package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A destination scan must still match when the server obtains the project's movement lock. */
@AutoConfigureMockMvc
class WarehouseTaskScanConfirmationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtProvider jwtProvider;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRenameAfterTheScanRejectsWholeAndPartialMovesWithoutSideEffects(boolean partial) throws Exception {
        Fixture fixture = fixture();
        send(put("/storage-locations/" + fixture.location()), Map.of("locationCode", "NEW"), DEMO_OWNER, 200);
        Integer before = jdbc.queryForObject("select count(*) from inventory_transaction where project_id = ?", Integer.class, fixture.project());
        Map<String, Object> body = new HashMap<>(Map.of("expectedToLocation", "OLD"));
        if (partial) body.put("quantity", 1);
        JsonNode result = send(post("/warehouse-tasks/" + fixture.task() + "/complete"), body, DEMO_OWNER, 409);
        assertTrue(result.path("message").asText().contains("expectedToLocation"));
        assertEquals(before, jdbc.queryForObject("select count(*) from inventory_transaction where project_id = ?", Integer.class, fixture.project()));
        assertEquals(5, jdbc.queryForObject("select quantity from inventory where inventory_id = ?", Integer.class, fixture.stock()));
        assertEquals("open", jdbc.queryForObject("select status from warehouse_task where task_id = ?", String.class, fixture.task()));
        assertEquals(2, jdbc.queryForObject("select quantity from warehouse_task where task_id = ?", Integer.class, fixture.task()));
        JsonNode done = send(post("/warehouse-tasks/" + fixture.task() + "/complete"), Map.of("expectedToLocation", " new "), DEMO_OWNER, 200).path("data");
        assertEquals("NEW", done.path("toLocation").asText());
        assertEquals("done", done.path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchingScanAndLegacyClientsCanComplete(boolean legacy) throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = legacy ? Map.of() : Map.of("expectedToLocation", " old ");
        JsonNode done = send(post("/warehouse-tasks/" + fixture.task() + "/complete"), body, DEMO_OWNER, 200).path("data");
        assertEquals("done", done.path("status").asText());
        assertEquals("OLD", done.path("toLocation").asText());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 101})
    void suppliedConfirmationMustBeNonblankAndFitTheLocationCode(int length) throws Exception {
        Fixture fixture = fixture();
        String expected = length == 3 ? "   " : "X".repeat(length);
        send(post("/warehouse-tasks/" + fixture.task() + "/complete"), Map.of("expectedToLocation", expected), DEMO_OWNER, 400);
        assertEquals(5, jdbc.queryForObject("select quantity from inventory where inventory_id = ?", Integer.class, fixture.stock()));
    }

    @Test
    void confirmationDoesNotBypassProjectAuthorization() throws Exception {
        Fixture fixture = fixture();
        send(post("/warehouse-tasks/" + fixture.task() + "/complete"), Map.of("expectedToLocation", "OLD"), "unrelated-user", 403);
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 12);
        String project = send(post("/projects"), Map.of("projectName", "Scan confirmation " + tag, "ownerId", DEMO_OWNER), DEMO_OWNER, 200)
            .path("data").path("projectId").asText();
        String item = send(post("/items"), Map.of("projectId", project, "itemCode", "SCAN-" + tag,
            "itemName", "Scanned material", "unitId", "unit_kg"), DEMO_OWNER, 200).path("data").path("itemId").asText();
        String stock = send(post("/inventories"), Map.of("projectId", project, "itemId", item, "quantity", 5, "location", "SRC"), DEMO_OWNER, 200)
            .path("data").path("inventoryId").asText();
        String location = send(post("/storage-locations"), Map.of("projectId", project, "locationCode", "OLD", "locationType", "bin"), DEMO_OWNER, 200)
            .path("data").path("locationId").asText();
        String task = send(post("/warehouse-tasks"), Map.of("projectId", project, "inventoryId", stock, "quantity", 2, "toLocation", "OLD"), DEMO_OWNER, 200)
            .path("data").path("taskId").asText();
        return new Fixture(project, stock, location, task);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body, String user, int expectedStatus) throws Exception {
        String response = mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private record Fixture(String project, String stock, String location, String task) {}
}