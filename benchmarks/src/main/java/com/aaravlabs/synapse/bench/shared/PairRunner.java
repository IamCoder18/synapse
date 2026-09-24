package com.aaravlabs.synapse.bench.shared;

/**
 * One measured scenario × style run. The harness owns the measurement window;
 * implementations only build the robot program in their style and start/stop it.
 */
public interface PairRunner extends AutoCloseable {

    /** Start the style's loops/threads/nodes. */
    void start();

    /** Stop the style's loops/threads/nodes. */
    void stop();

    @Override
    default void close() {
        stop();
    }
}
