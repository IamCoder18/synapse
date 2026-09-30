package com.aaravlabs.synapse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * The pub/sub bus. One per robot. Nodes talk to each other by {@link #publish publishing}
 * to and {@link #subscribe subscribing} to named, typed {@link Topic topics}.
 *
 * <p>Threads: the orchestrator runs four internal workers — a scheduler pool
 * (8 threads, for {@link com.aaravlabs.synapse.annotation.RunPeriodically}
 * loops), a callback pool (4–16 threads with a bounded queue, for
 * {@link com.aaravlabs.synapse.annotation.SubscribedTo} callbacks), an
 * unbounded action pool (for {@link com.aaravlabs.synapse.annotation.RunnableAction}
 * methods), and a <b>single dedicated hardware thread</b> for all
 * hardware-touching work. See the project README for the rationale.
 *
 * <p>Lifecycle: create via {@link #create(String)} (or {@code FtcOrchestrator.create()}
 * on the robot), register nodes with {@link #registerNode(String, Node)}, and call
 * {@link #close()} when the OpMode ends.
 */
public interface Orchestrator extends AutoCloseable {

    // ---- identity ---------------------------------------------------------

    /** Short human-readable name for this orchestrator, mostly for logging.
     *
     * @return the name this orchestrator was created with
     */
    String name();

    // ---- topics -----------------------------------------------------------

    /**
     * Look up an existing topic, or create it with the given type if it does not yet
     * exist. If the topic already exists with an incompatible type, this throws.
     * Primitive and wrapper types are treated as identical ({@code double.class} and
     * {@code Double.class} are the same topic type).
     *
     * @param name the topic name
     * @param type the message type
     * @param <T> the message type
     * @return the existing or newly created topic
     * @throws IllegalArgumentException if the topic exists with an incompatible type
     */
    <T> Topic<T> getOrCreateTopic(String name, Class<T> type);

    /**
     * Look up an existing topic by name, regardless of its type.
     *
     * @param name the topic name
     * @return the topic, or empty if none exists
     */
    Optional<Topic<?>> findTopic(String name);

    /**
     * Typed lookup of an existing topic.
     *
     * @param name the topic name
     * @param type the expected message type
     * @param <T> the message type
     * @return the topic if it exists and is type-compatible, otherwise empty
     */
    <T> Optional<Topic<T>> findTopic(String name, Class<T> type);

    // ---- publish ----------------------------------------------------------

    /**
     * Publish {@code value} to the named topic. Subscribers will be invoked
     * asynchronously on the callback pool. The latest value cache is updated
     * synchronously before any callbacks run, so {@link Topic#latestValue()} reflects
     * the new value immediately.
     *
     * <p>If no topic named {@code name} exists yet, one is created lazily from the
     * value's runtime type, so callers never have to pre-register topics. The publish
     * itself is non-blocking even from the hardware thread.
     *
     * @param name the topic name
     * @param value the value to publish (must not be null)
     * @param <T> the value type
     * @throws IllegalArgumentException if the value is {@code null}, or if the topic
     *         already exists and the value's type is incompatible with it.
     */
    <T> void publish(String name, T value);

    // ---- subscribe --------------------------------------------------------

    /**
     * Subscribe {@code handler} to the named topic. The handler is invoked
     * asynchronously on the callback pool for every published value. If the topic
     * does not exist yet it is created with the given type.
     *
     * @param name the topic name
     * @param type the message type
     * @param handler invoked for every published value
     * @param <T> the message type
     * @return a {@link Subscription} handle that can be used to unsubscribe
     */
    <T> Subscription subscribe(String name, Class<T> type, java.util.function.Consumer<? super T> handler);

    // ---- fetch the latest value ------------------------------------------

    /**
     * Convenience: get the most recently published value on the named topic, or empty
     * if nothing has been published yet. Equivalent to
     * {@code findTopic(name).flatMap(Topic::latestValue)} but type-safe.
     *
     * @param name the topic name
     * @param type the expected message type
     * @param <T> the message type
     * @return the latest value, or empty if the topic is unknown, empty, or
     *         type-incompatible
     */
    <T> Optional<T> getLatestValue(String name, Class<T> type);

    // ---- nodes -----------------------------------------------------------

    /**
     * Register a node with the orchestrator. This:
     * <ul>
     *   <li>Wires up all {@link com.aaravlabs.synapse.annotation.SubscribedTo} callbacks.</li>
     *   <li>Schedules all {@link com.aaravlabs.synapse.annotation.RunPeriodically} loops.</li>
     *   <li>Registers all {@link com.aaravlabs.synapse.annotation.RunnableAction} names.</li>
     * </ul>
     * The node is stored under {@code name}; calling {@link #registerNode} again with
     * the same name is a no-op that returns the previously-registered node (and logs a
     * warning), so a silently-skipped second registration is visible in Logcat.
     *
     * @param name unique name for the node
     * @param node the node to register
     * @return the registered node, or the previously-registered node if the name was
     *         already taken
     */
    Node registerNode(String name, Node node);

    /**
     * Stop and unregister a previously registered node. Cancels its scheduled loops,
     * unsubscribes its callbacks, and removes its actions.
     *
     * @param name the node name
     */
    void unregisterNode(String name);

    /**
     * Look up a registered node by name.
     *
     * @param name the node name
     * @return the node, or empty if none is registered under that name
     */
    Optional<Node> findNode(String name);

    // ---- actions ---------------------------------------------------------

    /**
     * Fire a registered {@link com.aaravlabs.synapse.annotation.RunnableAction} by name.
     * The returned future completes when the action's method returns normally, or
     * completes exceptionally if the method throws, if no action with that name is
     * registered, or if the orchestrator has been closed (including via
     * {@link #cancelAllActions()}, which shuts the action pool down permanently).
     *
     * @param actionName the name the action was registered with
     * @return a future that completes when the action finishes
     */
    CompletableFuture<Void> runAction(String actionName);

    /**
     * Cancel all currently-running actions. This shuts the action pool down
     * permanently — every subsequent {@link #runAction} call completes exceptionally.
     */
    void cancelAllActions();

    // ---- scheduling helpers ---------------------------------------------

    /**
     * Schedule {@code task} to run periodically on the scheduler pool at {@code hz}
     * hertz. Exposed for users who don't want the annotation form. Uses fixed-delay
     * scheduling, so the effective rate is {@code hz} or lower.
     *
     * @param task the task to run
     * @param hz target frequency in hertz (must be &gt; 0)
     * @return a future that can be used to cancel the loop
     */
    ScheduledFuture<?> runPeriodically(Runnable task, int hz);

    // ---- hardware thread -------------------------------------------------

    /**
     * Run {@code task} on the orchestrator's <b>dedicated single hardware
     * thread</b>. All {@code runOnHardwareThread} invocations and all
     * {@link com.aaravlabs.synapse.annotation.OnHardwareThread}-annotated callbacks
     * share this thread and execute strictly serially.
     *
     * <p>Use this when you need to touch FTC hardware from non-annotated code,
     * for example from inside an OpMode lifecycle hook.
     *
     * <p>If you call {@link #publish} from the runnable, the publish itself is
     * non-blocking (the bus enqueues the callback-pool task). The publish does
     * not block the hardware thread.
     *
     * @param task the task to run on the hardware thread
     */
    void runOnHardwareThread(Runnable task);

    /**
     * Returns a high-level facade over the hardware thread: {@code run} (async),
     * {@code call} (sync, blocking, returns a value), {@code callAsync}
     * (returns a {@link java.util.concurrent.CompletableFuture}), and
     * {@code bulkRead} (periodic read pattern that publishes values to topics).
     *
     * <p>Most users should use this facade rather than {@link #runOnHardwareThread}
     * directly — it gives you synchronous read access to hardware without breaking
     * thread safety.
     *
     * @return a {@link com.aaravlabs.synapse.ftc.HardwareActions} facade bound to
     *         this orchestrator
     */
    com.aaravlabs.synapse.ftc.HardwareActions hardware();

    // ---- logging ---------------------------------------------------------

    /**
     * Send an info-level log message tagged with this orchestrator's name.
     *
     * @param message the message
     */
    void log(String message);

    /**
     * Send an info-level log message with an explicit tag.
     *
     * @param tag short label for the source
     * @param message the message
     */
    void log(String tag, String message);

    /**
     * Log a warning tagged with this orchestrator's name.
     *
     * @param message the message
     */
    void warn(String message);

    /**
     * Log an error tagged with this orchestrator's name.
     *
     * @param message the message
     */
    void error(String message);

    /**
     * Log an error with a throwable.
     *
     * @param message the message
     * @param t the throwable to log
     */
    void error(String message, Throwable t);

    // ---- lifecycle -------------------------------------------------------

    /**
     * @return true if {@link #close()} has been called
     */
    boolean isClosed();
    /**
     * Stop all periodic loops, cancel all actions, unregister every node (calling its
     * {@link Node#close()}), and shut down every thread pool. Safe to call more than
     * once. {@code SafeOpMode.stop()} does this for you after {@code onSafeStop()}.
     */
    @Override
    void close();

    // ---- diagnostics -----------------------------------------------------

    /**
     * Registers a listener notified on every {@link #publish}, before subscriber
     * dispatch. See {@link PublishListener} for the threading contract.
     *
     * <p>Listeners run on the publishing thread and must not block. Registering
     * none leaves publish with a single volatile read, so this is cheap to leave
     * enabled permanently.
     *
     * <p>Listeners are notified before the topic's type is validated, so a
     * publish rejected with {@code IllegalArgumentException} is still reported.
     * That is deliberate: a type mismatch is a fault worth being able to
     * observe, and the caller still receives the exception.
     *
     * <p>The default implementation throws rather than silently doing nothing.
     * An implementor that cannot support listeners should fail at registration,
     * where the mistake is visible, rather than leave the caller believing a
     * recorder or a metric is attached when nothing is being captured. An
     * implementor that can must override both this and
     * {@link #removePublishListener(PublishListener)}: the default removal is a
     * silent no-op, so a wrapper that forwarded only registration would let a
     * caller detach a listener that is still attached.
     *
     * @param listener the listener; ignored if null
     * @throws UnsupportedOperationException if this implementation cannot
     *         support listeners
     */
    default void addPublishListener(PublishListener listener) {
        if (listener == null) {
            return;
        }
        throw new UnsupportedOperationException("publish listeners are not supported by this orchestrator");
    }

    /**
     * Removes a previously registered listener. Does nothing if it was not
     * registered, or if this implementation does not support listeners, so
     * cleanup is always safe to call.
     *
     * @param listener the listener to remove; ignored if null
     */
    default void removePublishListener(PublishListener listener) {
        // no-op
    }

    // ---- factories -------------------------------------------------------

    /**
     * Create a basic orchestrator with the given name and the stderr log sink. On the
     * robot, prefer {@code FtcOrchestrator.create()}, which routes logs to
     * {@code android.util.Log}.
     *
     * @param name short name used in logs and thread names
     * @return a new orchestrator
     */
    static Orchestrator create(String name) {
        return OrchestratorImpl.create(name, LogSink.STDERR);
    }

    /**
     * Create a basic orchestrator with the given name and a custom log sink.
     *
     * @param name short name used in logs and thread names
     * @param logSink where log lines are written
     * @return a new orchestrator
     */
    static Orchestrator create(String name, LogSink logSink) {
        return OrchestratorImpl.create(name, logSink);
    }
}
