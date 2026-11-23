package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Staged material covers a pick only while its LOT can actually be used in production. */
@AutoConfigureMockMvc
class WarehousePickReadinessIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectCalendarQuery calendar;
    @MockitoBean private Clock projectClock;

    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void fixProjectClock() {
        // Seoul is already on the next day while UTC is still on October 8.
        when(projectClock.instant()).thenReturn(Instant.parse("2026-10-08T15:30:00Z"));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0, 6, 2", "0, 4, 4, 0", "1, 4, 4, 0"})
    void stagedLotsCountOnlyThroughTheirExpiryDay(int expiryDays, int staged, int planned, int shortage) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = call("/projects", Map.of("projectName", "Pick readiness " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String item = call("/items", Map.of("projectId", project, "itemCode", "READY-" + tag,
            "itemName", "Pick readiness material", "unitId", "unit_kg", "lotManageYn", "Y"))
            .path("itemId").asText();
        String stagedLot = call("/lots", Map.of("projectId", project, "itemId", item, "lotNo", "STAGED-" + tag,
            "expiryDate", calendar.today(project).plusDays(expiryDays).toString())).path("lotId").asText();
        String usableLot = call("/lots", Map.of("projectId", project, "itemId", item, "lotNo", "USABLE-" + tag,
            "expiryDate", calendar.today(project).plusDays(30).toString())).path("lotId").asText();
        call("/inventories", Map.of("projectId", project, "itemId", item, "lotId", stagedLot, "quantity", 4, "location", "LINE"));
        String source = call("/inventories", Map.of("projectId", project, "itemId", item, "lotId", usableLot,
            "quantity", 6, "location", "STORE")).path("inventoryId").asText();

        JsonNode response = call("/warehouse-tasks/pick-list", Map.of("projectId", project, "stagingLocation", "LINE",
            "lines", List.of(Map.of("itemId", item, "quantity", 8))));
        JsonNode coverage = response.path("lines").get(0);
        assertEquals(staged, coverage.path("atStaging").asInt());
        assertEquals(planned, coverage.path("plannedNow").asInt());
        assertEquals(shortage, coverage.path("shortage").asInt());
        assertEquals(1, response.path("tasks").size());
        assertEquals(source, response.path("tasks").get(0).path("inventoryId").asText());
        assertEquals(planned, response.path("tasks").get(0).path("quantity").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "quarantined", "drained", "usable"})
    void queuedPicksCoverTheNeedOnlyWhileTheirSourceRemainsUsable(String state) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = call("/projects", Map.of("projectName", "Queued pick readiness " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String item = call("/items", Map.of("projectId", project, "itemCode", "QUEUE-" + tag,
            "itemName", "Queued pick material", "unitId", "unit_kg", "lotManageYn", "Y")).path("itemId").asText();
        String queuedLot = call("/lots", Map.of("projectId", project, "itemId", item, "lotNo", "QUEUED-" + tag,
            "expiryDate", calendar.today(project).plusDays(1).toString())).path("lotId").asText();
        String replacementLot = call("/lots", Map.of("projectId", project, "itemId", item, "lotNo", "REPLACEMENT-" + tag,
            "expiryDate", calendar.today(project).plusDays(30).toString())).path("lotId").asText();
        String queuedSource = call("/inventories", Map.of("projectId", project, "itemId", item, "lotId", queuedLot,
            "quantity", 4, "location", "STORE-A")).path("inventoryId").asText();
        String replacementSource = call("/inventories", Map.of("projectId", project, "itemId", item, "lotId", replacementLot,
            "quantity", 6, "location", "STORE-B")).path("inventoryId").asText();
        String queuedTask = call("/warehouse-tasks", Map.of("projectId", project, "taskType", "pick", "inventoryId", queuedSource,
            "quantity", 4, "toLocation", "LINE")).path("taskId").asText();
        if (state.equals("expired")) {
            // Simulate the date passing after the task was planned, only in this Testcontainers fixture.
            jdbc.update("update lot_master set expiry_date = ? where lot_id = ?", calendar.today(project).minusDays(1), queuedLot);
        } else if (state.equals("quarantined")) {
            call("/lots/" + queuedLot + "/recall/quarantine", Map.of("reason", "Hold the queued LOT for inspection"));
        } else if (state.equals("drained")) {
            call("/inventory-transactions", Map.of("inventoryId", queuedSource, "transactionType", "issue",
                "quantity", 2, "requestId", UUID.randomUUID().toString()));
        }
        Map<String, Object> request = Map.of("projectId", project, "stagingLocation", "LINE",
            "lines", List.of(Map.of("itemId", item, "quantity", 8)));
        JsonNode response = call("/warehouse-tasks/pick-list", request);
        JsonNode line = response.path("lines").get(0);
        int covered = state.equals("usable") ? 4 : state.equals("drained") ? 2 : 0;
        int planned = Math.min(8 - covered, 6);
        int shortage = 8 - covered - planned;
        assertEquals(covered, line.path("alreadyPlanned").asInt());
        assertEquals(planned, line.path("plannedNow").asInt());
        assertEquals(shortage, line.path("shortage").asInt());
        assertEquals(1, response.path("tasks").size());
        assertEquals(replacementSource, response.path("tasks").get(0).path("inventoryId").asText());
        // Replanning is idempotent, while the original task is retained for an operator to inspect/cancel.
        JsonNode repeated = call("/warehouse-tasks/pick-list", request);
        assertEquals(0, repeated.path("tasks").size());
        assertEquals(covered + planned, repeated.path("lines").get(0).path("alreadyPlanned").asInt());
        assertEquals(shortage, repeated.path("lines").get(0).path("shortage").asInt());
        assertEquals("open", jdbc.queryForObject("select status from warehouse_task where task_id = ?", String.class, queuedTask));
    }

    private JsonNode call(String path, Object body) throws Exception {
        String result = mockMvc.perform(post(path)
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(result).path("data");
    }
}
