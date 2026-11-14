package org.myweb.flowmat.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V135 drops the twelve tables V1 created and nothing used, but never a table that holds rows: there it stops, leaves
 * every table as it was, and can be run again once someone has decided what the rows are.
 */
class UnusedTableDropMigrationTest {

    private static final List<String> DROPPED = List.of("cad_import_job", "payment_discount", "project_activity_log",
        "project_comment", "project_export", "project_file", "project_permission", "resource_types", "resource_usage",
        "resources", "simulation_run", "simulation_step");

    @Test
    void theDropStopsOnATableThatHoldsRowsAndGoesThroughOnceItIsEmpty() throws SQLException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();
            flyway(postgres, "134").migrate();
            try (Connection connection = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                assertThat(existing(statement)).containsExactlyInAnyOrderElementsOf(DROPPED);
                statement.executeUpdate(
                    "insert into resource_types(rs_type_id, rs_code, rs_name, rs_unit) values ('kept', 'KEPT', 'kept', 'ea')");

                assertThatThrownBy(() -> flyway(postgres, "135").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("Table resource_types holds 1 row(s)");
                assertThat(existing(statement)).as("A refused drop leaves every table").containsExactlyInAnyOrderElementsOf(DROPPED);
                try (ResultSet kept = statement.executeQuery("select count(*) from resource_types")) {
                    kept.next();
                    assertThat(kept.getInt(1)).isEqualTo(1);
                }

                statement.executeUpdate("delete from resource_types");
                flyway(postgres, "135").migrate();
                assertThat(existing(statement)).isEmpty();
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }

    private static List<String> existing(Statement statement) throws SQLException {
        List<String> tables = new java.util.ArrayList<>();
        try (ResultSet found = statement.executeQuery("select table_name from information_schema.tables "
            + "where table_schema = 'public' and table_name in ('" + String.join("','", DROPPED) + "')")) {
            while (found.next()) {
                tables.add(found.getString(1));
            }
        }
        return tables;
    }
}
