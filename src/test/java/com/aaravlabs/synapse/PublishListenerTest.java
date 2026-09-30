package com.aaravlabs.synapse;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link PublishListener} extension point.
 *
 * <p>Two properties matter most and are easy to get wrong. A listener that
 * throws must not break the bus -- that is the difference between diagnostics
 * and an outage during a match. And the zero-listener path must stay cheap,
 * since this hook is on the hot path of every publish on the robot.
 */
class PublishListenerTest {

    private static final String CLASS_SUFFIX = ".class";

    @Test
    void listenerSeesEveryPublish() {
        Orchestrator orch = Orchestrator.create("listener");
        try {
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            orch.addPublishListener((topic, value, nanos) -> seen.add(topic + '=' + value));

            orch.publish("a", 1.0);
            orch.publish("b", "two");
            orch.publish("c", true);

            assertEquals(List.of("a=1.0", "b=two", "c=true"), seen);
        } finally {
            orch.close();
        }
    }

    @Test
    void listenerRunsBeforeSubscribersObserveTheValue() throws Exception {
        Orchestrator orch = Orchestrator.create("order");
        try {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            orch.addPublishListener((t, v, n) -> order.add("listener"));
            orch.subscribe("t", Double.class, v -> order.add("subscriber"));

            orch.publish("t", 1.0);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (order.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(List.of("listener", "subscriber"), order,
                    "the listener must fire before dispatch");
        } finally {
            orch.close();
        }
    }

    @Test
    void everyListenerGetsTheSameTimestamp() throws Exception {
        Orchestrator orch = Orchestrator.create("timestamps");
        try {
            // "Did it run" is recorded as a flag, never inferred from the
            // timestamp. System.nanoTime() has an arbitrary origin and its
            // values may be zero or negative, so `nanos > 0` can fail on a
            // perfectly good clock -- and when it does, it blames the clock for
            // a listener that ran perfectly well.
            AtomicBoolean slowRan = new AtomicBoolean();
            AtomicBoolean fastRan = new AtomicBoolean();
            AtomicLong first = new AtomicLong();
            AtomicLong second = new AtomicLong();

            orch.addPublishListener((t, v, nanos) -> {
                slowRan.set(true);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                first.set(nanos);
            });
            orch.addPublishListener((t, v, nanos) -> {
                fastRan.set(true);
                second.set(nanos);
            });

            orch.publish("t", 1.0);

            assertTrue(slowRan.get(), "the first listener should have run");
            assertTrue(fastRan.get(), "the second listener should have run");
            assertEquals(first.get(), second.get(),
                    "a slow first listener must not shift the timestamp later ones see");
        } finally {
            orch.close();
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakThePublish() throws Exception {
        Orchestrator orch = Orchestrator.create("throwing");
        try {
            // Plain ArrayList: the listener runs inline on the publishing
            // thread, which here is the test thread, so nothing else touches
            // this list. The subscriber below is dispatched to the callback
            // pool, so its counter has to be atomic.
            List<String> seen = new ArrayList<>();
            AtomicInteger subscriberCalls = new AtomicInteger();

            orch.addPublishListener((t, v, n) -> {
                throw new RuntimeException("listener is broken");
            });
            orch.addPublishListener((t, v, n) -> seen.add(t));
            orch.subscribe("t", Double.class, v -> subscriberCalls.incrementAndGet());

            orch.publish("t", 1.0);

            assertEquals(List.of("t"), seen, "listeners after the throwing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (subscriberCalls.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, subscriberCalls.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void removeStopsNotifications() {
        Orchestrator orch = Orchestrator.create("remove");
        try {
            AtomicInteger calls = new AtomicInteger();
            PublishListener listener = (t, v, n) -> calls.incrementAndGet();

            orch.addPublishListener(listener);
            orch.publish("a", 1.0);
            assertEquals(1, calls.get());

            orch.removePublishListener(listener);
            orch.publish("b", 2.0);
            assertEquals(1, calls.get(), "no further notifications after removal");
        } finally {
            orch.close();
        }
    }

    @Test
    void removingSomethingNeverRegisteredIsHarmless() {
        Orchestrator orch = Orchestrator.create("remove-unknown");
        try {
            orch.removePublishListener((t, v, n) -> { });
            orch.removePublishListener(null);
        } finally {
            orch.close();
        }
    }

    @Test
    void nullListenersAreIgnored() {
        Orchestrator orch = Orchestrator.create("null-listener");
        try {
            orch.addPublishListener(null);
            orch.publish("t", 1.0);
        } finally {
            orch.close();
        }
    }

    @Test
    void listenersAreCalledInRegistrationOrder() {
        Orchestrator orch = Orchestrator.create("order-registration");
        try {
            // Plain ArrayList: registration order is only meaningful because
            // all three listeners run inline on this thread.
            List<String> order = new ArrayList<>();
            orch.addPublishListener((t, v, n) -> order.add("first"));
            orch.addPublishListener((t, v, n) -> order.add("second"));
            orch.addPublishListener((t, v, n) -> order.add("third"));

            orch.publish("t", 1.0);

            assertEquals(List.of("first", "second", "third"), order);
        } finally {
            orch.close();
        }
    }

    @Test
    void timestampIsPlausiblyCurrent() {
        Orchestrator orch = Orchestrator.create("now");
        try {
            long[] seen = new long[1];
            orch.addPublishListener((t, v, nanos) -> seen[0] = nanos);

            long before = System.nanoTime();
            orch.publish("t", 1.0);
            long after = System.nanoTime();

            // nanoTime values are only meaningful as differences: the origin
            // is arbitrary and the sequence wraps. Comparing raw values with
            // >= / <= happens to work while the counter is small and
            // positive, which is exactly why the bug is easy to miss.
            assertTrue(seen[0] - before >= 0 && after - seen[0] >= 0,
                    "listener timestamp " + seen[0] + " should fall within the publish window");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingANonFatalErrorStillDoesNotBreakThePublish() throws Exception {
        // The boundary is not Exception. On a robot the realistic way a
        // diagnostics module breaks is a failed assertion or a module that no
        // longer links against the robot build -- AssertionError,
        // NoClassDefFoundError, and friends are all Errors, and all of them
        // must be contained just like a RuntimeException is. This is what makes
        // narrowing the catch to Exception the wrong fix.
        Orchestrator orch = Orchestrator.create("throwing-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            orch.addPublishListener((t, v, n) -> {
                throw new NoClassDefFoundError("com/example/recorder/Recorder");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            AtomicInteger delivered = new AtomicInteger();
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "an Error from a listener must not break the publish");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingOutOfMemoryErrorIsNotContained() {
        // The one documented exception to "instrumentation can never break the
        // bus", and it is exactly one class. Heap exhaustion is the only
        // condition where there is no publish worth protecting, because the
        // recovery path needs memory too: log.error builds a message and fills
        // in a stack trace, so swallowing it would fail again in a worse
        // place. Carrying on to the next listener would only allocate more.
        // Pinned here so the carve-out stays deliberate and cannot widen by
        // accident.
        Orchestrator orch = Orchestrator.create("throwing-vm-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw new OutOfMemoryError("listener exhausted the heap");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertThrows(OutOfMemoryError.class, () -> orch.publish("t", 1.0),
                    "a JVM-fatal error from a listener must propagate, not be swallowed");
            assertFalse(afterRan.get(),
                    "the remaining listeners must not run once the JVM is out of memory");
            assertEquals(0, delivered.get(),
                    "subscriber dispatch must not run once the JVM is out of memory");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingStackOverflowErrorIsContained() throws Exception {
        // Reverting the carve-out to catch (VirtualMachineError) -- which is
        // what the code did before this was narrowed -- fails here: the
        // assertDoesNotThrow below would see the StackOverflowError escape.
        //
        // The reviewer who reported the broad catch is right about this one.
        // StackOverflowError is a VirtualMachineError, but it is routinely
        // recoverable and very common: a listener with an unbounded recursion
        // blows the stack, the JVM unwinds that listener's frames, and the
        // stack is whole again by the time the publish loop sees the error.
        // The heap was never touched. Failing the publish -- and with it every
        // subscriber on the topic -- is the hook causing the outage it exists
        // to diagnose.
        Orchestrator orch = Orchestrator.create("throwing-soe", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw new StackOverflowError("listener recursed without a bound");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "a StackOverflowError from a listener must be contained: the stack unwound,"
                            + " the heap was never exhausted, and the publish was still viable");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");
            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingInternalErrorIsContained() throws Exception {
        // Deliberate judgement call, not an oversight. InternalError is a
        // VirtualMachineError, but on the running JDK it has no subclass at
        // all and no documented recoverable producer, so this code cannot tell
        // a benign one from a fatal one -- and it does not guess. What it can
        // tell is that the bus is not compromised: the fault is inside one
        // listener's frame, and neither the other listeners nor the
        // subscribers depend on it. Contained.
        //
        // Reverting to catch (VirtualMachineError) fails this test.
        Orchestrator orch = Orchestrator.create("throwing-internal-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw new InternalError("VM internal invariant broken");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "an InternalError from one listener must not fail the publish for everyone else");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");
            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingUnknownErrorIsContained() throws Exception {
        // The fourth and last direct VirtualMachineError subclass in the JDK,
        // and the one most easily missed when narrowing: it does not extend
        // InternalError, so a carve-out written as "everything except
        // StackOverflowError and InternalError" would still rethrow this.
        // Containment here is the whole of the contract -- see the class
        // javadoc on PublishListener.
        //
        // Reverting to catch (VirtualMachineError) fails this test.
        Orchestrator orch = Orchestrator.create("throwing-unknown-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw new UnknownError("unrecognised VM exception");
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "an UnknownError from one listener must not fail the publish for everyone else");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");
            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerThrowingACorruptArchiveZipErrorIsContained() throws Exception {
        // The one member of the containment family that no earlier version of
        // this test knew about, and the reason this file now reads the JDK's
        // hierarchy instead of a list written beside it.
        //
        // java.util.zip.ZipError is java.base's only InternalError subclass.
        // The previous guard asserted InternalError had no JDK subclass at all,
        // and that assertion was green on every JDK this project runs on only
        // because ZipError was missing from the twenty-entry list it filtered.
        // So a VirtualMachineError a diagnostics module can genuinely raise --
        // one reading a corrupt artifact, cache or jar -- sat outside the
        // coverage entirely.
        //
        // The containment decision is unchanged and is what this pins: ZipError
        // is an InternalError, not an OutOfMemoryError, so the rethrow in
        // OrchestratorImpl.publish never sees it and catch (Throwable) contains
        // it. A corrupt archive is a fault to contain, not one to fail every
        // subscriber on the topic over.
        //
        // Built reflectively because ZipError is marked for removal on JDK 21
        // and later, so a direct reference would deprecation-warn on exactly
        // the JDKs a future maintainer would run this on.
        Object zipError = newZipError();
        Orchestrator orch = Orchestrator.create("throwing-zip-error", LogSink.SILENT);
        try {
            AtomicBoolean afterRan = new AtomicBoolean();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((t, v, n) -> {
                throw (Error) zipError;
            });
            orch.addPublishListener((t, v, n) -> afterRan.set(true));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "ZipError is an InternalError and not an OutOfMemoryError, so the one rethrow in"
                            + " publish must not see it");
            assertTrue(afterRan.get(), "listeners after the failing one must still run");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");
            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscribers must still be dispatched");
        } finally {
            orch.close();
        }
    }

    private static Object newZipError() {
        try {
            return Class.forName("java.util.zip.ZipError")
                    .getConstructor(String.class)
                    .newInstance("listener hit a corrupt archive");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("java.util.zip.ZipError must exist and be constructible;"
                    + " the containment reasoning below depends on it being a VirtualMachineError"
                    + " subclass", e);
        }
    }

    @Test
    void theContainedVirtualMachineErrorsAreAllOfThem() throws Exception {
        // Not a behaviour test: a guard on the reasoning behind the carve-out.
        // publish rethrows OutOfMemoryError and contains every other
        // VirtualMachineError, so the set is "all VirtualMachineError except
        // OutOfMemoryError" -- which means the JDK's own hierarchy is part of
        // the contract. If a future JDK adds or reshapes a subclass, this
        // fails and the carve-out has to be re-examined rather than assumed.
        //
        // The hierarchy is read out of the running JDK by javaBaseClasses()
        // below, never from a list written in this file. That distinction is
        // the whole test. The previous version filtered a hardcoded
        // twenty-entry list of error classes and compared the result against a
        // hand-written restatement of the same twenty entries, so it could only
        // ever confirm itself: it was green on every JDK while
        // java.util.zip.ZipError -- java.base's one real InternalError subclass
        // -- went unexamined, and while a JDK that added a fifth
        // VirtualMachineError subclass would have left it green too. Its own
        // comment claimed the opposite.
        List<Class<?>> javaBase = javaBaseClasses();

        // Self-check on the scan, so that a scan which silently found nothing
        // cannot turn every emptiness assertion below into a vacuous pass. The
        // old version had no equivalent, which is how a wrong "there is no
        // subclass" could stay green.
        assertEquals(List.of(Error.class, Exception.class),
                directSubclassesIn(javaBase, Throwable.class),
                "the scan must really find the Throwable roots, or every emptiness assertion below"
                        + " is vacuous and this guard guards nothing");

        assertEquals(
                List.of(InternalError.class, OutOfMemoryError.class,
                        StackOverflowError.class, UnknownError.class),
                directSubclassesIn(javaBase, VirtualMachineError.class),
                "the direct VirtualMachineError subclasses in this JDK; if this changed, re-review"
                        + " which of them are recoverable before leaving them contained");

        // An empty subclass list is what makes each of these the whole family
        // rather than one member of it, and that is the fact the containment
        // argument rests on. If a future JDK grew a StackOverflowError
        // subclass, say, the behaviour tests above would still pin the one
        // class they throw while a contained sibling escaped as if it were the
        // pinned one -- and OutOfMemoryError is worse still, because a
        // subclass of it would propagate straight out of publish.
        assertEquals(List.of(), directSubclassesIn(javaBase, OutOfMemoryError.class),
                "OutOfMemoryError has no subclass in this JDK, so the carve-out is exactly one class;"
                        + " if this changed, a new subclass would propagate and take the bus down");
        assertEquals(List.of(), directSubclassesIn(javaBase, StackOverflowError.class),
                "StackOverflowError has no subclass either, so containment covers the whole family");
        assertEquals(List.of(), directSubclassesIn(javaBase, UnknownError.class),
                "UnknownError has no JDK subclass either");

        // The assertion the hand-written list got wrong. ZipError is
        // java.base's only InternalError subclass, so the older claim that
        // InternalError has no JDK subclass has never been true on any JDK this
        // project runs on. It changes no behaviour -- ZipError is an
        // InternalError and not an OutOfMemoryError, so it is contained, and
        // aListenerThrowingACorruptArchiveZipErrorIsContained pins that -- but
        // a carve-out justified by "no subclass exists" is not a carve-out
        // anybody has examined.
        Class<?> zipError = Class.forName("java.util.zip.ZipError", false, null);
        assertEquals(List.of(zipError), directSubclassesIn(javaBase, InternalError.class),
                "java.base's only InternalError subclass; a change here means the containment"
                        + " reasoning in PublishListener has to be re-derived from the new shape");
        assertFalse(OutOfMemoryError.class.isAssignableFrom(zipError),
                "ZipError is an InternalError, not an OutOfMemoryError, so it is contained and never"
                        + " reaches the one rethrow");

        // The class-loading errors a robot build realistically throws do not
        // descend from InternalError at all. They are LinkageErrors, so they
        // were already contained by catch (Throwable) even under the broad
        // carve-out this test exists to keep narrow -- none of them was ever in
        // scope, and they are covered by
        // aListenerThrowingANonFatalErrorStillDoesNotBreakThePublish.
        // Verified on the running JDK by class name, because ThreadDeath and
        // ZipError are both marked for removal on recent JDKs.
        List<Class<?>> linkageSubclasses = directSubclassesIn(javaBase, LinkageError.class);
        for (String classLoadingError : List.of("java.lang.ClassFormatError",
                "java.lang.NoClassDefFoundError", "java.lang.VerifyError",
                "java.lang.IncompatibleClassChangeError")) {
            Class<?> candidate = Class.forName(classLoadingError, false, null);
            assertTrue(linkageSubclasses.contains(candidate),
                    classLoadingError + " must descend from LinkageError in this JDK");
            assertFalse(InternalError.class.isAssignableFrom(candidate),
                    classLoadingError + " is a LinkageError, not an InternalError, so it was never"
                            + " in scope of the old VirtualMachineError carve-out");
        }

        Class<?> threadDeath = Class.forName("java.lang.ThreadDeath", false, null);
        assertFalse(VirtualMachineError.class.isAssignableFrom(threadDeath),
                "ThreadDeath is a plain Error, not a VirtualMachineError; it was never in the"
                        + " carve-out and stays contained");
    }

    /**
     * Every class declared by {@code java.base} on the running JDK, read out of
     * the module image rather than asserted by this project.
     *
     * <p>Two JDK APIs that look like they would do this, and do not:
     * {@code Class.getDeclaredClasses()} returns an empty array for these
     * JDK-internal error classes from JDK 17 on, because they are not nested
     * classes of anything, and {@link Module} exposes no way to enumerate the
     * classes it contains. What does work is the {@code jrt:} filesystem, the
     * JVM's own view of the module image.
     *
     * <p>The package list comes from the module descriptor, and the class list
     * from those packages' directories, so both halves are JDK-derived and a
     * new package or a new class appears here without this file changing. It
     * walks {@code java.base} only, which is where the whole standard error
     * hierarchy lives -- {@code Error} and {@code Exception} are its only two
     * roots -- and the sanity assertion in the test above checks that this
     * scan really does find them.
     */
    private static List<Class<?>> javaBaseClasses() throws IOException {
        FileSystem jrt = jrtFileSystem();
        Path moduleRoot = jrt.getPath("/modules/java.base");
        Set<String> packages = ModuleLayer.boot().findModule("java.base")
                .map(module -> module.getDescriptor().packages())
                .orElseThrow(() -> new AssertionError("java.base is not in the boot layer of this JVM,"
                        + " so its hierarchy cannot be read. A guard that cannot see the hierarchy"
                        + " must fail loudly, not pass"));

        List<Class<?>> classes = new ArrayList<>();
        for (String packageName : packages) {
            Path packageDir = moduleRoot.resolve(packageName.replace('.', '/'));
            // A module descriptor may declare a package that holds no classes
            // at all. That is not a hierarchy change, so it must not be
            // allowed to fail this test.
            if (!Files.isDirectory(packageDir)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(packageDir)) {
                for (Path entry : entries) {
                    String fileName = entry.getFileName().toString();
                    // Nested classes are skipped: the hierarchy is declared by
                    // top-level types, and a member class of ZipError is not a
                    // new node in the error tree.
                    if (!fileName.endsWith(CLASS_SUFFIX) || fileName.indexOf('$') >= 0) {
                        continue;
                    }
                    String className = packageName + '.'
                            + fileName.substring(0, fileName.length() - CLASS_SUFFIX.length());
                    try {
                        // initialize=false, so reading the JDK's declarations
                        // cannot run a static initialiser and fail. A null
                        // loader uses the boot loader, which is where java.base
                        // lives.
                        classes.add(Class.forName(className, false, null));
                    } catch (ClassNotFoundException | LinkageError notAClass) {
                        // An image entry that is not loadable as a class -- a
                        // resource named like one, or a class this JDK cannot
                        // link -- is not part of the hierarchy.
                    }
                }
            }
        }
        return classes;
    }

    /**
     * The jrt filesystem, opening it if this JVM has not installed the
     * provider yet. Most JDKs have it from startup, but not all launchers do,
     * and a test must not depend on that.
     */
    private static FileSystem jrtFileSystem() throws IOException {
        URI jrt = URI.create("jrt:/");
        try {
            return FileSystems.getFileSystem(jrt);
        } catch (RuntimeException notInstalledYet) {
            try {
                return FileSystems.newFileSystem(jrt, Collections.emptyMap());
            } catch (FileSystemAlreadyExistsException raced) {
                return FileSystems.getFileSystem(jrt);
            }
        }
    }

    /**
     * The classes in {@code candidates} whose immediate superclass is exactly
     * {@code type}, name-sorted.
     *
     * <p>Exact superclass rather than assignable: this reads the shape of the
     * JDK's hierarchy, which is what the containment argument is about. The
     * old helper of this name returned every subtype of {@code type} drawn
     * from twenty classes written beside it, so it was a filter over a
     * constant rather than a reading of anything.
     */
    private static List<Class<?>> directSubclassesIn(List<Class<?>> candidates, Class<?> type) {
        return candidates.stream()
                .filter(c -> c.getSuperclass() == type)
                .sorted(Comparator.comparing(Class::getName))
                .collect(Collectors.toList());
    }

    @Test
    void publishingWithNoListenerStillWorks() {
        Orchestrator orch = Orchestrator.create("no-listener");
        try {
            orch.publish("t", 1.0);
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow());
        } finally {
            orch.close();
        }
    }

    @Test
    void safeFromManyThreads() throws Exception {
        Orchestrator orch = Orchestrator.create("concurrent");
        try {
            AtomicInteger notifications = new AtomicInteger();
            List<String> topics = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                topics.add("topic/" + i);
            }
            // Exactly-once is the invariant worth pinning: too few means a
            // publish was lost from the notification path, too many means the
            // listener list was iterated more than once per publish.
            orch.addPublishListener((t, v, n) -> notifications.incrementAndGet());

            int threads = 6;
            int perThread = 500;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            orch.publish(topics.get((id + i) % topics.size()), (double) i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers should finish");

            assertEquals(threads * perThread, notifications.get(),
                    "every publish must be notified exactly once, no more and no fewer");
        } finally {
            orch.close();
        }
    }

    @Test
    void listenerSeesTheValueNotJustTheTopic() {
        Orchestrator orch = Orchestrator.create("value");
        try {
            List<Object> values = new ArrayList<>();
            orch.addPublishListener((t, v, n) -> values.add(v));

            String payload = "not interned";
            orch.publish("t", payload);

            assertEquals(1, values.size());
            assertSame(payload, values.get(0), "the listener receives the published reference");
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerUnregisteringAnotherMidPublishDoesNotBreakTheBus() throws Exception {
        // Regression test, single-threaded and deterministic. The listener
        // loop originally iterated with size() and get(i), each of which reads
        // the current CopyOnWriteArrayList array independently. A listener
        // that unregistered a later one mid-publish left the cached size()
        // stale, and get(i) then threw IndexOutOfBoundsException. That call is
        // inside the try/catch, so the publish itself never failed -- but the
        // loop aborted there, every remaining listener was silently skipped
        // for that publish, and the log reported a listener as having thrown
        // when the list was merely shorter than expected. The loop now walks
        // one stable snapshot, so a publish notifies exactly the listeners
        // registered when it started.
        //
        // Registering `first` before `second` is what makes it deterministic:
        // `first` runs at index 0 and drops a listener that has not been
        // notified yet, so the old loop's cached size() of 2 was already stale
        // by the time it indexed. No threads, no timing, no retry.
        Orchestrator orch = Orchestrator.create("remove-during-publish", LogSink.SILENT);
        try {
            List<String> seen = new ArrayList<>();
            PublishListener second = (t, v, n) -> seen.add("second");
            PublishListener first = (t, v, n) -> {
                seen.add("first");
                orch.removePublishListener(second);
            };
            orch.addPublishListener(first);
            orch.addPublishListener(second);

            AtomicInteger delivered = new AtomicInteger();
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertDoesNotThrow(() -> orch.publish("t", 1.0),
                    "mutating the listener list from inside a listener must not fail the publish");

            assertEquals(List.of("first", "second"), seen,
                    "every listener registered when the publish started must be notified, even one"
                    + " that a preceding listener unregistered mid-publish");
            assertEquals(1.0, orch.getLatestValue("t", Double.class).orElseThrow(),
                    "the publish itself must still complete");

            awaitCount(delivered, 1);
            assertEquals(1, delivered.get(), "subscriber dispatch must not be skipped");
        } finally {
            orch.close();
        }
    }

    @Test
    void concurrentListenerChurnNeverSurfacesAsAPublishFailure() throws Exception {
        // Bounded companion to the test above: mutating the listener list from
        // several threads at once must not surface as a failed publish, and
        // must not degrade into quadratic work.
        //
        // The listener set is fixed at four and registered once. An earlier
        // version of this test added a fresh throwing listener on every
        // iteration and never removed it, so the list grew without bound and
        // every publish iterated -- and stack-traced -- an ever longer prefix:
        // 8.2M logged exceptions and a 3.9G test result in 120 seconds, which
        // timed the test out before it reached a single assertion. Registering
        // two throwers makes the bound observable: their invocation count has
        // to stay linear in the number of publishes.
        //
        // Two things here make the bound real rather than asserted. First, the
        // remove/add pair runs under a lock, because
        // CopyOnWriteArrayList.remove(Object) drops only the *first* equal
        // element: two threads interleaving remove/remove/add/add used to leave
        // two copies of `churned` registered, and the list grew by one per
        // collision. That was a bug in the harness, not in the orchestrator,
        // and the existing assertions could not see it -- the extra
        // registrations were no-ops, so neither the notification count nor the
        // thrower count moved. Only the listener count itself reveals it,
        // which is why publishListenerCount() exists.
        //
        // Second, the count is asserted. Checking only at the end is enough:
        // a duplicate is never removed again (the next remove takes the
        // remaining copy), so growth is monotonic and cannot hide until later.
        OrchestratorImpl orch = (OrchestratorImpl) Orchestrator.create("churn", LogSink.SILENT);
        try {
            AtomicInteger notifications = new AtomicInteger();
            AtomicInteger throwerRuns = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();

            // Never unregistered, so it observes every publish exactly once.
            PublishListener observer = (t, v, n) -> notifications.incrementAndGet();
            // Removed and re-added by the churning threads.
            PublishListener churned = (t, v, n) -> { };
            PublishListener thrower = (t, v, n) -> {
                throwerRuns.incrementAndGet();
                throw new RuntimeException("always throws");
            };
            orch.addPublishListener(observer);
            orch.addPublishListener(churned);
            orch.addPublishListener(thrower);
            orch.addPublishListener(thrower);
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());
            assertEquals(4, orch.publishListenerCount(), "test setup: four registrations");

            int threads = 4;
            int perThread = 500;
            int publishes = threads * perThread;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger publishFailures = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            // Makes the remove/add pair one atomic step across both churning
            // threads. Held only for the two list calls, never across a
            // publish, so it does not serialise the parts of the test that are
            // meant to be concurrent.
            Object churnLock = new Object();

            for (int t = 0; t < threads; t++) {
                final boolean churn = t % 2 == 0;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            if (churn) {
                                synchronized (churnLock) {
                                    orch.removePublishListener(churned);
                                    orch.addPublishListener(churned);
                                }
                            }
                            orch.publish("t", (double) i);
                        }
                    } catch (Throwable e) {
                        publishFailures.incrementAndGet();
                        firstFailure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers should finish");

            assertEquals(0, publishFailures.get(),
                    () -> "publish must never fail because of the listener list; first failure: "
                            + firstFailure.get());
            // The assertion this test was always supposed to make. Without it
            // the churn threads could register the same listener any number of
            // times and nothing below would notice.
            assertEquals(4, orch.publishListenerCount(),
                    "churning must swap a listener, never duplicate it: concurrent remove/add of the"
                            + " same listener leaves one copy registered, because"
                            + " CopyOnWriteArrayList.remove removes only the first equal element");
            assertEquals(publishes, notifications.get(),
                    "the listener that is never unregistered must see every publish exactly once");
            assertTrue(throwerRuns.get() <= 2L * publishes,
                    "only the two registered throwers may run per publish, so their invocation count"
                            + " must stay linear in the publish count; got " + throwerRuns.get()
                            + " for " + publishes + " publishes");

            awaitCount(delivered, publishes);
            assertEquals(publishes, delivered.get(), "every publish must still reach its subscriber");
        } finally {
            orch.close();
        }
    }

    @Test
    void aDuplicateRegistrationIsVisibleToTheListenerCount() {
        // Guards the guard. publishListenerCount() is the only thing that can
        // see a duplicate registration, so if that accessor were ever wrong the
        // churn test above would pass vacuously.
        OrchestratorImpl orch = (OrchestratorImpl) Orchestrator.create("count", LogSink.SILENT);
        try {
            PublishListener listener = (t, v, n) -> { };
            assertEquals(0, orch.publishListenerCount());

            orch.addPublishListener(listener);
            assertEquals(1, orch.publishListenerCount());

            orch.addPublishListener(listener);
            assertEquals(2, orch.publishListenerCount(),
                    "adding the same listener twice registers two copies -- which is why concurrent"
                            + " remove/add must be atomic");

            // One remove drops one equal element, not all of them.
            orch.removePublishListener(listener);
            assertEquals(1, orch.publishListenerCount(),
                    "remove drops only the first equal element, so the duplicate survives");

            orch.removePublishListener(listener);
            assertEquals(0, orch.publishListenerCount());
        } finally {
            orch.close();
        }
    }

    @Test
    void aListenerSeesAPublishThatTypeValidationRejects() {
        // Listeners run before the topic's type is checked, so a publish the
        // bus rejects is still reported. That is deliberate: a type mismatch is
        // exactly the kind of fault a recording should be able to show, and
        // the caller still sees the throw.
        //
        // Read together with
        // aPublishOfNullIsRejectedBeforeAnyListenerRuns: the two
        // IllegalArgumentExceptions are not equivalent, and the docs say which
        // is which.
        Orchestrator orch = Orchestrator.create("type-mismatch", LogSink.SILENT);
        try {
            List<Object> seen = new ArrayList<>();
            orch.subscribe("t", Double.class, v -> { });
            orch.addPublishListener((topic, value, nanos) -> seen.add(value));

            assertThrows(IllegalArgumentException.class, () -> orch.publish("t", "not a double"),
                    "the caller still sees the type mismatch");

            assertEquals(List.of("not a double"), seen,
                    "a publish rejected by type validation must still be visible to listeners");
        } finally {
            orch.close();
        }
    }

    @Test
    void aPublishOfNullIsRejectedBeforeAnyListenerRuns() throws Exception {
        // The other IllegalArgumentException, and the one an earlier version of
        // the comment above got wrong. publish() runs its null check before the
        // listener block, so a null-value publish throws and no listener sees
        // it -- while a type-mismatch publish notifies and then throws. Nothing
        // distinguished them, and the comment claimed both were reported.
        //
        // This is the documented behaviour, not an accident:
        //   - onPublish documents its value as never null, so notifying would
        //     break the contract for every implementor;
        //   - a rejected null never became a publish. No topic is resolved, no
        //     latest value recorded, nothing dispatched;
        //   - it matches the closed-orchestrator case, which is also
        //     unobservable and was always pinned that way.
        // So the fix is to say so, not to hand listeners a null.
        //
        // Teeth: moving the null check below the listener block fails the
        // assertEquals on `seen` below with expected: <[]> but was: <[null]>.
        Orchestrator orch = Orchestrator.create("null-value", LogSink.SILENT);
        try {
            List<Object> seen = new ArrayList<>();
            AtomicInteger delivered = new AtomicInteger();
            orch.addPublishListener((topic, value, nanos) -> seen.add(value));
            orch.subscribe("t", Double.class, v -> delivered.incrementAndGet());

            assertThrows(IllegalArgumentException.class, () -> orch.publish("t", (Double) null),
                    "the caller still sees the rejection");

            assertEquals(List.of(), seen,
                    "a null-value publish is argument validation, not a publish; no listener may see"
                            + " it, and in particular none may be handed a null value");
            assertTrue(orch.getLatestValue("t", Double.class).isEmpty(),
                    "no value was published, so there is no latest value");

            // A topic nobody subscribed to, so this is about the publish alone:
            // subscribe creates its topic by itself, which is why "t" cannot
            // be used to check this.
            assertThrows(IllegalArgumentException.class,
                    () -> orch.publish("untouched", (Double) null), "same rejection");
            assertTrue(orch.findTopic("untouched").isEmpty(),
                    "a rejected null must not even create the topic; nothing was published");

            // The only assertion here that cannot be checked synchronously:
            // subscriber dispatch is asynchronous, and the claim is a negative
            // one, so the counter is given a bounded window to prove it never
            // moves rather than being polled for a value it must never reach.
            Thread.sleep(200);
            assertEquals(0, delivered.get(),
                    "a rejected null publish must not reach the callback pool");
        } finally {
            orch.close();
        }
    }

    /** Poll until {@code counter} reaches {@code target} or the deadline passes. */
    private static void awaitCount(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
    }

    @Test
    void aPublishOnAClosedOrchestratorDoesNotNotify() {
        Orchestrator orch = Orchestrator.create("closed");
        AtomicInteger calls = new AtomicInteger();
        orch.addPublishListener((t, v, n) -> calls.incrementAndGet());
        orch.close();

        orch.publish("t", 1.0);

        assertEquals(0, calls.get(),
                "a publish rejected because the bus is closed should not notify");
    }
}
