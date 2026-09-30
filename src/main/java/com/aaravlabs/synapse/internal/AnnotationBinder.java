package com.aaravlabs.synapse.internal;

import com.aaravlabs.synapse.OrchestratorImpl;
import com.aaravlabs.synapse.Node;
import com.aaravlabs.synapse.Subscription;
import com.aaravlabs.synapse.annotation.OnHardwareThread;
import com.aaravlabs.synapse.annotation.RunnableAction;
import com.aaravlabs.synapse.annotation.RunPeriodically;
import com.aaravlabs.synapse.annotation.SubscribedTo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Scans a {@link Node}'s class for annotated methods and wires them up to the
 * orchestrator. Package-private; called by {@link OrchestratorImpl#registerNode}.
 */
public final class AnnotationBinder {

    private AnnotationBinder() {}

    public static void bindNode(OrchestratorImpl orchestrator, Node node, String nodeName) {
        for (Method m : allDeclaredMethods(node.getClass())) {
            wireRunPeriodically(orchestrator, node, m);
            wireSubscribedTos(orchestrator, node, m);
            wireRunnableAction(orchestrator, node, m);
        }
    }

    public static void unbindNode(OrchestratorImpl orchestrator, Node node) {
        orchestrator.cleanupNode(node);
    }

    // ----------------------------------------------------------------------
    // @RunPeriodically
    // ----------------------------------------------------------------------

    private static void wireRunPeriodically(OrchestratorImpl orchestrator, Node node, Method m) {
        RunPeriodically[] rps = m.getAnnotationsByType(RunPeriodically.class);
        if (rps.length == 0) return;

        if (m.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    "@RunPeriodically method must take no parameters: " + m);
        }
        if (Modifier.isStatic(m.getModifiers())) {
            throw new IllegalArgumentException(
                    "@RunPeriodically method must not be static: " + m);
        }

        m.setAccessible(true);
        for (RunPeriodically rp : rps) {
            if (rp.hz() <= 0) {
                orchestrator.warn("@RunPeriodically hz must be > 0 on " + m);
                continue;
            }
            long delayMs = Math.max(1, 1000L / rp.hz());

            ScheduledFuture<?> f;
            if (rp.hardware()) {
                f = orchestrator.scheduleHardwarePeriodic(() -> {
                    try {
                        m.invoke(node);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                        orchestrator.error("@RunPeriodically(hardware) method threw: " + m, cause);
                        throw new RuntimeException(cause);
                    } catch (Throwable t) {
                        orchestrator.error("@RunPeriodically(hardware) method threw: " + m, t);
                        throw new RuntimeException(t);
                    }
                }, delayMs);
            } else {
                f = orchestrator.schedulePeriodic(() -> {
                    try {
                        m.invoke(node);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                        orchestrator.error("@RunPeriodically method threw: " + m, cause);
                        throw new RuntimeException(cause);
                    } catch (Throwable t) {
                        orchestrator.error("@RunPeriodically method threw: " + m, t);
                        throw new RuntimeException(t);
                    }
                }, delayMs);
            }
            orchestrator.trackNodeScheduled(node, f);
        }
    }

    // ----------------------------------------------------------------------
    // @SubscribedTo  (with optional @OnHardwareThread)
    // ----------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void wireSubscribedTos(OrchestratorImpl orchestrator, Node node, Method m) {
        SubscribedTo[] subs = m.getAnnotationsByType(SubscribedTo.class);
        if (subs.length == 0) return;

        if (m.getParameterCount() > 1) {
            throw new IllegalArgumentException(
                    "@SubscribedTo method must take 0 or 1 parameter: " + m);
        }

        Class<?> paramType = m.getParameterCount() == 1 ? m.getParameterTypes()[0] : null;
        // Normalised once here rather than per delivered message. Equivalent to
        // `paramType.isPrimitive() ? boxed(paramType) : paramType`, because boxed()
        // returns non-primitives unchanged.
        final Class<?> effectiveParam = paramType != null ? boxed(paramType) : null;
        Class<?> topicType = effectiveParam != null ? effectiveParam : Object.class;

        m.setAccessible(true);

        boolean onHardware = m.isAnnotationPresent(OnHardwareThread.class);

        for (SubscribedTo sub : subs) {
            Subscription s = orchestrator.subscribeRaw(sub.topic(), topicType, msg -> {
                try {
                    if (effectiveParam == null) {
                        m.invoke(node);
                    } else if (effectiveParam.isInstance(msg)) {
                        m.invoke(node, msg);
                    }
                    // else: silently drop — message type didn't match the parameter.
                } catch (InvocationTargetException ite) {
                    orchestrator.error("@SubscribedTo handler threw: " + m,
                            ite.getCause() != null ? ite.getCause() : ite);
                } catch (Throwable t) {
                    orchestrator.error("@SubscribedTo handler threw: " + m, t);
                }
            });
            // If @OnHardwareThread is present, re-route this subscription's
            // handler to the hardware thread. We do this by wrapping the
            // subscription in a special wrapper that, when invoked, submits
            // work to hardwareThread instead of the callback pool.
            if (onHardware) {
                orchestrator.markSubscriptionAsHardwareThreaded(s);
            }
            orchestrator.trackNodeSubscription(node, s);
        }
    }

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

    // ----------------------------------------------------------------------
    // @RunnableAction
    // ----------------------------------------------------------------------

    private static void wireRunnableAction(OrchestratorImpl orchestrator, Node node, Method m) {
        RunnableAction ra = m.getAnnotation(RunnableAction.class);
        if (ra == null) return;
        if (m.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    "@RunnableAction method must take no parameters: " + m);
        }
        if (m.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    "@RunnableAction method must return void: " + m);
        }
        if (Modifier.isStatic(m.getModifiers())) {
            throw new IllegalArgumentException(
                    "@RunnableAction method must not be static: " + m);
        }
        m.setAccessible(true);
        orchestrator.registerAction(node, ra.value(), m);
        orchestrator.trackNodeAction(node, ra.value());
    }

    // ----------------------------------------------------------------------

    /** Walk the class hierarchy to find inherited annotated methods. */
    private static List<Method> allDeclaredMethods(Class<?> cls) {
        List<Method> out = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) out.add(m);
            c = c.getSuperclass();
        }
        return out;
    }
}
