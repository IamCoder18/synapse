package com.aaravlabs.synapse;

import com.aaravlabs.synapse.internal.AnnotationBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Default {@link Orchestrator} implementation. Owns the topic registry, the
 * four workers (scheduler pool, callback pool, action pool, and the dedicated
 * hardware thread), the node registry, and the action registry.
 *
 * <p>Thread-pool layout:
 * <ul>
 *   <li><b>Scheduler</b> ({@link ScheduledExecutorService}, fixed size 8) runs
 *       {@link com.aaravlabs.synapse.annotation.RunPeriodically} loops and one-shot timers.</li>
 *   <li><b>Callback pool</b> ({@link ThreadPoolExecutor}, core 4, max 16, bounded queue
 *       of 256) runs {@link com.aaravlabs.synapse.annotation.SubscribedTo} callbacks. When
 *       the queue is full, tasks fall back to running on the caller thread (the
 *       {@link ThreadPoolExecutor.CallerRunsPolicy}) so dispatchers back-pressure
 *       naturally instead of dropping work.</li>
 *   <li><b>Action pool</b> ({@link ThreadPoolExecutor}, core 0, unbounded max, {@link
 *       SynchronousQueue}) runs {@link com.aaravlabs.synapse.annotation.RunnableAction}
 *       methods. Unbounded so a long action cannot be rejected.</li>
 *   <li><b>Hardware thread</b> (single dedicated thread) runs every piece of
 *       hardware-touching work: {@code @OnHardwareThread} callbacks,
 *       {@code @RunPeriodically(hardware = true)} loops, and everything submitted
 *       through {@link com.aaravlabs.synapse.ftc.HardwareActions}.</li>
 * </ul>
 */
public final class OrchestratorImpl implements Orchestrator {

    private final String name;
    private final LogSink log;

    private final java.util.Map<String, Topic<?>> topics = new ConcurrentHashMap<>();
    private final java.util.Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final java.util.Map<String, RunnableActionEntry> actions = new ConcurrentHashMap<>();

    // Per-topic subscriber list, with copy-on-write snapshots for safe iteration.
    private final java.util.Map<Topic<?>, SubscriberList> subscribers = new ConcurrentHashMap<>();

    // Per-node bookkeeping so unregisterNode can clean up cleanly.
    private final java.util.Map<Node, NodeResources> nodeResources = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor callbacks;
    private final ThreadPoolExecutor actionPool;

    /**
     * Dedicated single-thread executor for all hardware-touching work in the
     * orchestrator. {@link com.aaravlabs.synapse.annotation.OnHardwareThread}-annotated
     * callbacks and {@code @RunPeriodically(hardware = true)} loops both submit
     * here, guaranteeing serial execution on the same OS thread.
     */
    private final java.util.concurrent.ScheduledExecutorService hardwareThread;

    // Immutable one-field views over this orchestrator, so they are built once
    // rather than allocated per hardware() call or per bulk-read registration.
    private final com.aaravlabs.synapse.ftc.HardwareActions hardwareActions;
    private final com.aaravlabs.synapse.ftc.HardwareView hardwareView;

    private volatile boolean closed = false;

    private OrchestratorImpl(String name, LogSink log) {
        this.name = Objects.requireNonNull(name);
        this.log = Objects.requireNonNull(log);

        ThreadFactory tf = makeThreadFactory("synapse-" + name + "-");
        ThreadFactory hwTf = makeHardwareThreadFactory();

        this.scheduler = Executors.newScheduledThreadPool(8, tf);

        this.callbacks = new ThreadPoolExecutor(
                4, 16, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy());

        this.actionPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                tf);

        // Single dedicated thread — all hardware-bound callbacks and periodic
        // loops share this thread and run strictly serially. We use the scheduled
        // variant so both `execute` (for callbacks) and `scheduleWithFixedDelay`
        // (for hardware loops) go to the same executor / thread.
        this.hardwareThread = java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor(hwTf);

        this.hardwareActions = new com.aaravlabs.synapse.ftc.HardwareActions(this);
        this.hardwareView = new com.aaravlabs.synapse.ftc.HardwareView(this);
    }

    private volatile Thread hardwareThreadThread;

    private ThreadFactory makeHardwareThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "synapse-" + name + "-hw-" + counter.incrementAndGet());
            t.setDaemon(true);
            // Capture the OS thread so we can compare against Thread.currentThread()
            // in isHardwareThread(). The ThreadFactory's newThread() runs exactly
            // once for a newSingleThreadScheduledExecutor, so this is reliable.
            hardwareThreadThread = t;
            return t;
        };
    }

    static OrchestratorImpl create(String name, LogSink log) {
        return new OrchestratorImpl(name, log);
    }

    private ThreadFactory makeThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ---- identity --------------------------------------------------------

    @Override public String name() { return name; }

    // ---- topics ----------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> Topic<T> getOrCreateTopic(String topicName, Class<T> type) {
        Objects.requireNonNull(topicName, "topicName");
        Objects.requireNonNull(type, "type");

        Topic<?> existing = topics.get(topicName);
        if (existing != null) {
            if (!existing.acceptsType(type)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + existing.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            if (!existing.safelyReturnsAs(type)) {
                // compatible, but this topic may hold values the caller cannot cast to T.
                // Returning it anyway defers a ClassCastException to an unrelated line.
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' holds "
                                + existing.type().getSimpleName() + ", which cannot be "
                                + "returned as " + type.getName()
                                + "; read it via findTopic(name) and treat the value as "
                                + existing.type().getName());
            }
            return (Topic<T>) existing;
        }

        Topic<T> created = new Topic<>(topicName, type);
        Topic<?> prior = topics.putIfAbsent(topicName, created);
        if (prior != null) {
            if (!prior.acceptsType(type)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + prior.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            if (!prior.safelyReturnsAs(type)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' holds "
                                + prior.type().getSimpleName() + ", which cannot be "
                                + "returned as " + type.getName()
                                + "; read it via findTopic(name) and treat the value as "
                                + prior.type().getName());
            }
            return (Topic<T>) prior;
        }
        log.info(name, "created topic " + created);
        return created;
    }

    /**
     * Topic lookup that skips the cast-safety check for callers whose handler receives
     * {@code Object}. Used by the annotation binder, where an Object-typed topic is a
     * supported configuration rather than a mistake.
     */
    private Topic<?> getOrCreateTopicUnchecked(String topicName, Class<?> type) {
        Objects.requireNonNull(topicName, "topicName");
        Objects.requireNonNull(type, "type");
        Topic<?> existing = topics.get(topicName);
        if (existing != null) {
            if (!existing.acceptsType(type)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + existing.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            return existing;
        }
        Topic<?> created = new Topic<>(topicName, type);
        Topic<?> prior = topics.putIfAbsent(topicName, created);
        if (prior != null) {
            if (!prior.acceptsType(type)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + prior.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            return prior;
        }
        log.info(name, "created topic " + created);
        return created;
    }

    @Override
    public Optional<Topic<?>> findTopic(String topicName) {
        return Optional.ofNullable(topics.get(topicName));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<Topic<T>> findTopic(String topicName, Class<T> type) {
        Topic<?> t = topics.get(topicName);
        if (t == null || !t.acceptsType(type) || !t.safelyReturnsAs(type)) {
            return Optional.empty();
        }
        return Optional.of((Topic<T>) t);
    }

    // ---- publish listeners ------------------------------------------------

    // Copy-on-write: iteration happens on the publishing thread and must be
    // lock-free, and registration is rare. A volatile read of the field is what
    // keeps publish cheap when nobody is listening.
    private final java.util.List<PublishListener> publishListeners = new CopyOnWriteArrayList<>();

    @Override
    public void addPublishListener(PublishListener listener) {
        if (listener != null) publishListeners.add(listener);
    }

    @Override
    public void removePublishListener(PublishListener listener) {
        if (listener != null) publishListeners.remove(listener);
    }

    /**
     * Package-private: how many listeners are currently registered.
     *
     * <p>Exists for one test. Concurrent remove/add of the same listener can
     * leave duplicates behind -- {@link java.util.concurrent.CopyOnWriteArrayList}
     * removes only the first equal element -- and a duplicate is invisible to
     * every other assertion available from outside, because the extra
     * registration is a no-op that still has to be iterated and logged.
     * Exposing the size is what lets the churn test enforce the bound it
     * exists to test rather than merely claim it.
     */
    int publishListenerCount() {
        return publishListeners.size();
    }

    // ---- publish ---------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> void publish(String topicName, T value) {
        if (closed) {
            log.warn(name, "publish to '" + topicName + "' on closed orchestrator ignored");
            return;
        }
        if (value == null) {
            throw new IllegalArgumentException("publish value cannot be null");
        }

        // One timestamp for every listener, taken before the type check so a
        // listener never observes a later instant than the publish itself.
        // Guarded so the common case -- nobody listening -- is one read.
        //
        // Two guards above run *before* this block, so their failures are not
        // observable through a listener, and the two IllegalArgumentExceptions
        // this method can throw are not equivalent:
        //
        //   closed orchestrator -> warn, return, listeners not notified
        //   null value          -> throw,    listeners not notified
        //   type mismatch       -> listeners notified, THEN throw
        //
        // The split is argument validation versus a fault in an otherwise real
        // publish. A closed bus or a null value means the call never became a
        // publish: no topic was resolved, no latest value recorded, nothing
        // dispatched -- and there is no value to hand a listener, which is why
        // onPublish documents its value as never null. A type mismatch happens
        // after the bus has started acting, and is exactly the kind of fault a
        // recording should be able to show; the caller still sees the throw.
        java.util.List<PublishListener> listeners = publishListeners;
        if (!listeners.isEmpty()) {
            long now = System.nanoTime();
            // Iterate, never index. CopyOnWriteArrayList's size() and get(i)
            // each read the current array independently, so a listener that
            // unregistered a later one mid-publish left the cached size()
            // stale and get(i) threw IndexOutOfBoundsException. That call sits
            // inside the try below, so the bus did not break -- but the loop
            // aborted, every remaining listener was silently skipped for that
            // publish, and the log blamed a listener for "throwing" when the
            // list was merely shorter than expected. The iterator is backed by
            // a single stable snapshot, so one publish always notifies exactly
            // the listeners registered when it started.
            for (PublishListener listener : listeners) {
                try {
                    listener.onPublish(topicName, value, now);
                } catch (OutOfMemoryError heapGone) {
                    // The one deliberate exception to "a listener can never
                    // break the bus", and it is exactly one class. Heap
                    // exhaustion is the only condition where there is no
                    // publish left worth protecting, because the recovery
                    // path itself needs memory: log.error builds a message
                    // and fills in a stack trace, so stepping to the next
                    // listener would fail a second time in a worse place.
                    // Let it out and let the JVM deal with it.
                    throw heapGone;
                } catch (Throwable t) {
                    // Everything else is contained, including the sibling
                    // VirtualMachineErrors. Catching Throwable rather than
                    // Exception is the point: a diagnostics module on a
                    // robot fails with AssertionError or NoClassDefFoundError
                    // at least as often as it fails with a RuntimeException,
                    // and none of those may take the bus down.
                    //
                    // StackOverflowError in particular is the common one and
                    // is contained on purpose. A listener that recurses
                    // without a bound blows the stack, the JVM unwinds that
                    // listener's frames, and the stack is whole again by the
                    // time we are here -- the heap was never touched. The
                    // listener's bug is its own; failing the publish, and
                    // with it every subscriber, would be the hook causing
                    // the outage it exists to diagnose. If the stack is
                    // genuinely gone, the log call below raises a fresh
                    // StackOverflowError that escapes publish anyway, which
                    // is the right outcome for that case.
                    //
                    // InternalError and UnknownError are contained for the
                    // same reason: both are documented as serious VM
                    // failures, and no subclass of either marks the fatal
                    // instance, so this code cannot tell a fatal one from a
                    // benign one and does not guess. Silence in the hierarchy
                    // is not evidence that these are harmless, and is not read
                    // as such. What the hierarchy does settle is the family
                    // they belong to: neither is an OutOfMemoryError, so no
                    // instance of either reaches the rethrow above, and
                    // containment is the whole of the decision. The image is
                    // scanned across every module in the boot layer for that
                    // answer, not java.base alone, because catch
                    // (OutOfMemoryError) matches subclasses from any module:
                    // UnknownError has no subclass there at all, and the sole
                    // InternalError subclass is java.util.zip.ZipError. That is
                    // named as a fact about the family, not as a hazard this
                    // hook will meet -- the JDK documents ZipError as no longer
                    // used and obsolete, superseded by ZipException, so a
                    // corrupt archive raises something else today. What can be
                    // told is that the bus itself is fine: the fault is inside
                    // one listener's frame, and the other listeners and the
                    // subscribers have no dependence on it. Re-throwing a
                    // VirtualMachineError merely because of its type was the
                    // bug; the narrow case above is the one that is actually
                    // unrecoverable.
                    log.error(name, "publish listener threw", t);
                }
            }
        }

        // Lazily create the topic from the value's runtime type. This matches
        // Heron's behavior: publishers don't have to pre-register topics.
        Topic<?> topic = topics.get(topicName);
        if (topic == null) {
            topic = getOrCreateTopic(topicName, value.getClass());
        } else if (!topic.acceptsValueClass(value.getClass())) {
            throw new IllegalArgumentException(
                    "Topic '" + topicName + "' is typed " + topic.type().getName()
                            + " but publish got " + value.getClass().getName());
        }

        ((Topic<Object>) topic).recordLatest(value);

        SubscriberList list = subscribers.get(topic);
        if (list == null) return;
        MessageHandler[] snapshot = list.snapshot();
        for (MessageHandler h : snapshot) {
            dispatchCallback(h, value);
        }
    }

    private void dispatchCallback(MessageHandler handler, Object value) {
        callbacks.execute(() -> {
            try {
                handler.accept(value);
            } catch (Throwable t) {
                log.error(name, "subscriber threw on topic dispatch", t);
            }
        });
    }

    // ---- subscribe (public typed) ----------------------------------------

    @Override
    public <T> Subscription subscribe(String topicName, Class<T> type,
                                      Consumer<? super T> handler) {
        Objects.requireNonNull(handler, "handler");
        Topic<T> topic = getOrCreateTopic(topicName, type);
        // Class.cast() returns false for every argument when the class is primitive, so
        // casting with the raw type made every delivery throw ClassCastException, which
        // dispatchCallback swallowed into a log line -- the subscriber was registered,
        // never fired, and nothing else indicated why. Box the type for the runtime check;
        // Consumer<? super T> erases to accept(Object), so no cast on T is needed and
        // none would survive erasure anyway.
        final Class<?> boxedType = Topic.box(type);
        @SuppressWarnings("unchecked")
        MessageHandler wrapped = msg -> ((Consumer<Object>) handler).accept(boxedType.cast(msg));
        SubscriberList list = subscribers.computeIfAbsent(topic, k -> new SubscriberList());
        list.add(wrapped);
        return new Subscription(topic, wrapped, this);
    }

    /** Raw subscribe used by the annotation binder where the parameter type is reflective. */
    public Subscription subscribeRaw(String topicName, Class<?> type,
                                     java.util.function.Consumer<Object> handler) {
        // Deliberately no safelyReturnsAs check. This path exists for the annotation
        // binder, which uses Object as the topic type when handlers of differing
        // parameter types share a topic and does its own per-message isInstance filter.
        // The handler receives Object, so a wider topic is correct rather than unsafe.
        Topic<?> topic = getOrCreateTopicUnchecked(topicName, type);
        MessageHandler wrapped = handler::accept;
        SubscriberList list = subscribers.computeIfAbsent(topic, k -> new SubscriberList());
        list.add(wrapped);
        return new Subscription(topic, wrapped, this);
    }

    void removeSubscription(Subscription sub) {
        Topic<?> t = sub.topic();
        SubscriberList list = subscribers.get(t);
        // markSubscriptionAsHardwareThreaded may have replaced the list entry with a
        // wrapper, so look for whichever handler is actually registered. Searching only
        // sub.handler() missed the wrapper and left an unregistered @OnHardwareThread
        // subscriber running -- it kept receiving publishes, so an unregistered node
        // could still drive the hardware.
        MessageHandler registered = hardwareRerouted.getOrDefault(sub, sub.handler());
        if (list != null) list.remove(registered);
        hardwareRerouted.remove(sub);
    }

    /**
     * Re-target a subscription so its handler runs on the hardware thread instead
     * of the callback pool. Used by the binder when it sees {@code @SubscribedTo
     * + @OnHardwareThread}.
     */
    public void markSubscriptionAsHardwareThreaded(Subscription sub) {
        Topic<?> t = sub.topic();
        SubscriberList list = subscribers.get(t);
        if (list == null) return;
        MessageHandler original = sub.handler();
        MessageHandler wrapped = msg -> {
            if (closed) return;
            hardwareThread.execute(() -> {
                try {
                    original.accept(msg);
                } catch (Throwable th) {
                    log.error(name, "hardware-thread subscriber threw", th);
                }
            });
        };
        // Replace in the list. sub.handler() keeps returning the original so callers see
        // a stable identity, and hardwareRerouted records which handler actually sits in
        // the list so removeSubscription can find and remove exactly that one.
        list.replace(original, wrapped);
        hardwareRerouted.put(sub, wrapped);
    }

    private final java.util.Map<Subscription, MessageHandler> hardwareRerouted = new ConcurrentHashMap<>();

    // ---- fetch latest ----------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getLatestValue(String topicName, Class<T> type) {
        Topic<?> t = topics.get(topicName);
        // safelyReturnsAs, not just acceptsType: an Object topic accepts a request for
        // Integer, but handing back Optional<Integer> when the stored value is a String
        // moves a ClassCastException to the caller's own site, after isPresent() has
        // already told them the value is there.
        if (t == null || !t.acceptsType(type) || !t.safelyReturnsAs(type)) {
            return Optional.empty();
        }
        return (Optional<T>) t.latestValue();
    }

    // ---- nodes -----------------------------------------------------------

    @Override
    public Node registerNode(String nodeName, Node node) {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(node, "node");
        if (closed) throw new IllegalStateException("orchestrator is closed");

        Node prior = nodes.putIfAbsent(nodeName, node);
        if (prior != null) {
            log.warn(name, "node '" + nodeName + "' already registered; skipping");
            return prior;
        }

        // Ensure resources slot exists before binding so the binder can populate it.
        nodeResources.put(node, new NodeResources());

        AnnotationBinder.bindNode(this, node, nodeName);
        log.info(name, "registered node '" + nodeName + "' (" + node.getClass().getSimpleName() + ")");
        return node;
    }

    @Override
    public void unregisterNode(String nodeName) {
        Node node = nodes.remove(nodeName);
        if (node == null) return;
        AnnotationBinder.unbindNode(this, node);
        nodeResources.remove(node);
        log.info(name, "unregistered node '" + nodeName + "'");
    }

    @Override
    public Optional<Node> findNode(String name) {
        return Optional.ofNullable(nodes.get(name));
    }

    // ---- actions ---------------------------------------------------------

    @Override
    public CompletableFuture<Void> runAction(String actionName) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("orchestrator is closed"));
        }
        RunnableActionEntry entry = actions.get(actionName);
        if (entry == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "No action named '" + actionName + "'"));
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            actionPool.execute(() -> {
                try {
                    entry.method.setAccessible(true);
                    entry.method.invoke(entry.node);
                    future.complete(null);
                } catch (InvocationTargetException ite) {
                    future.completeExceptionally(ite.getCause() != null ? ite.getCause() : ite);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    @Override
    public void cancelAllActions() {
        actionPool.shutdownNow();
        try {
            actionPool.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- scheduling helpers ---------------------------------------------

    @Override
    public ScheduledFuture<?> runPeriodically(Runnable task, int hz) {
        if (hz <= 0) throw new IllegalArgumentException("hz must be > 0");
        long delayMs = Math.max(1, 1000L / hz);
        return schedulePeriodic(task, delayMs);
    }

    @Override
    public void runOnHardwareThread(Runnable task) {
        if (closed) {
            log.warn(name, "runOnHardwareThread on closed orchestrator ignored");
            return;
        }
        hardwareThread.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error(name, "hardware-thread task threw", t);
            }
        });
    }

    /** @return true if the calling thread is the orchestrator's hardware thread. */
    public boolean isHardwareThread() {
        return Thread.currentThread() == hardwareThreadThread;
    }

    /** Package-private: used by the binder to schedule @RunPeriodically(hardware=true). */
    public ScheduledFuture<?> scheduleHardwarePeriodic(Runnable task, long delayMs) {
        return hardwareThread.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error(name, "hardware-thread periodic threw", t);
            }
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule a {@link com.aaravlabs.synapse.ftc.BulkReader} callback to run
     * periodically on the hardware thread. Returns a handle the caller can use
     * to cancel.
     */
    public com.aaravlabs.synapse.ftc.HardwareActions.BulkReadHandle scheduleHardwareBulkRead(
            int hz, com.aaravlabs.synapse.ftc.BulkReader reader) {
        if (hz <= 0) throw new IllegalArgumentException("hz must be > 0");
        long delayMs = Math.max(1, 1000L / hz);
        com.aaravlabs.synapse.ftc.HardwareView view = hardwareView;
        ScheduledFuture<?> f = hardwareThread.scheduleWithFixedDelay(() -> {
            try {
                reader.read(view);
            } catch (Throwable t) {
                log.error(name, "bulk-read callback threw", t);
            }
        }, 0, delayMs, TimeUnit.MILLISECONDS);
        return new com.aaravlabs.synapse.ftc.HardwareActions.BulkReadHandle(f);
    }

    /**
     * @return the shared {@link com.aaravlabs.synapse.ftc.HardwareActions} facade for
     *         this orchestrator. The same instance is returned on every call, so it is
     *         safe to hold onto.
     */
    @Override
    public com.aaravlabs.synapse.ftc.HardwareActions hardware() {
        return hardwareActions;
    }

    /** Package-private: used by the binder to schedule @RunPeriodically methods. */
    public ScheduledFuture<?> schedulePeriodic(Runnable task, long delayMs) {
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error(name, "periodic task threw", t);
            }
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Package-private: tracked per-node so we can cancel on unbind. */
    public void trackNodeScheduled(Node node, ScheduledFuture<?> f) {
        nodeResources.computeIfAbsent(node, k -> new NodeResources()).scheduled.add(f);
    }

    /** Package-private: tracked per-node so we can unsubscribe on unbind. */
    public void trackNodeSubscription(Node node, Subscription sub) {
        nodeResources.computeIfAbsent(node, k -> new NodeResources()).subscriptions.add(sub);
    }

    /** Package-private: tracked per-node so we can unregister actions on unbind. */
    public void trackNodeAction(Node node, String actionName) {
        nodeResources.computeIfAbsent(node, k -> new NodeResources()).actions.add(actionName);
    }

    /** Package-private: clean up all tracked resources for a node on unregister. */
    public void cleanupNode(Node node) {
        NodeResources r = nodeResources.get(node);
        if (r == null) return;
        for (ScheduledFuture<?> f : r.scheduled) f.cancel(false);
        for (Subscription s : r.subscriptions) removeSubscription(s);
        for (String a : r.actions) actions.remove(a);
    }

    /** Package-private: register an action method. */
    public void registerAction(Node node, String actionName, Method method) {
        RunnableActionEntry prior = actions.putIfAbsent(
                actionName, new RunnableActionEntry(node, method));
        if (prior != null) {
            log.warn(name, "action '" + actionName + "' already registered; skipping on "
                    + node.getClass().getSimpleName());
        }
    }

    // ---- logging ---------------------------------------------------------

    @Override public void log(String message) { log(name, message); }
    @Override public void log(String tag, String message) { log.info(tag, message); }
    @Override public void warn(String message) { log.warn(name, message); }
    @Override public void error(String message) { log.error(name, message); }
    @Override public void error(String message, Throwable t) { log.error(name, message, t); }

    // ---- lifecycle -------------------------------------------------------

    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        for (String nodeName : new java.util.ArrayList<>(nodes.keySet())) {
            Node n = nodes.get(nodeName);
            unregisterNode(nodeName);
            if (n != null) {
                try { n.close(); } catch (Throwable t) { log.error(name, "node.close threw", t); }
            }
        }

        scheduler.shutdownNow();
        callbacks.shutdownNow();
        actionPool.shutdownNow();
        hardwareThread.shutdownNow();
        try {
            scheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
            callbacks.awaitTermination(100, TimeUnit.MILLISECONDS);
            actionPool.awaitTermination(100, TimeUnit.MILLISECONDS);
            hardwareThread.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info(name, "orchestrator closed");
    }

    // ----------------------------------------------------------------------
    // internal types
    // ----------------------------------------------------------------------

    private static final class SubscriberList {
        private volatile MessageHandler[] snapshot = new MessageHandler[0];
        private final Object lock = new Object();
        void add(MessageHandler h) {
            synchronized (lock) {
                MessageHandler[] cur = snapshot;
                MessageHandler[] next = new MessageHandler[cur.length + 1];
                System.arraycopy(cur, 0, next, 0, cur.length);
                next[cur.length] = h;
                snapshot = next;
            }
        }
        void remove(MessageHandler h) {
            synchronized (lock) {
                MessageHandler[] cur = snapshot;
                int idx = -1;
                for (int i = 0; i < cur.length; i++) if (cur[i] == h) { idx = i; break; }
                if (idx < 0) return;
                MessageHandler[] next = new MessageHandler[cur.length - 1];
                System.arraycopy(cur, 0, next, 0, idx);
                System.arraycopy(cur, idx + 1, next, idx, cur.length - idx - 1);
                snapshot = next;
            }
        }
        /** Atomically replace {@code old} with {@code next}. No-op if not found. */
        void replace(MessageHandler old, MessageHandler next) {
            synchronized (lock) {
                MessageHandler[] cur = snapshot;
                int idx = -1;
                for (int i = 0; i < cur.length; i++) if (cur[i] == old) { idx = i; break; }
                if (idx < 0) return;
                MessageHandler[] out = new MessageHandler[cur.length];
                System.arraycopy(cur, 0, out, 0, cur.length);
                out[idx] = next;
                snapshot = out;
            }
        }
        MessageHandler[] snapshot() { return snapshot; }
    }

    private static final class NodeResources {
        final java.util.List<ScheduledFuture<?>> scheduled = new java.util.ArrayList<>();
        final java.util.List<Subscription> subscriptions = new java.util.ArrayList<>();
        final java.util.List<String> actions = new java.util.ArrayList<>();
    }

    private static final class RunnableActionEntry {
        final Node node;
        final Method method;
        RunnableActionEntry(Node node, Method method) {
            this.node = node;
            this.method = method;
        }
    }
}
