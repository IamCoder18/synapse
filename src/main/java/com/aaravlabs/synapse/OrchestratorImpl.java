package com.aaravlabs.synapse;

import com.aaravlabs.synapse.internal.AnnotationBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

        // Both facades are immutable one-field views over this orchestrator, so build
        // them once here. `orchestrator.hardware()` is called per message by hot
        // subscribers and used to allocate a fresh facade every time; the bulk-read
        // view was likewise allocated per registration.
        this.hardwareActions = new com.aaravlabs.synapse.ftc.HardwareActions(this);
        this.hardwareView = new com.aaravlabs.synapse.ftc.HardwareView(this);
    }

    private final com.aaravlabs.synapse.ftc.HardwareActions hardwareActions;
    private final com.aaravlabs.synapse.ftc.HardwareView hardwareView;

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

        Class<?> normalized = boxed(type);

        Topic<?> existing = topics.get(topicName);
        if (existing != null) {
            if (!boxed(existing.type()).isAssignableFrom(normalized)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + existing.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            return (Topic<T>) existing;
        }

        Topic<T> created = new Topic<>(topicName, type);
        Topic<?> prior = topics.putIfAbsent(topicName, created);
        if (prior != null) {
            if (!boxed(prior.type()).isAssignableFrom(normalized)) {
                throw new IllegalArgumentException(
                        "Topic '" + topicName + "' already exists with type "
                                + prior.type().getName() + ", cannot re-create as "
                                + type.getName());
            }
            return (Topic<T>) prior;
        }
        log.info(name, "created topic " + created);
        return created;
    }

    /** Treat primitive {@code double.class} and wrapper {@code Double.class} as the same type. */
    private static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == double.class)  return Double.class;
        if (c == float.class)   return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == char.class)    return Character.class;
        return c;
    }

    @Override
    public Optional<Topic<?>> findTopic(String topicName) {
        return Optional.ofNullable(topics.get(topicName));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<Topic<T>> findTopic(String topicName, Class<T> type) {
        Topic<?> t = topics.get(topicName);
        if (t == null || !boxed(t.type()).isAssignableFrom(boxed(type))) return Optional.empty();
        return Optional.of((Topic<T>) t);
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

        // Lazily create the topic from the value's runtime type. This matches
        // Heron's behavior: publishers don't have to pre-register topics.
        Class<?> valueType = value.getClass();
        Topic<?> topic = topics.get(topicName);
        if (topic == null) {
            topic = getOrCreateTopic(topicName, valueType);
        } else if (!boxed(topic.type()).isAssignableFrom(boxed(valueType))) {
            throw new IllegalArgumentException(
                    "Topic '" + topicName + "' is typed " + topic.type().getName()
                            + " but publish got " + valueType.getName());
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
        MessageHandler wrapped = msg -> handler.accept(type.cast(msg));
        SubscriberList list = subscribers.computeIfAbsent(topic, k -> new SubscriberList());
        list.add(wrapped);
        return new Subscription(topic, wrapped, this);
    }

    /** Raw subscribe used by the annotation binder where the parameter type is reflective. */
    public Subscription subscribeRaw(String topicName, Class<?> type,
                                     java.util.function.Consumer<Object> handler) {
        Topic<?> topic = getOrCreateTopic(topicName, type);
        MessageHandler wrapped = handler::accept;
        SubscriberList list = subscribers.computeIfAbsent(topic, k -> new SubscriberList());
        list.add(wrapped);
        return new Subscription(topic, wrapped, this);
    }

    void removeSubscription(Subscription sub) {
        Topic<?> t = sub.topic();
        SubscriberList list = subscribers.get(t);
        if (list != null) list.remove(sub.handler());
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
        // Replace in the list. Subscription object's handler() returns the
        // original so removeSubscription still works correctly.
        list.replace(original, wrapped);
        // Stash the wrapped handler so subscribers still see the original via
        // sub.handler() (which is unchanged), but the dispatch uses wrapped.
        hardwareRerouted.put(sub, wrapped);
    }

    private final java.util.Map<Subscription, MessageHandler> hardwareRerouted = new ConcurrentHashMap<>();

    // ---- fetch latest ----------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getLatestValue(String topicName, Class<T> type) {
        Topic<?> t = topics.get(topicName);
        if (t == null || !boxed(t.type()).isAssignableFrom(boxed(type))) return Optional.empty();
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

    @Override
    /**
     * @return the shared {@link com.aaravlabs.synapse.ftc.HardwareActions} facade for
     *         this orchestrator. The same instance is returned on every call, so it is
     *         safe to hold onto. (It was a fresh object per call before.)
     */
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
