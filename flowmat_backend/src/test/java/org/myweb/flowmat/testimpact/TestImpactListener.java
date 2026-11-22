package org.myweb.flowmat.testimpact;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records, for each top-level test class, how it ended and which project classes it ran ({@link TestImpact}).
 *
 * <p>The JaCoCo agent's data is taken and reset when a class starts and when it ends, so what is recorded for a class
 * is what ran while it ran (a Spring context it starts included). Every piece is also written as an exec file, so the
 * coverage report still counts all of it. A class whose tests did not all succeed is recorded as failed and runs
 * again next time.
 */
public class TestImpactListener implements TestExecutionListener {

    private final TestImpact impact = TestImpact.current();
    private final Map<String, Boolean> passed = new ConcurrentHashMap<>();
    private TestPlan plan;
    private int between;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
        if (impact.out == null) return;
        saveBetween();
        try {
            TestImpact.write(TestImpact.classListFile(impact.out), TestImpact.JSON.writeValueAsBytes(impact.currentClasses()));
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        if (impact.out == null || !isTopLevelClass(id)) return;
        saveBetween();
        passed.put(className(id.getSource().orElseThrow()), true);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (impact.out == null) return;
        Optional<String> testClass = topLevelClass(id);
        if (testClass.isEmpty()) return;
        // A test's own result: its class container still finishes as successful when one of its tests failed.
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) passed.put(testClass.get(), false);
        if (isTopLevelClass(id)) record(testClass.get(), passed.getOrDefault(testClass.get(), false));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (impact.out != null) saveBetween();
    }

    private void record(String testClass, boolean succeeded) {
        byte[] exec = TestImpact.dump(true);
        if (exec == null) return;
        TestImpact.write(TestImpact.execFile(impact.out, testClass), exec);
        Map<String, String> classes = new TreeMap<>();
        String outcome = succeeded ? "passed" : "failed";
        try {
            for (String name : TestImpact.executedClasses(exec)) {
                if (name.startsWith(TestImpact.PROJECT)) classes.put(name, TestImpact.hashOfClass(name));
            }
        } catch (IOException unreadable) {
            outcome = "untraced";
        }
        TestImpact.Entry entry = new TestImpact.Entry(TestImpact.FORMAT, impact.key, outcome, classes);
        try {
            TestImpact.write(TestImpact.recordFile(impact.out, testClass), TestImpact.JSON.writeValueAsBytes(entry));
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    /** What ran outside any test class (between classes, before the first): kept for coverage only. */
    private void saveBetween() {
        byte[] exec = TestImpact.dump(true);
        if (exec != null) TestImpact.write(TestImpact.execFile(impact.out, "_between-" + ProcessHandle.current().pid() + "-" + between++), exec);
    }

    private static boolean isTopLevelClass(TestIdentifier id) {
        return id.isContainer() && id.getSource().filter(ClassSource.class::isInstance)
            .map(source -> !((ClassSource) source).getClassName().contains("$")).orElse(false);
    }

    /** The top-level class a test or container belongs to, looking up through its parents when its source says nothing. */
    private Optional<String> topLevelClass(TestIdentifier id) {
        TestIdentifier current = id;
        while (current != null) {
            Optional<TestSource> source = current.getSource();
            if (source.isPresent() && (source.get() instanceof ClassSource || source.get() instanceof MethodSource)) {
                return Optional.of(className(source.get()));
            }
            current = plan == null ? null : plan.getParent(current).orElse(null);
        }
        return Optional.empty();
    }

    private static String className(TestSource source) {
        String name = source instanceof ClassSource classSource ? classSource.getClassName()
            : ((MethodSource) source).getClassName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
