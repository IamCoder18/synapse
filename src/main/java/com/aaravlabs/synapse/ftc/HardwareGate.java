package com.aaravlabs.synapse.ftc;

import com.aaravlabs.synapse.LogSink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The gate over the orchestrator's hardware thread: named <b>labels</b>, per-label
 * <b>restrictions</b>, and per-action <b>slots</b>. This is the policy layer that
 * decides whether a piece of scheduled hardware work may reach the thread at all.
 *
 * <h2>Labels</h2>
 *
 * <p>Every hardware-thread submission may carry any number of labels. A label is a
 * {@code /}-separated path (see {@link LabelPath} for the tilde escapes) and the path
 * structure is the hierarchy: {@code write/drive} is a sublabel of {@code write}.
 * Labelling is always optional — unlabelled work is ordinary work and is only reachable
 * by the two reserved roots:
 *
 * <ul>
 *   <li>{@link #ALL} — the everything label. Restricting it covers every hardware
 *       action, labelled or not.</li>
 *   <li>{@link #UNLABELLED} — covers exactly the actions that carry no label.</li>
 * </ul>
 *
 * <h2>Restrictions</h2>
 *
 * <p>{@link #restrict(String, GateMode)} covers the named label <b>and all its
 * sublabels</b> (downward only — restricting {@code write/drive} never holds
 * {@code write}). While a restriction covers an action it cannot run:
 *
 * <ul>
 *   <li>{@link GateMode#DROP} — discarded, counted, and reported through the action's
 *       caller (see {@link HardwareActionRejectedException});</li>
 *   <li>{@link GateMode#QUEUE} — held and run when nothing covers it any more, in
 *       submission order.</li>
 * </ul>
 *
 * <p>The fluent form adds a {@link ChildPolicy child policy} and <b>exceptions</b>
 * (carve-outs): {@code restrict("shooter").drop().except("shooter/write").apply()}
 * covers {@code shooter/**} except {@code shooter/write/**}. Exceptions carve out only
 * their own restriction — another restriction (a parent {@link #ALL} seal, say) still
 * applies to the uncovered labels — and an excepted subtree sits outside a
 * {@link ChildPolicy#SEALED seal} too, so it stays independently gateable.
 *
 * <p>An action carrying several labels is covered if <b>any</b> of its labels is
 * covered; the strictest covering restriction decides (DROP beats QUEUE beats running).
 * Held actions are re-evaluated on every gate change: if a later restriction starts
 * dropping them they are dropped then, and once all their labels are free they run in
 * submission order.
 *
 * <h2>Slots</h2>
 *
 * <p>A submission may also claim a slot. At most one <em>pending</em> (not yet started)
 * action per slot exists at any time, wherever it waits — behind a restriction or just
 * queued behind busy hardware work: a newer action with the same slot supersedes the
 * older one, which is rejected with
 * {@link HardwareActionRejectedException.Reason#SUPERSEDED}. Only the most recent
 * action per slot is used, so a slotted stream (a 50 Hz setpoint, say) can never pile
 * up. Slots are opaque strings — no hierarchy, no escapes. Periodic work
 * ({@code @RunPeriodically(hardware = true)} ticks, {@code bulkRead} readers) has
 * labels but no slots: a tick is stale the moment it is late, so a tick whose labels are
 * restricted is simply skipped under either mode and the loop resumes on release.
 *
 * <h2>The one rule</h2>
 *
 * <p><b>An action runs, or its caller finds out why it didn't — and nothing crashes.</b>
 * Fire-and-forget work is counted and summarised in one warning when its label is
 * released; futures complete exceptionally with a
 * {@link HardwareActionRejectedException reason}; blocking {@code call} fails fast with
 * the same checked exception and never waits behind a hold. Refused gate operations
 * return {@code false} and log a warning — never throw.
 *
 * <p>Obtain via {@code orchestrator.hardware()} ({@link HardwareActions#restrict},
 * {@link HardwareActions#release}, …). Thread-safe.
 */
public final class HardwareGate {

    /** The everything label: restricting it covers every hardware action. */
    public static final String ALL = "~all";

    /** Covers exactly the hardware actions that carry no label at all. */
    public static final String UNLABELLED = "~unlabelled";

    /**
     * How many held actions one restriction may accumulate before the oldest are
     * dropped with a warning. Slotted streams are already bounded at one pending
     * action per slot; this is the safety valve for unslotted floods in QUEUE mode.
     */
    private static final int MAX_HELD_PER_RESTRICTION = 1024;

    /** What happened to a submission at gate time. */
    public enum Fate {
        /** Handed to the hardware thread. */
        DISPATCHED,
        /** Held by a QUEUE-mode restriction; will run when nothing covers it. */
        HELD,
        /** Prevented from running; {@code onReject} has been notified. */
        DROPPED
    }

    private final String name;
    private final LogSink log;
    private final Executor hardwareThread;

    private final Object lock = new Object();
    /** Restrictions by node key, in insertion order (deterministic attribution). */
    private final Map<String, Restriction> restrictions = new LinkedHashMap<>();
    /** Held actions in submission order. */
    private final List<Task> held = new ArrayList<>();
    /** At most one pending (not-yet-started) action per slot. */
    private final Map<String, Task> pendingBySlot = new HashMap<>();
    private boolean closed;

    /**
     * @param name orchestrator name, for log tags
     * @param log where warnings and summaries go
     * @param hardwareThread the single hardware-thread executor to dispatch onto
     */
    public HardwareGate(String name, LogSink log, Executor hardwareThread) {
        this.name = Objects.requireNonNull(name, "name");
        this.log = Objects.requireNonNull(log, "log");
        this.hardwareThread = Objects.requireNonNull(hardwareThread, "hardwareThread");
    }

    // ------------------------------------------------------------------
    // submissions
    // ------------------------------------------------------------------

    /**
     * Submit one shot of hardware work through the gate. Parses {@code labels},
     * applies slot coalescing, resolves the action's fate against every active
     * restriction, and either dispatches it to the hardware thread, holds it, or
     * rejects it.
     *
     * @param labels action labels (null or empty = unlabelled); must not contain the
     *        reserved {@link #ALL} or {@link #UNLABELLED}
     * @param slot coalescing slot, or null for none
     * @param body the work, run on the hardware thread
     * @param onReject notified (off the gate lock) if the action cannot run; may be null
     * @param queueable false for blocking {@code call}: a held fate becomes an
     *        immediate drop instead of a hold
     * @return the fate at gate time
     * @throws IllegalArgumentException on a malformed or reserved label, or an empty slot
     */
    public Fate submit(String[] labels, String slot, Runnable body,
                       Consumer<Exception> onReject, boolean queueable) {
        Objects.requireNonNull(body, "body");
        GateNode[] nodes = parseActionLabels(labels);
        if (slot != null && slot.isEmpty()) {
            throw new IllegalArgumentException("slot must not be empty; pass null for none");
        }
        Task task = new Task(nodes, slot, body, onReject, queueable);
        List<Runnable> deferred = new ArrayList<>(2);
        // User callbacks (future completions) run via fire() after the lock is
        // released: they may call back into the gate (release, restrict, submit…).
        Fate fate = submitLocked(task, deferred);
        fire(deferred);
        return fate;
    }

    private Fate submitLocked(Task task, List<Runnable> deferred) {
        synchronized (lock) {
            if (closed) {
                log.warn(name, "hardware action submitted to a closed orchestrator; dropped");
                task.rejectWith(new IllegalStateException("orchestrator is closed"), deferred);
                return Fate.DROPPED;
            }
            if (task.slot != null) {
                Task prev = pendingBySlot.get(task.slot);
                if (prev != null && prev.state == Task.State.PENDING) {
                    // Only the most recent action per slot is used. A newer action
                    // supersedes the previous one wherever it waits — held by the
                    // gate or already queued on the hardware thread (its wrapper
                    // sees SUPERSEDED and skips the body).
                    removeFromHeld(prev);
                    prev.rejectWith(new HardwareActionRejectedException(
                            HardwareActionRejectedException.Reason.SUPERSEDED,
                            "hardware action (slot '" + task.slot + "') was superseded by a newer "
                                    + "action with the same slot before it ran; only the most "
                                    + "recent action per slot is used"), deferred);
                }
                pendingBySlot.put(task.slot, task);
            }
            Fate fate = resolveFate(task);
            if (fate == Fate.DROPPED || (fate == Fate.HELD && !task.queueable)) {
                Restriction cause = attribute(task, fate == Fate.DROPPED);
                if (cause != null) {
                    cause.droppedActions++;
                }
                if (task.slot != null && pendingBySlot.get(task.slot) == task) {
                    pendingBySlot.remove(task.slot);
                }
                task.rejectWith(new HardwareActionRejectedException(
                        HardwareActionRejectedException.Reason.DROPPED_BY_GATE,
                        dropMessage(task, cause, fate == Fate.HELD)), deferred);
                return Fate.DROPPED;
            }
            if (fate == Fate.HELD) {
                task.attribution = attribute(task, false);
                held.add(task);
                trimHeld(task.attribution, deferred);
                return Fate.HELD;
            }
            dispatch(task);
            return Fate.DISPATCHED;
        }
    }

    /**
     * Whether a periodic tick with these labels may run right now. Ticks are never
     * held or queued — a tick is stale the moment it is late — so any active
     * restriction covering the labels (DROP or QUEUE alike) skips the tick and counts
     * it.
     *
     * @param labels tick labels (null or empty = unlabelled)
     * @return true if the tick may run
     * @throws IllegalArgumentException on a malformed or reserved label
     */
    public boolean tickAllowed(String[] labels) {
        GateNode[] nodes = parseActionLabels(labels);
        synchronized (lock) {
            if (closed) {
                return false;
            }
            Restriction cause = null;
            boolean covered = false;
            for (Restriction r : restrictions.values()) {
                if (isShadowed(r) || !r.coversAction(nodes, nodes.length == 0)) {
                    continue;
                }
                covered = true;
                if (cause == null) {
                    cause = r;
                }
            }
            if (covered && cause != null) {
                cause.skippedTicks++;
            }
            return !covered;
        }
    }

    /**
     * Validate action labels the way {@link #submit} will: throws on malformed or
     * reserved input. Called at annotation binding so a bad label fails at
     * {@code registerNode}, not at first dispatch.
     *
     * @param labels the labels to validate (null or empty is fine)
     * @throws IllegalArgumentException on a malformed or reserved label
     */
    public static void validateLabels(String[] labels) {
        parseActionLabels(labels);
    }

    // ------------------------------------------------------------------
    // gate operations
    // ------------------------------------------------------------------

    /**
     * Restrict {@code label} and all its sublabels with an
     * {@link ChildPolicy#INDEPENDENT} child policy. Sugar for the common case; use
     * {@link #restrict(String)} for child policy and exceptions.
     *
     * @param label the label to restrict ({@link #ALL}, {@link #UNLABELLED}, or a path)
     * @param mode drop covered work or hold it
     * @return true if the restriction was applied; false (with a warning) if refused —
     *         malformed label, or a {@link ChildPolicy#SEALED} ancestor forbids it
     */
    public boolean restrict(String label, GateMode mode) {
        return restrict(label, mode, ChildPolicy.INDEPENDENT);
    }

    /**
     * Restrict {@code label} and all its sublabels.
     *
     * @param label the label to restrict
     * @param mode drop covered work or hold it
     * @param childPolicy whether children may be restricted/released independently
     * @return true if applied; false (with a warning) if refused
     */
    public boolean restrict(String label, GateMode mode, ChildPolicy childPolicy) {
        return restrictNode(label, mode, childPolicy, new GateNode[0]);
    }

    /**
     * Fluent form of {@link #restrict(String, GateMode, ChildPolicy)}:
     * {@code gate.restrict("shooter").drop().sealChildren().except("shooter/write").apply()}.
     * The mode ({@code drop()} or {@code queue()}) is required before {@code apply()};
     * everything else is optional.
     *
     * @param label the label to restrict
     * @return a builder that applies the restriction on {@link RestrictionBuilder#apply()}
     */
    public RestrictionBuilder restrict(String label) {
        return new RestrictionBuilder(this, label);
    }

    /**
     * Release the restriction registered at exactly {@code label}. Restrictions on
     * other labels (parents, children) are untouched. Held work that is now uncovered
     * runs in submission order; the drop/tick counter summary for the restriction is
     * logged here.
     *
     * @param label the label to release
     * @return true if a restriction was removed; false (with a warning) if refused —
     *         malformed label, sealed ancestor, or nothing restricted at that label
     */
    public boolean release(String label) {
        GateNode node = parseGateNode(label);
        if (node == null) {
            return false;
        }
        List<Runnable> deferred = new ArrayList<>(2);
        synchronized (lock) {
            if (closed) {
                log.warn(name, "release('" + label + "') refused: orchestrator is closed");
                return false;
            }
            Restriction seal = sealOver(node);
            if (seal != null) {
                log.warn(name, "release('" + label + "') refused: '" + seal.node
                        + "' is sealed (ChildPolicy.SEALED) and governs it; release '"
                        + seal.node + "' first");
                return false;
            }
            Restriction removed = restrictions.remove(node.key());
            if (removed == null) {
                log.warn(name, "release('" + label + "') refused: no restriction at that label");
                return false;
            }
            if (removed.droppedActions > 0 || removed.skippedTicks > 0) {
                log.warn(name, "released '" + label + "': dropped "
                        + removed.droppedActions + " hardware action(s), skipped "
                        + removed.skippedTicks + " periodic tick(s) while restricted");
            }
            log.info(name, "released '" + label + "'");
            reevaluate(deferred);
        }
        fire(deferred);
        return true;
    }

    /**
     * Whether a hypothetical action labelled {@code label} (or, for {@link #UNLABELLED},
     * an unlabelled one) would be covered by an active restriction right now — i.e.
     * whether it would be held or dropped. For {@link #ALL}: whether every hardware
     * action is covered.
     *
     * @param label the label to query
     * @return true if covered by an active restriction; false if free, malformed, or
     *         covered only by {@linkplain ChildPolicy#SEALED sealed-away} suspended
     *         restrictions
     */
    public boolean isRestricted(String label) {
        GateNode node = parseGateNode(label);
        if (node == null) {
            return false;
        }
        synchronized (lock) {
            for (Restriction r : restrictions.values()) {
                if (!isShadowed(r) && r.coversNode(node)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Reject everything still held, clear all restrictions and slots, and refuse
     * further work. Called from {@code Orchestrator.close()}.
     */
    public void close() {
        List<Runnable> deferred = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Task t : held) {
                t.rejectWith(new IllegalStateException("orchestrator is closed"), deferred);
            }
            held.clear();
            for (Task t : pendingBySlot.values()) {
                if (t.state == Task.State.PENDING) {
                    t.rejectWith(new IllegalStateException("orchestrator is closed"), deferred);
                }
            }
            pendingBySlot.clear();
            restrictions.clear();
        }
        fire(deferred);
    }

    private boolean restrictNode(String label, GateMode mode, ChildPolicy childPolicy,
                                 GateNode[] exceptions) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(childPolicy, "childPolicy");
        GateNode node = parseGateNode(label);
        if (node == null) {
            return false;
        }
        for (GateNode e : exceptions) {
            if (e == null) {
                log.warn(name, "restrict('" + label + "') refused: exception label is malformed");
                return false;
            }
            if (!node.coversNode(e)) {
                log.warn(name, "restrict('" + label + "') refused: exception '" + e
                        + "' is outside the covered subtree of '" + node + "'");
                return false;
            }
        }
        List<Runnable> deferred = new ArrayList<>(2);
        synchronized (lock) {
            if (closed) {
                log.warn(name, "restrict('" + label + "') refused: orchestrator is closed");
                return false;
            }
            Restriction seal = sealOver(node);
            if (seal != null) {
                log.warn(name, "restrict('" + label + "') refused: '" + seal.node
                        + "' is sealed (ChildPolicy.SEALED) and governs it; release '"
                        + seal.node + "' first");
                return false;
            }
            restrictions.put(node.key(),
                    new Restriction(node, mode, childPolicy, exceptions));
            log.info(name, "restricted '" + node + "' (" + mode + ", " + childPolicy
                    + (exceptions.length == 0 ? "" : ", except " + Arrays.toString(exceptions)) + ")");
            reevaluate(deferred);
        }
        fire(deferred);
        return true;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** Where a held or dropped action is attributed, for counters and overflow. */
    private Restriction attribute(Task task, boolean forDrop) {
        for (Restriction r : restrictions.values()) {
            if (isShadowed(r) || !r.coversAction(task.labels, task.labels.length == 0)) {
                continue;
            }
            if (!forDrop || r.mode == GateMode.DROP) {
                return r;
            }
        }
        return null;
    }

    private Fate resolveFate(Task task) {
        boolean unlabelled = task.labels.length == 0;
        boolean held = false;
        for (Restriction r : restrictions.values()) {
            if (isShadowed(r) || !r.coversAction(task.labels, unlabelled)) {
                continue;
            }
            if (r.mode == GateMode.DROP) {
                return Fate.DROPPED;
            }
            held = true;
        }
        return held ? Fate.HELD : Fate.DISPATCHED;
    }

    /**
     * A restriction is suspended while a sealed restriction strictly above it governs
     * it: the seal alone decides, and the suspended restriction re-applies when the
     * seal lifts.
     */
    private boolean isShadowed(Restriction r) {
        return sealOver(r.node) != null;
    }

    /** The active sealed restriction governing {@code node}'s strict subtree, if any. */
    private Restriction sealOver(GateNode node) {
        for (Restriction s : restrictions.values()) {
            if (s.childPolicy != ChildPolicy.SEALED || !s.node.coversNode(node)) {
                continue;
            }
            if (s.node.key().equals(node.key())) {
                continue; // a seal governs strict descendants; its own node is releasable
            }
            boolean carved = false;
            for (GateNode e : s.exceptions) {
                if (e.coversNode(node)) {
                    carved = true;
                    break;
                }
            }
            if (carved) {
                continue;
            }
            // A shadowed seal exerts no authority — the seal above it decides.
            if (isShadowed(s)) {
                continue;
            }
            return s;
        }
        return null;
    }

    /** Re-resolve every held action after a gate change. Order is preserved. */
    private void reevaluate(List<Runnable> deferred) {
        if (held.isEmpty()) {
            return;
        }
        List<Task> still = new ArrayList<>(held.size());
        for (Task t : held) {
            Fate fate = resolveFate(t);
            if (fate == Fate.DISPATCHED) {
                dispatch(t);
            } else if (fate == Fate.HELD) {
                still.add(t);
            } else {
                Restriction cause = attribute(t, true);
                if (cause != null) {
                    cause.droppedActions++;
                }
                if (t.slot != null && pendingBySlot.get(t.slot) == t) {
                    pendingBySlot.remove(t.slot);
                }
                t.rejectWith(new HardwareActionRejectedException(
                        HardwareActionRejectedException.Reason.DROPPED_BY_GATE,
                        dropMessage(t, cause, false)), deferred);
            }
        }
        held.clear();
        held.addAll(still);
    }

    /** Drop-oldest safety valve for unslotted floods behind one restriction. */
    private void trimHeld(Restriction attribution, List<Runnable> deferred) {
        if (attribution == null) {
            return;
        }
        int count = 0;
        Task oldest = null;
        for (Task t : held) {
            if (t.attribution == attribution) {
                count++;
                if (oldest == null) {
                    oldest = t;
                }
            }
        }
        if (count <= MAX_HELD_PER_RESTRICTION || oldest == null) {
            return;
        }
        held.remove(oldest);
        attribution.droppedActions++;
        if (oldest.slot != null && pendingBySlot.get(oldest.slot) == oldest) {
            pendingBySlot.remove(oldest.slot);
        }
        oldest.rejectWith(new HardwareActionRejectedException(
                HardwareActionRejectedException.Reason.DROPPED_BY_GATE,
                "held queue for '" + attribution.node + "' overflowed ("
                        + MAX_HELD_PER_RESTRICTION + " actions); dropped the oldest held action"),
                deferred);
        log.warn(name, "held queue for '" + attribution.node + "' exceeded "
                + MAX_HELD_PER_RESTRICTION + " actions; dropped the oldest");
    }

    private void dispatch(Task task) {
        hardwareThread.execute(() -> {
            synchronized (lock) {
                if (task.state != Task.State.PENDING) {
                    return; // superseded or dropped while queued
                }
                task.state = Task.State.RUNNING;
                if (task.slot != null && pendingBySlot.get(task.slot) == task) {
                    pendingBySlot.remove(task.slot);
                }
            }
            try {
                task.body.run();
            } catch (Throwable t) {
                log.error(name, "hardware-thread task threw", t);
            }
            synchronized (lock) {
                if (task.state == Task.State.RUNNING) {
                    task.state = Task.State.DONE;
                }
            }
        });
    }

    private void removeFromHeld(Task task) {
        for (Iterator<Task> it = held.iterator(); it.hasNext(); ) {
            if (it.next() == task) {
                it.remove();
                return;
            }
        }
    }

    private String dropMessage(Task task, Restriction cause, boolean heldButNotQueueable) {
        StringBuilder sb = new StringBuilder("hardware action ");
        if (task.labels.length > 0) {
            sb.append("with label(s) ").append(Arrays.toString(task.labels)).append(' ');
        } else {
            sb.append("with no label ");
        }
        if (task.slot != null) {
            sb.append("(slot '").append(task.slot).append("') ");
        }
        if (heldButNotQueueable) {
            sb.append("is held by a restriction on '").append(cause == null ? "?" : cause.node)
                    .append("' and blocking call() does not wait behind a gate hold; ")
                    .append("use callAsync(...) or run(...), or release the label");
        } else {
            sb.append("was dropped: ").append(cause == null ? "the gate covers it" : "'" + cause.node + "' is restricted")
                    .append(" (GateMode.DROP); release the label (or an ancestor) to let it through");
        }
        return sb.toString();
    }

    /** Run deferred reject callbacks (user code) off the gate lock. */
    private static void fire(List<Runnable> deferred) {
        for (Runnable r : deferred) {
            r.run();
        }
    }

    private static GateNode[] parseActionLabels(String[] labels) {
        if (labels == null || labels.length == 0) {
            return GateNode.NONE;
        }
        GateNode[] out = new GateNode[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = GateNode.parseActionLabel(labels[i]);
        }
        return out;
    }

    /** Gate-op parse: warn and return null on malformed input (never throws). */
    private GateNode parseGateNode(String label) {
        if (label == null) {
            log.warn(name, "gate operation refused: label must not be null");
            return null;
        }
        GateNode node = GateNode.parse(label);
        if (node == null) {
            log.warn(name, "gate operation refused: malformed label '" + label
                    + "' ('~' escapes '~' as '~0' and '/' as '~1'; segments are '/'-separated "
                    + "and must be non-empty)");
            return null;
        }
        return node;
    }

    // ------------------------------------------------------------------
    // internal types
    // ------------------------------------------------------------------

    /** A gate tree node: the root {@link #ALL}, {@link #UNLABELLED}, or a label path. */
    private static final class GateNode {
        static final GateNode[] NONE = new GateNode[0];

        private static final int KIND_ALL = 0;
        private static final int KIND_UNLABELLED = 1;
        private static final int KIND_PATH = 2;

        final int kind;
        final LabelPath path; // null unless KIND_PATH

        private GateNode(int kind, LabelPath path) {
            this.kind = kind;
            this.path = path;
        }

        /** For gate ops: {@link HardwareGate#ALL}, {@link HardwareGate#UNLABELLED}, or a path; null if malformed. */
        static GateNode parse(String s) {
            if (HardwareGate.ALL.equals(s)) {
                return new GateNode(KIND_ALL, null);
            }
            if (HardwareGate.UNLABELLED.equals(s)) {
                return new GateNode(KIND_UNLABELLED, null);
            }
            LabelPath p = LabelPath.tryParse(s);
            return p == null ? null : new GateNode(KIND_PATH, p);
        }

        /** For action labels: paths only; the roots are reserved. Throws on bad input. */
        static GateNode parseActionLabel(String s) {
            if (HardwareGate.ALL.equals(s) || HardwareGate.UNLABELLED.equals(s)) {
                throw new IllegalArgumentException("label '" + s
                        + "' is reserved for gate operations and cannot label an action");
            }
            return new GateNode(KIND_PATH, LabelPath.parse(s));
        }

        String key() {
            switch (kind) {
                case KIND_ALL: return HardwareGate.ALL;
                case KIND_UNLABELLED: return HardwareGate.UNLABELLED;
                default: return path.raw();
            }
        }

        /** Whether an action with {@code labels} (or unlabelled) is covered by this node. */
        boolean coversAction(GateNode[] labels, boolean unlabelled) {
            switch (kind) {
                case KIND_ALL:
                    return true;
                case KIND_UNLABELLED:
                    return unlabelled;
                default:
                    for (GateNode l : labels) {
                        if (path.covers(l.path)) {
                            return true;
                        }
                    }
                    return false;
            }
        }

        /** Whether this node is {@code other} or an ancestor of it (self-or-descendant). */
        boolean coversNode(GateNode other) {
            switch (kind) {
                case KIND_ALL:
                    return true;
                case KIND_UNLABELLED:
                    return other.kind == KIND_UNLABELLED;
                default:
                    return other.kind == KIND_PATH && path.covers(other.path);
            }
        }

        @Override
        public String toString() {
            return key();
        }
    }

    private static final class Restriction {
        final GateNode node;
        final GateMode mode;
        final ChildPolicy childPolicy;
        final GateNode[] exceptions;
        int droppedActions;
        int skippedTicks;

        Restriction(GateNode node, GateMode mode, ChildPolicy childPolicy, GateNode[] exceptions) {
            this.node = node;
            this.mode = mode;
            this.childPolicy = childPolicy;
            this.exceptions = exceptions;
        }

        boolean coversAction(GateNode[] labels, boolean unlabelled) {
            if (!node.coversAction(labels, unlabelled)) {
                return false;
            }
            for (GateNode e : exceptions) {
                if (e.coversAction(labels, unlabelled)) {
                    return false;
                }
            }
            return true;
        }

        boolean coversNode(GateNode other) {
            return node.coversNode(other) && !carves(other);
        }

        private boolean carves(GateNode other) {
            for (GateNode e : exceptions) {
                if (e.coversNode(other)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Task {
        enum State { PENDING, RUNNING, DONE, SUPERSEDED, REJECTED }

        final GateNode[] labels;
        final String slot;
        final Runnable body;
        final Consumer<Exception> onReject;
        final boolean queueable;
        State state = State.PENDING;
        Restriction attribution;
        Exception rejection;

        Task(GateNode[] labels, String slot, Runnable body,
             Consumer<Exception> onReject, boolean queueable) {
            this.labels = labels;
            this.slot = slot;
            this.body = body;
            this.onReject = onReject;
            this.queueable = queueable;
        }

        /** Mark rejected and queue the caller notification to run off the gate lock. */
        void rejectWith(Exception e, List<Runnable> deferred) {
            if (state != State.PENDING) {
                return;
            }
            state = State.REJECTED;
            rejection = e;
            if (onReject != null) {
                final Exception ex = e;
                deferred.add(() -> onReject.accept(ex));
            }
        }
    }

    /**
     * Fluent builder for a restriction with a {@link ChildPolicy child policy} and
     * exceptions. Obtain via {@link HardwareGate#restrict(String)} or
     * {@link HardwareActions#restrict(String)}. Not thread-safe; single use.
     */
    public static final class RestrictionBuilder {
        private final HardwareGate gate;
        private final String label;
        private GateMode mode;
        private ChildPolicy childPolicy = ChildPolicy.INDEPENDENT;
        private final List<GateNode> exceptions = new ArrayList<>();

        RestrictionBuilder(HardwareGate gate, String label) {
            this.gate = gate;
            this.label = label;
        }

        /** Covered work is dropped (never runs). */
        public RestrictionBuilder drop() {
            this.mode = GateMode.DROP;
            return this;
        }

        /** Covered work is held and runs when the restriction lifts. */
        public RestrictionBuilder queue() {
            this.mode = GateMode.QUEUE;
            return this;
        }

        /**
         * This restriction alone decides for its covered subtree: gate operations on
         * covered descendants are refused while it is active, and pre-existing child
         * restrictions are suspended until it lifts.
         */
        public RestrictionBuilder sealChildren() {
            this.childPolicy = ChildPolicy.SEALED;
            return this;
        }

        /**
         * Carve labels out of this restriction: {@code except("shooter/write")} also
         * uncovers {@code shooter/write/power} and every other sublabel. The carve-out
         * is local to this restriction — other restrictions still apply — and an
         * excepted subtree is independently gateable even under {@link #sealChildren()}.
         *
         * @param labels labels (with their sublabels) to exclude
         * @return this builder
         */
        public RestrictionBuilder except(String... labels) {
            for (String l : labels) {
                // A malformed exception becomes null here and is refused in apply(),
                // which reports it as a gate operation (warn + false) like its siblings.
                exceptions.add(l == null ? null : GateNode.parse(l));
            }
            return this;
        }

        /**
         * Apply the restriction.
         *
         * @return true if applied; false (with a warning) if refused — no mode chosen,
         *         malformed label or exception, exception outside the subtree, or a
         *         sealed ancestor
         */
        public boolean apply() {
            if (mode == null) {
                gate.log.warn(gate.name, "restrict('" + label
                        + "') refused: no mode chosen; call .drop() or .queue() before .apply()");
                return false;
            }
            GateNode[] excepts = exceptions.toArray(new GateNode[0]);
            return gate.restrictNode(label, mode, childPolicy, excepts);
        }
    }
}
