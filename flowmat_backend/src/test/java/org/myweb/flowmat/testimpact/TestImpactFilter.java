package org.myweb.flowmat.testimpact;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Leaves out a test class whose last run passed against the same code ({@link TestImpact}).
 *
 * <p>Every class runs when any of these holds: the last record is missing, failed or has another key (resources,
 * build files, fixtures or libraries changed); a class it ran changed or is gone; a class that shapes every test
 * changed, appeared or was removed: Spring configuration and properties, controller advice, filters, interceptors,
 * listeners, bean post-processors, runners, JPA mappings, Jackson components, test extensions, Java migrations, or
 * this package. {@code testImpact.alwaysRun} classes always run, so the shared context starts and checks the schema.
 * A class left out keeps its last record and exec file, so the coverage report still counts it.
 */
public class TestImpactFilter implements PostDiscoveryFilter {

    private static final List<String> HOOK_TYPES = List.of(
        "jakarta.servlet.Filter",
        "org.springframework.web.servlet.HandlerInterceptor",
        "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
        "org.springframework.web.method.support.HandlerMethodArgumentResolver",
        "org.springframework.context.ApplicationListener",
        "org.springframework.context.ApplicationContextInitializer",
        "org.springframework.beans.factory.config.BeanPostProcessor",
        "org.springframework.beans.factory.config.BeanFactoryPostProcessor",
        "org.springframework.boot.ApplicationRunner",
        "org.springframework.boot.CommandLineRunner",
        "org.springframework.boot.env.EnvironmentPostProcessor",
        "org.springframework.test.context.TestExecutionListener",
        "org.springframework.test.context.ContextCustomizerFactory",
        "org.junit.jupiter.api.extension.Extension");
    private static final List<String> HOOK_ANNOTATIONS = List.of(
        "org.springframework.context.annotation.Configuration",
        "org.springframework.boot.context.properties.ConfigurationProperties",
        "org.springframework.web.bind.annotation.ControllerAdvice",
        "org.springframework.boot.jackson.JsonComponent",
        "jakarta.persistence.Entity",
        "jakarta.persistence.Embeddable",
        "jakarta.persistence.MappedSuperclass",
        "jakarta.persistence.Converter");
    private static final List<String> HOOK_METHOD_ANNOTATIONS = List.of(
        "org.springframework.context.annotation.Bean",
        "org.springframework.context.event.EventListener",
        "org.springframework.scheduling.annotation.Scheduled",
        "jakarta.annotation.PostConstruct",
        "jakarta.annotation.PreDestroy");

    private final TestImpact impact = TestImpact.current();
    private final Map<String, String> decided = new HashMap<>();
    private Map<String, TestImpact.Entry> previous;
    private String runAllBecause;

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (impact.previous == null || impact.out == null) return FilterResult.included("test impact is off");
        // JUnit filters the leaves (tests) and then drops the containers left empty.
        if (!descriptor.getChildren().isEmpty()) return FilterResult.included("a container");
        Optional<String> testClass = topLevelClass(descriptor);
        if (testClass.isEmpty()) return FilterResult.included("not in a class");
        String reason = decided.computeIfAbsent(testClass.get(), this::decide);
        return reason.isEmpty() ? FilterResult.included("runs") : FilterResult.excluded(reason);
    }

    /** Why the class is left out, or "" when it runs. Anything unexpected makes it run. */
    private String decide(String testClass) {
        try {
            if (impact.alwaysRun.contains(testClass)) return "";
            TestImpact.Entry entry = previous().get(testClass);
            if (entry == null || !entry.passedWith(impact.key)) return "";
            if (!runAllBecause().isEmpty()) return "";
            for (Map.Entry<String, String> ran : entry.classes().entrySet()) {
                if (!ran.getValue().equals(TestImpact.hashOfClass(ran.getKey()))) return "";
            }
            carryOver(testClass);
            return "already passed against the same code";
        } catch (RuntimeException | LinkageError unexpected) {
            return "";
        }
    }

    private Map<String, TestImpact.Entry> previous() {
        if (previous != null) return previous;
        previous = new HashMap<>();
        Path records = impact.previous.resolve("records");
        if (Files.isDirectory(records)) {
            try (Stream<Path> files = Files.list(records)) {
                files.filter(file -> file.toString().endsWith(".json")).forEach(file -> {
                    TestImpact.Entry entry = TestImpact.read(file);
                    String name = file.getFileName().toString();
                    if (entry != null) previous.put(name.substring(0, name.length() - ".json".length()), entry);
                });
            } catch (IOException unreadable) {
                previous.clear();
            }
        }
        return previous;
    }

    /**
     * Why every class runs this time, or "": a class that shapes every test changed or is new since the last run's class
     * list, or any class was removed. Also written to run-all.txt for the CI summary.
     */
    String runAllBecause() {
        if (runAllBecause != null) return runAllBecause;
        runAllBecause = findRunAllReason();
        if (!runAllBecause.isEmpty()) {
            TestImpact.write(impact.out.resolve("run-all.txt"), (runAllBecause + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return runAllBecause;
    }

    private String findRunAllReason() {
        Path listFile = TestImpact.classListFile(impact.previous);
        if (!Files.exists(listFile)) return "the last run left no class list";
        Map<String, String> before;
        try {
            before = TestImpact.JSON.readValue(listFile.toFile(),
                TestImpact.JSON.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
        } catch (IOException unreadable) {
            return "the last run's class list cannot be read";
        }
        Map<String, String> now = impact.currentClasses();
        for (String name : before.keySet()) {
            if (!now.containsKey(name)) return name + " was removed";
        }
        for (Map.Entry<String, String> current : now.entrySet()) {
            String was = before.get(current.getKey());
            if (current.getValue().equals(was)) continue;
            if (isHook(current.getKey())) return current.getKey() + (was == null ? " is new" : " changed");
        }
        return "";
    }

    /** A class that can change what every test sees without running in it. One that cannot be read counts as one. */
    static boolean isHook(String internalName) {
        if (internalName.startsWith(TestImpact.PROJECT + "testimpact/") || internalName.contains("/db/migration/")) return true;
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, TestImpactFilter.class.getClassLoader());
            for (String name : HOOK_TYPES) {
                Class<?> hook = load(name);
                if (hook != null && hook.isAssignableFrom(type)) return true;
            }
            for (String name : HOOK_ANNOTATIONS) {
                Class<? extends Annotation> annotation = annotation(name);
                if (annotation != null && AnnotatedElementUtils.hasAnnotation(type, annotation)) return true;
            }
            for (Method method : type.getDeclaredMethods()) {
                for (String name : HOOK_METHOD_ANNOTATIONS) {
                    Class<? extends Annotation> annotation = annotation(name);
                    if (annotation != null && AnnotatedElementUtils.hasAnnotation(method, annotation)) return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unreadable) {
            return true;
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, TestImpactFilter.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotation(String name) {
        Class<?> type = load(name);
        return type != null && type.isAnnotation() ? (Class<? extends Annotation>) type : null;
    }

    /** The class's last record and exec file go into this run's records, as if it had run. */
    private void carryOver(String testClass) {
        try {
            Path record = TestImpact.recordFile(impact.out, testClass);
            Files.createDirectories(record.getParent());
            Files.copy(TestImpact.recordFile(impact.previous, testClass), record, StandardCopyOption.REPLACE_EXISTING);
            Path exec = TestImpact.execFile(impact.previous, testClass);
            if (Files.exists(exec)) {
                Path target = TestImpact.execFile(impact.out, testClass);
                Files.createDirectories(target.getParent());
                Files.copy(exec, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(impact.out.resolve("left-out.txt"), testClass + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private static Optional<String> topLevelClass(TestDescriptor descriptor) {
        TestDescriptor current = descriptor;
        while (current != null) {
            Optional<TestSource> source = current.getSource();
            if (source.isPresent() && (source.get() instanceof ClassSource || source.get() instanceof MethodSource)) {
                String name = source.get() instanceof ClassSource classSource ? classSource.getClassName()
                    : ((MethodSource) source.get()).getClassName();
                int nested = name.indexOf('$');
                return Optional.of(nested < 0 ? name : name.substring(0, nested));
            }
            current = current.getParent().orElse(null);
        }
        return Optional.empty();
    }
}
