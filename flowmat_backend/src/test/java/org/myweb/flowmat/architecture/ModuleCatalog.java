package org.myweb.flowmat.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

/** The product's module catalog, {@code modules.yml} at the repository root, as the catalog tests read it. */
final class ModuleCatalog {

    static final String FILE = "modules.yml";

    private final Path root;
    private final Map<String, Object> yaml;

    private ModuleCatalog(Path root, Map<String, Object> yaml) {
        this.root = root;
        this.yaml = yaml;
    }

    /** Tests run in {@code flowmat_backend}; the catalog is one directory up, at the product root. */
    static ModuleCatalog load() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path file = directory.resolve(FILE);
            if (Files.isRegularFile(file)) {
                try {
                    return new ModuleCatalog(directory, new Yaml().load(Files.readString(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(FILE + " was not found above " + Path.of("").toAbsolutePath());
    }

    Path root() {
        return root;
    }

    Path backend() {
        return root.resolve("flowmat_backend");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules() {
        return (List<Map<String, Object>>) yaml.get("modules");
    }

    /** The modules built into the backend: every {@code backend-module} that is past {@code proposed}. */
    List<Map<String, Object>> backendModules() {
        return modules().stream()
            .filter(module -> "backend-module".equals(module.get("kind")) && !"proposed".equals(module.get("status")))
            .toList();
    }

    @SuppressWarnings("unchecked")
    Set<String> repositories() {
        return ((Map<String, Object>) yaml.get("repositories")).keySet();
    }

    Set<String> unownedTables() {
        return strings(yaml.get("unowned_tables"));
    }

    static String id(Map<String, Object> module) {
        return (String) module.get("id");
    }

    /** A list field as a sorted set; a missing field is empty. */
    static Set<String> strings(Object list) {
        Set<String> values = new TreeSet<>();
        if (list instanceof List<?> items) {
            items.forEach(item -> values.add(String.valueOf(item)));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    static Set<String> foreignKeysTo(Map<String, Object> module) {
        Object data = module.get("data");
        return strings(data instanceof Map<?, ?> map ? ((Map<String, Object>) map).get("foreign_keys_to") : null);
    }
}
