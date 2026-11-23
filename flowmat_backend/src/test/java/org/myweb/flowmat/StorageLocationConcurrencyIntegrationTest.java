package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransferRequest;
import org.myweb.flowmat.domain.inventory.application.InventoryTransferService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@Timeout(45)
class StorageLocationConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private InventoryTransferService transferService;

    @ParameterizedTest
    @ValueSource(strings = {"rename", "metadata", "deactivate", "delete"})
    void aWaitingLocationMutationUsesTheCommittedCode(String operation) throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                try {
                    call(put("/storage-locations/" + fixture.sourceId()), Map.of("locationCode", "SRC-1"))
                        .andExpect(status().isOk());
                    Future<Integer> request = pool.submit(() -> mutate(operation, fixture));
                    DatabaseContention.awaitWaitingOrDone(jdbc, request, jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return request;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertEquals(operation.equals("deactivate") || operation.equals("delete") ? 409 : 200,
                pending.get(15, TimeUnit.SECONDS));
            String expected = operation.equals("rename") ? "SRC-2" : "SRC-1";
            assertEquals(expected, data(call(get("/inventories/" + fixture.stockId()), null)).path("location").asText());
            JsonNode places = data(call(get("/storage-locations").param("projectId", fixture.projectId()), null));
            assertTrue(places.findValues("locationCode").stream().anyMatch(code -> expected.equals(code.asText())));
            for (JsonNode task : data(call(get("/warehouse-tasks").param("projectId", fixture.projectId()), null))) {
                assertEquals(expected, task.path("fromLocation").asText());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWaitingTaskPlanUsesTheRenamedSource() throws Exception {
        Fixture fixture = fixture();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> pending = new TransactionTemplate(transactionManager).execute(transaction -> {
                try {
                    call(put("/storage-locations/" + fixture.sourceId()), Map.of("locationCode", "SRC-1"))
                        .andExpect(status().isOk());
                    Future<JsonNode> request = pool.submit(() -> data(call(post("/warehouse-tasks"), Map.of(
                        "projectId", fixture.projectId(), "inventoryId", fixture.stockId(), "quantity", 2, "toLocation", "DST"))));
                    DatabaseContention.awaitWaitingOrDone(jdbc, request, jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return request;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertEquals("SRC-1", pending.get(15, TimeUnit.SECONDS).path("fromLocation").asText());
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"source, full", "destination, full", "source, partial", "destination, partial"})
    void completingATaskAndRenamingEitherEndBothSucceed(String end, String amount) throws Exception {
        Fixture fixture = fixture();
        CountDownLatch moving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        // Pause the real completion immediately before its transfer, with all of its earlier locks still held.
        doAnswer(invocation -> {
            InventoryTransferRequest request = invocation.getArgument(0);
            if (fixture.stockId().equals(request.fromInventoryId())) {
                blockerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                moving.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS), "Completion was not released.");
            }
            return invocation.callRealMethod();
        }).when(transferService).transfer(any(InventoryTransferRequest.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> completing = pool.submit(() -> call(post("/warehouse-tasks/" + fixture.taskId() + "/complete"),
                amount.equals("partial") ? Map.of("quantity", 1) : Map.of()).andReturn().getResponse().getStatus());
            assertTrue(moving.await(15, TimeUnit.SECONDS), "Completion did not reach its transfer.");
            boolean source = end.equals("source");
            Future<Integer> renaming = pool.submit(() -> call(put("/storage-locations/" + (source ? fixture.sourceId() : fixture.destinationId())),
                Map.of("locationCode", source ? "SRC-R" : "DST-R")).andReturn().getResponse().getStatus());
            DatabaseContention.awaitWaitingOrDone(jdbc, renaming, blockerPid.get());
            release.countDown();
            assertEquals(200, completing.get(15, TimeUnit.SECONDS));
            assertEquals(200, renaming.get(15, TimeUnit.SECONDS));
            JsonNode stock = data(call(get("/inventories/" + fixture.stockId()), null));
            assertEquals(amount.equals("partial") ? 9 : 8, stock.path("quantity").asInt());
            assertEquals(source ? "SRC-R" : "SRC", stock.path("location").asText());
            for (JsonNode task : data(call(get("/warehouse-tasks").param("projectId", fixture.projectId()), null))) {
                assertEquals(source ? "SRC-R" : "SRC", task.path("fromLocation").asText());
                assertEquals(source ? "DST" : "DST-R", task.path("toLocation").asText());
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private int mutate(String operation, Fixture fixture) throws Exception {
        if (operation.equals("delete")) {
            return call(delete("/storage-locations/" + fixture.sourceId()), null).andReturn().getResponse().getStatus();
        }
        Map<String, ?> body = switch (operation) {
            case "rename" -> Map.of("locationCode", "SRC-2");
            case "deactivate" -> Map.of("active", false);
            default -> Map.of("locationName", "Updated name");
        };
        return call(put("/storage-locations/" + fixture.sourceId()), body).andReturn().getResponse().getStatus();
    }

    private Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String project = data(call(post("/projects"), Map.of("projectName", "Location concurrency " + suffix, "ownerId", DEMO_OWNER)))
            .path("projectId").asText();
        Item item = new Item();
        item.setItemId("itm-loc-race-" + suffix);
        item.setProjectId(project);
        item.setItemCode(item.getItemId().toUpperCase(java.util.Locale.ROOT));
        item.setItemName("Location concurrency material");
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        String source = data(call(post("/storage-locations"), Map.of("projectId", project, "locationCode", "SRC", "locationType", "bin")))
            .path("locationId").asText();
        String destination = data(call(post("/storage-locations"), Map.of("projectId", project, "locationCode", "DST", "locationType", "bin")))
            .path("locationId").asText();
        String stock = data(call(post("/inventories"), Map.of("projectId", project, "itemId", item.getItemId(), "quantity", 10, "location", "SRC")))
            .path("inventoryId").asText();
        String task = data(call(post("/warehouse-tasks"), Map.of("projectId", project, "inventoryId", stock, "quantity", 2, "toLocation", "DST")))
            .path("taskId").asText();
        return new Fixture(project, source, destination, stock, task);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private record Fixture(String projectId, String sourceId, String destinationId, String stockId, String taskId) {
    }
}
