package org.myweb.flowmat.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
 * 2026-10-25, so it has no frozen list either and any use fails. Since 2026-11-07 the contexts may not depend on each
 * other in a cycle either, nor use another context's web layer ({@code api}) or its other domain types such as enums
 * (docs/architecture/adr/ADR-036-cross-context-contract-types.md). Since 2026-11-08 another context's {@code application}
 * is reached through its {@code publicapi} only. {@code global} and {@code batch} are not bounded contexts and are not
 * checked.
 */
@AnalyzeClasses(packages = "org.myweb.flowmat", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final Pattern CONTEXT = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)(\\..*)?$");
    private static final Pattern REPOSITORY = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.repository(\\..*)?$");
    private static final Pattern ENTITY = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.domain\\.entity(\\..*)?$");
    private static final Pattern WEB = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.api(\\..*)?$");
    private static final Pattern DOMAIN = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.domain(\\..*)?$");
    private static final Pattern APPLICATION = Pattern.compile("^org\\.myweb\\.flowmat\\.domain\\.(\\w+)\\.application(\\..*)?$");
    /** Not checked by the application rule: every context's publicapi. */
    private static final Pattern PUBLIC_APPLICATION = Pattern.compile(
        "^org\\.myweb\\.flowmat\\.domain\\.\\w+\\.application\\.publicapi\\..*$");
    /**
     * Not checked by the domain type rule: entities, which have their own rule, and workflow's definition language (port
     * schemas and condition expressions), which flowrun executes as published (ADR-036).
     */
    private static final Pattern NOT_DOMAIN_TYPES = Pattern.compile(
        "^org\\.myweb\\.flowmat\\.domain\\.(\\w+\\.domain\\.entity|workflow\\.domain\\.contract|workflow\\.domain\\.expression)(\\..*)?$");

    @ArchTest
    static final ArchRule repositoriesStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("repositories", REPOSITORY));

    @ArchTest
    static final ArchRule entitiesStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("entities", ENTITY));

    /** Another context's request and response types change with its HTTP API; contexts share publicapi types instead. */
    @ArchTest
    static final ArchRule webLayersStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("web layer", WEB));

    /** Enums and other domain types besides entities: another context reads statuses as codes from publicapi views. */
    @ArchTest
    static final ArchRule domainTypesStayInsideTheirBoundedContext = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("domain types", DOMAIN, NOT_DOMAIN_TYPES));

    /**
     * Another context's application layer through its publicapi: its queries and commands, and the ports it declares for
     * higher contexts to implement (for example {@code catalog.application.publicapi.ItemUsageCheck}).
     */
    @ArchTest
    static final ArchRule applicationLayersAreReachedThroughTheirPublicApi = classes().that().resideInAPackage("org.myweb.flowmat.domain..")
        .should(useOnlyTheirOwnContexts("application classes outside publicapi", APPLICATION, PUBLIC_APPLICATION));

    /**
     * Bounded contexts depend on each other in one direction only, so each can become its own Gradle module. When a lower
     * context needs something a higher one owns, the lower context declares an interface and the higher one implements it
     * (for example {@code catalog.application.ItemStockCheck}, implemented in inventory).
     */
    @ArchTest
    static final ArchRule boundedContextsAreFreeOfCycles = slices().matching("org.myweb.flowmat.domain.(*)..")
        .should().beFreeOfCycles();

    /** {@code kind} of another context, matched by {@code owned} whose first group is the owning context. */
    private static ArchCondition<JavaClass> useOnlyTheirOwnContexts(String kind, Pattern owned) {
        return useOnlyTheirOwnContexts(kind, owned, null);
    }

    /** As above, leaving out the classes whose full name {@code except} matches. */
    private static ArchCondition<JavaClass> useOnlyTheirOwnContexts(String kind, Pattern owned, Pattern except) {
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
                    if (other.matches() && !other.group(1).equals(own.group(1))
                        && (except == null || !except.matcher(target.getName()).matches())) {
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
