package org.myweb.flowmat.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.architecture.ModuleCatalog.id;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Data ownership in {@code modules.yml} against a database with every migration applied. A table belongs to the module
 * whose entity maps it, so each table has one owner or is listed as unowned, and the foreign keys that cross modules are
 * the ones the catalog names. A new foreign key into another module's tables ties the two modules' data together, which
 * decides whether they can ever be deployed or stored apart, so it has to be written down to pass.
 *
 * <p>Only foreign keys the database enforces are seen. Modules also point at each other's rows by plain id columns.
 */
class ModuleCatalogDataTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    /** A module's classes come from its build directory or its jar under it. */
    private static final Pattern MODULE_OF_CLASS = Pattern.compile("/modules/([a-z]+)/build/");

    private static final ModuleCatalog CATALOG = ModuleCatalog.load();
    private static final Set<String> TABLES = new TreeSet<>();
    /** Referencing table to the tables its foreign keys point at. */
    private static final Map<String, Set<String>> FOREIGN_KEYS = new TreeMap<>();
    /** Table to the modules whose entities map it. */
    private static final Map<String, Set<String>> OWNERS = new TreeMap<>();

    @BeforeAll
    static void migrateAndRead() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(
                 POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet tables = statement.executeQuery("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'
                """)) {
                while (tables.next()) {
                    TABLES.add(tables.getString(1));
                }
            }
            try (ResultSet keys = statement.executeQuery("""
                select conrelid::regclass::text, confrelid::regclass::text from pg_constraint
                where contype = 'f' and connamespace = 'public'::regnamespace
                """)) {
                while (keys.next()) {
                    FOREIGN_KEYS.computeIfAbsent(keys.getString(1), table -> new TreeSet<>()).add(keys.getString(2));
                }
            }
        }

        for (JavaClass entity : new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.myweb.flowmat")) {
            if (!entity.isAnnotatedWith(Entity.class)) {
                continue;
            }
            assertThat(entity.isAnnotatedWith(Table.class))
                .as("%s names its table with @Table, so its owner can be told", entity.getName())
                .isTrue();
            Matcher module = MODULE_OF_CLASS.matcher(entity.getSource().orElseThrow().getUri().toString());
            assertThat(module.find()).as("%s is in a module under modules/", entity.getName()).isTrue();
            OWNERS.computeIfAbsent(entity.getAnnotationOfType(Table.class).name().toLowerCase(), table -> new TreeSet<>())
                .add(module.group(1));
        }
    }

    @Test
    void everyTableHasOneOwningModuleOrIsListedAsUnowned() {
        List<String> problems = new ArrayList<>();
        Set<String> unowned = CATALOG.unownedTables();
        for (String table : TABLES) {
            Set<String> owners = OWNERS.getOrDefault(table, Set.of());
            if (owners.size() > 1) {
                problems.add(table + " is mapped by entities of " + owners);
            } else if (owners.isEmpty() && !unowned.contains(table)) {
                problems.add(table + " has no entity: map it in its module or add it to unowned_tables");
            } else if (!owners.isEmpty() && unowned.contains(table)) {
                problems.add(table + " is in unowned_tables but " + owners + " maps it");
            }
        }
        unowned.stream().filter(table -> !TABLES.contains(table))
            .forEach(table -> problems.add(table + " is in unowned_tables but not in the database"));
        OWNERS.keySet().stream().filter(table -> !TABLES.contains(table))
            .forEach(table -> problems.add(table + " is mapped by " + OWNERS.get(table) + " but not in the database"));

        assertThat(problems).as("table ownership in %s", ModuleCatalog.FILE).isEmpty();
    }

    @Test
    void foreignKeysBetweenModulesAreTheOnesCatalogued() {
        Map<String, Set<String>> referenced = new TreeMap<>();
        FOREIGN_KEYS.forEach((table, targets) -> {
            for (String target : targets) {
                String from = owner(table);
                String to = owner(target);
                if (from != null && to != null && !from.equals(to)) {
                    referenced.computeIfAbsent(from, module -> new TreeSet<>()).add(to);
                }
            }
        });

        for (Map<String, Object> module : CATALOG.backendModules()) {
            assertThat(ModuleCatalog.foreignKeysTo(module))
                .as("data.foreign_keys_to of %s and the modules its tables' foreign keys point at", id(module))
                .containsExactlyElementsOf(referenced.getOrDefault(id(module), Set.of()));
        }
    }

    /** The owning module, or null for an unowned table; ownership itself is the other test's concern. */
    private static String owner(String table) {
        Set<String> owners = OWNERS.getOrDefault(table, Set.of());
        return owners.size() == 1 ? owners.iterator().next() : null;
    }
}
