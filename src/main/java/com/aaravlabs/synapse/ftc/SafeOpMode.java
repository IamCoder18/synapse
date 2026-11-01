package com.aaravlabs.synapse.ftc;

import com.aaravlabs.synapse.Orchestrator;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Base class for {@link OpMode} subclasses that use the Synapse pub/sub bus
 * with hardware-thread safety. Extends the FTC SDK's {@code OpMode} and
 * provides:
 *
 * <ul>
 *   <li>Orchestrator lifecycle: created on {@code init()}, shut down on
 *       {@code stop()} after {@link #onSafeStop()}.</li>
 *   <li>A {@link HardwareActions} instance ready to use as {@code hardware}.</li>
 *   <li>A {@link SafeHardwareMap} that wraps {@code hardwareMap} so device
 *       method calls route through the hardware thread.</li>
 *   <li>A {@code loop()} body that asserts you are not running on the
 *       hardware thread (fail-fast if a bug sneaks in) and ticks the
 *       {@link HardwareActions}.</li>
 * </ul>
 *
 * <p>To use, extend this class instead of {@code OpMode}:
 *
 * <pre>{@code
 * @TeleOp(name = "My OpMode")
 * public class MyOpMode extends SafeOpMode {
 *     @Override protected void onSafeInit() {
 *         // hardwareMap, hardware, orchestrator, safeMap are all initialized for you.
 *         SafeDevice<DcMotorEx> intake = safeMap.device(DcMotorEx.class, "intake");
 *         orchestrator.registerNode("intake", new IntakeNode(orchestrator, intake));
 *     }
 *
 *     @Override protected void onSafeLoop() {
 *         // Default loop body. You may also override loop() yourself if you
 *         // need full control, but then call super.loop() to get the
 *         // thread assertion + tick.
 *         orchestrator.publish("tick", System.nanoTime());
 *     }
 * }
 * }</pre>
 *
 * <p>Lifecycle mapping (SDK method → your hook):
 *
 * <table border="1">
 *   <caption>Lifecycle hooks</caption>
 *   <tr><th>SDK method</th><th>Your hook</th><th>Notes</th></tr>
 *   <tr><td>{@code init()}</td><td>{@link #onSafeInit()}</td><td>Required. Orchestrator is created first.</td></tr>
 *   <tr><td>{@code init_loop()}</td><td>{@link #onSafeInitLoop()}</td><td>Between init and start.</td></tr>
 *   <tr><td>{@code start()}</td><td>{@link #onSafeStart()}</td><td>When Start is pressed.</td></tr>
 *   <tr><td>{@code loop()}</td><td>{@link #onSafeLoop()}</td><td>Thread assertion + tick run first.</td></tr>
 *   <tr><td>{@code stop()}</td><td>{@link #onSafeStop()}</td><td>Runs before the orchestrator is closed.</td></tr>
 * </table>
 */
public abstract class SafeOpMode extends OpMode {

    /** The pub/sub bus, created in {@code init()} and closed in {@code stop()}. */
    protected Orchestrator orchestrator;

    /**
     * @deprecated use {@link #orchestrator}. Retained as a deprecated alias so
     *             existing FTC team code that still references {@code this.orch}
     *             keeps compiling with a deprecation warning.
     */
    @Deprecated
    protected Orchestrator orch;

    /** Facade over the dedicated hardware thread. See {@link HardwareActions}. */
    protected HardwareActions hardware;

    /** Wraps {@code hardwareMap} so device lookups return {@link SafeDevice}s. */
    protected SafeHardwareMap safeMap;

    /**
     * Whether {@link #attachGamepad(Object, String)} should actually attach a
     * {@link GamepadAdaptor} for this OpMode.
     *
     * <p>Defaulted in {@link #init()} based on the OpMode type:
     * <ul>
     *   <li>{@link TeleOp}-annotated OpModes: {@code true} — gamepads are published
     *       by default.</li>
     *   <li>Autonomous / other OpModes: {@code false} — gamepads are <b>not</b>
     *       published by default; set this to {@code true} in {@link #onSafeInit()}
     *       to opt in.</li>
     * </ul>
     *
     * <p>Subclasses may override this field in {@link #onSafeInit()} before
     * calling {@link #attachGamepad} to opt in (auto) or opt out (teleop).
     */
    protected boolean publishGamepads;

    @Override
    @SuppressWarnings("deprecation")
    public final void init() {
        orchestrator = FtcOrchestrator.create();
        orch = orchestrator;
        hardware = orchestrator.hardware();
        safeMap = new SafeHardwareMap(hardwareMap, hardware);
        // Gamepad publishing is on by default for TeleOp, off for Autonomous/other.
        publishGamepads = getClass().isAnnotationPresent(TeleOp.class);
        onSafeInit();
    }

    @Override
    public void init_loop() {
        onSafeInitLoop();
    }

    @Override
    public void loop() {
        hardware.assertNotHardwareThread();
        hardware.tick();
        onSafeLoop();
    }

    @Override
    public void start() {
        onSafeStart();
    }

    @Override
    public void stop() {
        onSafeStop();
        orchestrator.close();
    }

    // ---- user-overridable hooks ----------------------------------------

    /** Required: build your nodes and register them here. */
    protected abstract void onSafeInit();

    /** Called every iteration after start(); defaults to no-op. */
    protected void onSafeLoop() {}

    /** Called repeatedly between init and start; defaults to no-op. */
    protected void onSafeInitLoop() {}

    /** Called when the OpMode starts. Defaults to no-op. */
    protected void onSafeStart() {}

    /** Called when the OpMode stops, before orchestrator is closed. Defaults to no-op. */
    protected void onSafeStop() {}

    // ---- gamepad attachment ---------------------------------------------

    /**
     * Attach a {@link GamepadAdaptor} for {@code gamepad} only if
     * {@link #publishGamepads} is true. Returns the registered node name
     * (e.g. {@code "GamepadAdaptor:g1"}) or {@code null} when gamepad
     * publishing is disabled for this OpMode.
     *
     * <p>Use this instead of calling {@link GamepadAdaptor#attach} directly so
     * the auto/teleop default is respected. Example:
     *
     * <pre>{@code
     * @Autonomous(name = "My Auto")
     * public class MyAuto extends SafeOpMode {
     *     @Override protected void onSafeInit() {
     *         // publishGamepads defaults to false for Autonomous.
     *         attachGamepad(gamepad1, "g1");   // no-op: gamepads not published
     *         publishGamepads = true;          // opt in
     *         attachGamepad(gamepad1, "g1");   // now attaches
     *     }
     * }
     * }</pre>
     */
    protected String attachGamepad(Object gamepad, String parentTopic) {
        if (!publishGamepads) {
            if (orchestrator != null) {
                orchestrator.log("GamepadAdaptor: skipping '" + parentTopic + "' — gamepad publishing disabled");
            }
            return null;
        }
        return GamepadAdaptor.attach(orchestrator, gamepad, parentTopic);
    }
}
