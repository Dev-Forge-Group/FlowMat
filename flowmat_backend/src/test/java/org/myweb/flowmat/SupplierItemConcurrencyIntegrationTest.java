package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** First writes of an item's usual supplier use the same lock before reading the unique item row. */
@AutoConfigureMockMvc
@Timeout(60)
class SupplierItemConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SupplierRepository suppliers;

    @Test
    void simultaneousIdenticalFirstAssignmentsReturnTheSameUnchangedRow() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = assignment(fixture, fixture.firstSupplierId(), "FIRST-CODE", "12.3456");

        List<Response> responses = race(fixture, body, body);

        assertEquals(200, responses.get(0).status());
        assertEquals(200, responses.get(1).status());
        JsonNode first = responses.get(0).data();
        JsonNode repeat = responses.get(1).data();
        assertFalse(first.path("supplierItemId").asText().isBlank());
        assertEquals(first.path("supplierItemId").asText(), repeat.path("supplierItemId").asText());
        assertEquals(0, first.path("version").asLong());
        assertEquals(0, repeat.path("version").asLong());
        assertSameAssignment(first, repeat);
        assertStored(fixture, first, fixture.firstSupplierId(), "FIRST-CODE", "12.3456");
    }

    @Test
    void simultaneousDifferentFirstAssignmentsKeepTheFirstCommitAndRejectTheOther() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> firstBody = assignment(fixture, fixture.firstSupplierId(), "KEEP-CODE", "8.7500");
        Map<String, Object> otherBody = assignment(fixture, fixture.secondSupplierId(), "OTHER-CODE", "99.1234");

        List<Response> responses = race(fixture, firstBody, otherBody);

        assertEquals(200, responses.get(0).status());
        assertEquals(409, responses.get(1).status(), "The other first write must reload after the first commit.");
        JsonNode first = responses.get(0).data();
        assertFalse(first.path("supplierItemId").asText().isBlank());
        assertEquals(0, first.path("version").asLong());
        assertStored(fixture, first, fixture.firstSupplierId(), "KEEP-CODE", "8.7500");
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from supplier_item where project_id=? and item_id=? and supplier_id=?",
            Integer.class, fixture.projectId(), fixture.itemId(), fixture.secondSupplierId()));
    }

    private List<Response> race(Fixture fixture, Map<String, Object> firstBody, Map<String, Object> secondBody) throws Exception {
        List<Future<Response>> pending = new ArrayList<>();
        // Java 21 close joins both workers even when a lock assertion or a response assertion fails.
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                TransactionTemplate gate = new TransactionTemplate(transactions);
                gate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                gate.executeWithoutResult(transaction -> {
                    // This transaction contains no API call; it only holds the exact production lock key.
                    suppliers.lockKey("supplier-item|" + fixture.projectId() + "|" + fixture.itemId());
                    int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                    pending.add(pool.submit(() -> perform(put("/supplier-items"), firstBody)));
                    DatabaseContention.awaitWaitingOrDone(jdbc, pending.get(0), blocker);
                    assertFalse(pending.get(0).isDone(), "The first assignment must wait on the held item lock.");
                    assertEquals(1, advisoryWaiters(blocker), "The first request must reach the lock before the second starts.");

                    pending.add(pool.submit(() -> perform(put("/supplier-items"), secondBody)));
                    awaitBothWaiting(pending, blocker);
                    assertEquals(0, rowCount(fixture), "Neither first write may insert before it acquires the item lock.");
                });
                // Returning (or throwing) from the transaction callback always releases the gate first.
                return List.of(pending.get(0).get(20, TimeUnit.SECONDS), pending.get(1).get(20, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private void awaitBothWaiting(List<Future<Response>> pending, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFalse(pending.get(0).isDone(), "The first request must still be blocked before gate release.");
            assertFalse(pending.get(1).isDone(), "The second request must wait on the same item lock.");
            if (advisoryWaiters(blocker) == 2) {
                return;
            }
            try {
                // Only a polling interval: pg_blocking_pids proves the actual database wait.
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while checking the two item lock waiters.", exception);
            }
        }
        fail("Both first assignments did not reach the held item advisory lock.");
    }

    private int advisoryWaiters(int blocker) {
        return jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and not granted "
            + "and ? = any(pg_blocking_pids(pid))",
            Integer.class, blocker);
    }

    private void assertStored(Fixture fixture, JsonNode first, String supplierId, String code, String price) throws Exception {
        assertEquals(1, rowCount(fixture), "uq_supplier_item permits one assignment for this project and item.");
        Map<String, Object> row = jdbc.queryForMap(
            "select supplier_item_id,supplier_id,supplier_item_code,unit_price,version,updated_by "
                + "from supplier_item where project_id=? and item_id=?", fixture.projectId(), fixture.itemId());
        assertEquals(first.path("supplierItemId").asText(), row.get("supplier_item_id"));
        assertEquals(supplierId, row.get("supplier_id"));
        assertEquals(code, row.get("supplier_item_code"));
        assertEquals(0, new BigDecimal(price).compareTo((BigDecimal) row.get("unit_price")));
        assertEquals(0L, ((Number) row.get("version")).longValue());
        assertEquals(DEMO_OWNER, row.get("updated_by"));
        JsonNode listed = ok(get("/supplier-items").param("projectId", fixture.projectId()), null);
        assertEquals(1, listed.size());
        assertSameAssignment(first, listed.get(0));
    }

    private static void assertSameAssignment(JsonNode expected, JsonNode actual) {
        ObjectNode expectedValues = expected.deepCopy();
        ObjectNode actualValues = actual.deepCopy();
        // A database reload may express the same instant with UTC instead of the original offset.
        assertEquals(OffsetDateTime.parse(expectedValues.remove("updatedAt").asText()).toInstant(),
            OffsetDateTime.parse(actualValues.remove("updatedAt").asText()).toInstant(),
            "A repeat must preserve the first update instant.");
        assertEquals(expectedValues, actualValues, "The id, version and assignment values must remain unchanged.");
    }

    private int rowCount(Fixture fixture) {
        return jdbc.queryForObject("select count(*) from supplier_item where project_id=? and item_id=?",
            Integer.class, fixture.projectId(), fixture.itemId());
    }

    private Fixture fixture() throws Exception {
        String tag = UUID.randomUUID().toString();
        String project = ok(post("/projects"), Map.of("projectName", "Supplier item race " + tag, "ownerId", DEMO_OWNER))
            .path("projectId").asText();
        String item = ok(post("/items"), Map.of("projectId", project, "itemCode", "RACE-" + tag,
            "itemName", "Supplier item race " + tag, "itemType", "material", "unitId", "unit_kg"))
            .path("itemId").asText();
        String first = ok(post("/suppliers"), Map.of("projectId", project, "supplierName", "First " + tag))
            .path("supplierId").asText();
        String second = ok(post("/suppliers"), Map.of("projectId", project, "supplierName", "Second " + tag))
            .path("supplierId").asText();
        assertTrue(List.of(project, item, first, second).stream().allMatch(id -> !id.isBlank()));
        Fixture fixture = new Fixture(project, item, first, second);
        assertEquals(0, rowCount(fixture));
        return fixture;
    }

    private static Map<String, Object> assignment(Fixture fixture, String supplierId, String code, String price) {
        Map<String, Object> body = new HashMap<>();
        body.put("projectId", fixture.projectId());
        body.put("itemId", fixture.itemId());
        body.put("supplierId", supplierId);
        body.put("supplierItemCode", code);
        body.put("unitPrice", new BigDecimal(price));
        body.put("expectedVersion", null);
        return body;
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, Object body) throws Exception {
        Response response = perform(request, body);
        assertEquals(200, response.status(), "Fixture creation or assignment lookup failed.");
        return response.data();
    }

    private Response perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        return new Response(response.getStatus(), parsed.path("data"));
    }

    private record Fixture(String projectId, String itemId, String firstSupplierId, String secondSupplierId) {
    }

    private record Response(int status, JsonNode data) {
    }
}
