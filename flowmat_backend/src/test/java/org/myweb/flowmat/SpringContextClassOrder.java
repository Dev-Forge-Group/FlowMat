package org.myweb.flowmat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.springframework.test.context.BootstrapUtils;

/**
 * Runs test classes that share a Spring application context one after another (junit-platform.properties).
 *
 * <p>Spring caches contexts by their merged configuration, and build.gradle keeps at most four alive. In name order the
 * classes with their own {@code @MockitoBean} or {@code @MockitoSpyBean} sit between the ones that use the shared
 * context, which pushed it out of the cache: the suite started 42 contexts for 23 configurations, each one 2–4 s
 * (2026-11-22). Grouped by the same key Spring uses, each configuration starts once. Classes keep their name order
 * within a group, and the groups keep the order of their first class.
 */
public class SpringContextClassOrder implements ClassOrderer {

    @Override
    public void orderClasses(ClassOrdererContext context) {
        var classes = context.getClassDescriptors();
        classes.sort(Comparator.comparing(descriptor -> descriptor.getTestClass().getName()));
        Map<Object, Integer> groups = new HashMap<>();
        Map<ClassDescriptor, Integer> groupOf = new HashMap<>();
        for (ClassDescriptor descriptor : classes) {
            groupOf.put(descriptor, groups.computeIfAbsent(contextKey(descriptor.getTestClass()), key -> groups.size()));
        }
        classes.sort(Comparator.comparing(groupOf::get));
    }

    /** The key Spring's context cache uses; a class it cannot read keeps a group of its own. */
    private static Object contextKey(Class<?> testClass) {
        try {
            return BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
        } catch (RuntimeException | LinkageError unreadable) {
            return testClass.getName();
        }
    }
}
