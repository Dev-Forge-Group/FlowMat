package org.myweb.flowmat.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.architecture.ModuleCatalog.id;
import static org.myweb.flowmat.architecture.ModuleCatalog.strings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code modules.yml} describes the modules as the build has them: the same module list as {@code settings.gradle}, the
 * same dependencies as each module's {@code build.gradle}, paths that exist and a contract where the module has a
 * {@code publicapi} package. A change to the build that the catalog does not follow fails here, so the catalog stays a
 * description that can be trusted. {@link ModuleCatalogDataTest} checks its data ownership against the database.
 */
class ModuleCatalogTest {

    private static final Set<String> STATUSES = Set.of("proposed", "internal", "extracting", "independent", "retired");
    private static final Pattern INCLUDED = Pattern.compile("'([a-z]+)'");
    private static final Pattern MODULE_DEPENDENCY = Pattern.compile("project\\(':modules:([a-z]+)'\\)");

    private final ModuleCatalog catalog = ModuleCatalog.load();

    @Test
    void listsTheBackendModulesOfTheBuild() {
        String settings = read(catalog.backend().resolve("settings.gradle"));
        Set<String> included = new TreeSet<>();
        Matcher matcher = INCLUDED.matcher(settings.substring(settings.indexOf('['), settings.indexOf("].each")));
        while (matcher.find()) {
            included.add(matcher.group(1));
        }

        assertThat(ids(catalog.backendModules()))
            .as("backend modules in %s and in settings.gradle", ModuleCatalog.FILE)
            .containsExactlyElementsOf(included);
    }

    @Test
    void dependsOnIsWhatEachModuleBuildsAgainst() {
        for (Map<String, Object> module : catalog.backendModules()) {
            Set<String> built = new TreeSet<>();
            Matcher matcher = MODULE_DEPENDENCY.matcher(read(catalog.root().resolve(path(module)).resolve("build.gradle")));
            while (matcher.find()) {
                built.add(matcher.group(1));
            }

            assertThat(strings(module.get("depends_on")))
                .as("depends_on of %s and the module projects in its build.gradle", id(module))
                .containsExactlyElementsOf(built);
        }
    }

    @Test
    void pathsExist() {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> module : catalog.modules()) {
            Set<String> paths = strings(module.get("frontend"));
            if (!"proposed".equals(module.get("status"))) {
                assertThat(path(module)).as("path of %s", id(module)).isNotBlank();
                paths.add(path(module));
            } else {
                assertThat(module.get("path")).as("path of %s, which is only proposed", id(module)).isNull();
            }
            paths.stream()
                .filter(path -> !Files.exists(catalog.root().resolve(path)))
                .forEach(path -> missing.add(id(module) + ": " + path));
        }

        assertThat(missing).as("catalogued paths that do not exist").isEmpty();
    }

    /** {@code contract: publicapi} says other modules may use the module, and they can only through that package. */
    @Test
    void contractIsThePublicApiPackage() {
        for (Map<String, Object> module : catalog.backendModules()) {
            boolean hasPublicApi;
            try (Stream<Path> files = Files.walk(catalog.root().resolve(path(module)).resolve("src/main/java"))) {
                hasPublicApi = files.anyMatch(file -> file.toString().endsWith(".java")
                    && file.getParent().getFileName().toString().equals("publicapi"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            assertThat(module.get("contract"))
                .as("contract of %s, whose publicapi package %s", id(module), hasPublicApi ? "exists" : "does not exist")
                .isEqualTo(hasPublicApi ? "publicapi" : "none");
        }
    }

    @Test
    void statusesAndRepositoriesAreKnown() {
        assertThat(ids(catalog.modules())).as("module ids").doesNotHaveDuplicates();

        for (Map<String, Object> module : catalog.modules()) {
            String status = (String) module.get("status");
            assertThat(STATUSES).as("status of %s", id(module)).contains(status);

            for (String field : List.of("repository", "candidate_repository")) {
                if (module.get(field) != null) {
                    assertThat(catalog.repositories()).as("%s of %s", field, id(module)).contains((String) module.get(field));
                }
            }
            // Once a module's source of truth moves, the catalog names where it went.
            if (status.equals("extracting") || status.equals("independent")) {
                assertThat(module.get("repository")).as("repository of %s, which is %s", id(module), status).isNotNull();
            }
        }
    }

    private static List<String> ids(List<Map<String, Object>> modules) {
        return modules.stream().map(ModuleCatalog::id).sorted().toList();
    }

    private static String path(Map<String, Object> module) {
        return (String) module.get("path");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
