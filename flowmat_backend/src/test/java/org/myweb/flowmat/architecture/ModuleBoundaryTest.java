package org.myweb.flowmat.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The module rules of docs/architecture/adr/ADR-002-module-dependency.md. Stage A: a bounded context
 * ({@code org.myweb.flowmat.domain.<context>}) uses only its own repositories and reaches other contexts through their
 * {@code application.publicapi}. The violations frozen when that rule came in (105 on 2026-10-02) were all removed by
 * 2026-10-18 (docs/architecture/adr/ADR-012-workflow-access-boundary.md), so it has no frozen list and any violation
 * fails. Stage C (docs/architecture/adr/ADR-018-entity-boundary.md): a bounded context does not use another context's
 * {@code domain.entity} classes either. The 39 uses frozen when that rule came in (2026-10-19) were all removed by
 * 2026-10-25, so it has no frozen list either and any use fails. {@code global} and {@code batch} are not bounded
 * contexts and are not checked.
 */
@AnalyzeClasses(packages = "org.myweb.flowmat", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final Pattern CONTEXT = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)(\\..*)?$");
    private static final Pattern REPOSITORY = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.repository(\\..*)?$");
    private static final Pattern ENTITY = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.domain\\.entity(\\..*)?$");

    @ArchTest
    static final ArchRule repositoriesStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("repositories", REPOSITORY));

    @ArchTest
    static final ArchRule entitiesStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("entities", ENTITY));

    /** {@code kind} of another context, matched by {@code owned} whose first group is the owning context. */
    private static ArchCondition<JavaClass> useOnlyTheirOwnContexts(String kind, Pattern owned) {
        return new ArchCondition<>("use only their own bounded context's " + kind) {
            // One violation per top-level class and foreign class (a nested class counts as its top-level class), named
            // without signatures or line numbers.
            private final Set<String> uses = new TreeSet<>();

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                uses.clear();
            }

            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Matcher own = CONTEXT.matcher(origin.getPackageName());
                if (!own.matches()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    Matcher other = owned.matcher(target.getPackageName());
                    if (other.matches() && !other.group(1).equals(own.group(1))) {
                        uses.add(topLevelName(origin) + " uses " + topLevelName(target));
                    }
                }
            }

            @Override
            public void finish(ConditionEvents events) {
                for (String use : uses) {
                    events.add(SimpleConditionEvent.violated(use, use));
                }
            }
        };
    }

    /** {@code a.B} for {@code a.B}, {@code a.B$Inner} and {@code a.B$1}. */
    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
