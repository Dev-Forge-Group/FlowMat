package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.batch.StockAlertBatchService;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock alerts (docs/domain/stock-alert.md) against real Postgres. */
@AutoConfigureMockMvc
class StockAlertIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectCalendarQuery calendar;
    @MockitoBean private Clock projectClock;

    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StockAlertBatchService stockAlertBatchService;

    @BeforeEach
    void fixProjectClock() {
        // Seoul is already on the next day while UTC is still on October 8.
        when(projectClock.instant()).thenReturn(Instant.parse("2026-10-08T15:30:00Z"));
    }

    @Test
    void anAlertOpensFollowsTheStockAndClosesByItself() throws Exception {
        String item = item();
        String row = id(call(post("/inventories"), stock(item, "20", "10", "100")), "inventoryId");
        assertThat(alertsOf(row, true)).isEmpty();

        move(row, "issue", "15");
        List<JsonNode> low = alertsOf(row, true);
        assertThat(low).hasSize(1);
        JsonNode alert = low.get(0);
        assertThat(alert.path("alertType").asText()).isEqualTo("low");
        assertThat(alert.path("severity").asText()).isEqualTo("warning");
        assertThat(alert.path("actualValue").decimalValue()).isEqualByComparingTo("5");
        assertThat(alert.path("thresholdValue").decimalValue()).isEqualByComparingTo("10");
        assertThat(alert.path("unit").asText()).isEqualTo("kg");
        String alertId = alert.path("stockAlertId").asText();

        // Still low: the same alert, now critical because nothing is available.
        move(row, "issue", "5");
        List<JsonNode> stillLow = alertsOf(row, true);
        assertThat(stillLow).hasSize(1);
        assertThat(stillLow.get(0).path("stockAlertId").asText()).isEqualTo(alertId);
        assertThat(stillLow.get(0).path("severity").asText()).isEqualTo("critical");
        assertThat(stillLow.get(0).path("actualValue").decimalValue()).isEqualByComparingTo("0");

        move(row, "receipt", "30");
        assertThat(alertsOf(row, true)).isEmpty();
        assertThat(alertsOf(row, false))
            .anySatisfy(closed -> {
                assertThat(closed.path("stockAlertId").asText()).isEqualTo(alertId);
                assertThat(closed.path("resolved").asBoolean()).isTrue();
            });

        // Above the maximum.
        move(row, "receipt", "80");
        assertThat(alertsOf(row, true)).extracting(a -> a.path("alertType").asText()).containsExactly("over");

        // New thresholds on the form re-check the row without any movement.
        JsonNode currentStock = objectMapper.readTree(call(get("/inventories/" + row))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        call(put("/inventories/" + row), stock(item, "110", "150", "200", currentStock.path("version").asLong()))
            .andExpect(status().isOk());
        List<JsonNode> afterEdit = alertsOf(row, true);
        assertThat(afterEdit).extracting(a -> a.path("alertType").asText()).containsExactly("low");
        assertThat(afterEdit.get(0).path("actualValue").decimalValue()).isEqualByComparingTo("110");
        assertThat(afterEdit.get(0).path("thresholdValue").decimalValue()).isEqualByComparingTo("150");

        // Outside the project nothing is shown.
        mockMvc.perform(get("/stock-alerts?projectId=" + DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("stock-alert-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void deletingTheRowOrAChangeBehindTheServicesBackIsCaughtUp() throws Exception {
        String empty = id(call(post("/inventories"), stock(item(), "0", "5", null)), "inventoryId");
        assertThat(alertsOf(empty, true)).extracting(a -> a.path("severity").asText()).containsExactly("critical");
        call(delete("/inventories/" + empty)).andExpect(status().isOk());
        assertThat(alertsOf(empty, true)).isEmpty();

        // A threshold written straight to the table is picked up by the sweep, and cleared by it too.
        String row = id(call(post("/inventories"), stock(item(), "8", "0", null)), "inventoryId");
        assertThat(alertsOf(row, true)).isEmpty();
        setMinimum(row, "10");
        stockAlertBatchService.sweep();
        assertThat(alertsOf(row, true)).extracting(a -> a.path("alertType").asText()).containsExactly("low");
        setMinimum(row, "0");
        stockAlertBatchService.sweep();
        assertThat(alertsOf(row, true)).isEmpty();
    }

    @Test
    void stockOfALotNearOrPastItsExpiryDateRaisesAnExpiryAlert() throws Exception {
        String item = item("Y");
        String soon = lotStock(item, calendar.today(DEMO_PROJECT).plusDays(3), "5");
        String past = lotStock(item, calendar.today(DEMO_PROJECT).minusDays(1), "4");
        String later = lotStock(item, calendar.today(DEMO_PROJECT).plusDays(30), "5");

        List<JsonNode> soonAlerts = alertsOf(soon, true);
        assertThat(soonAlerts).extracting(a -> a.path("alertType").asText()).containsExactly("expiry");
        assertThat(soonAlerts.get(0).path("severity").asText()).isEqualTo("warning");
        assertThat(soonAlerts.get(0).path("actualValue").asInt()).isEqualTo(3);
        assertThat(soonAlerts.get(0).path("thresholdValue").asInt()).isEqualTo(7);

        List<JsonNode> pastAlerts = alertsOf(past, true);
        assertThat(pastAlerts).extracting(a -> a.path("severity").asText()).containsExactly("critical");
        assertThat(pastAlerts.get(0).path("actualValue").asInt()).isEqualTo(-1);
        assertThat(pastAlerts.get(0).path("message").asText()).contains("expired on " + calendar.today(DEMO_PROJECT).minusDays(1));
        assertThat(alertsOf(later, true)).isEmpty();

        // Scrapping the expired stock leaves nothing to warn about.
        move(past, "issue", "4");
        assertThat(alertsOf(past, true)).isEmpty();
    }

    @Test
    void theReorderListCountsOnlyUsableStockAgainstSafetyStock() throws Exception {
        // Safety stock and lead time go through the items API; negatives are refused.
        String code = "RO-" + suffix().substring(0, 8);
        call(post("/items"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemCode\":\"" + code + "X\",\"itemName\":\"x\","
            + "\"safetyStockQty\":-1}").andExpect(status().isBadRequest());
        JsonNode created = objectMapper.readTree(call(post("/items"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemCode\":\"" + code
            + "\",\"itemName\":\"reorder salt\",\"unitId\":\"unit_kg\",\"safetyStockQty\":20,\"leadTimeDays\":3}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String salt = created.path("itemId").asText();
        assertThat(created.path("safetyStockQty").decimalValue()).isEqualByComparingTo("20");

        // 8 usable + 5 quarantined: only 8 counts.
        id(call(post("/inventories"), stock(salt, "8", "0", null)), "inventoryId");
        String held = id(call(post("/inventories"), stock(salt, "5", "0", null)), "inventoryId");
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + held + "\",\"transactionType\":\"quarantine\",\"requestId\":\""
            + UUID.randomUUID() + "\"}").andExpect(status().isOk());

        // A LOT item whose only stock has expired has nothing usable.
        String milk = item("Y");
        setSafety(milk, "10");
        lotStock(milk, calendar.today(DEMO_PROJECT).minusDays(1), "30");

        // Enough stock: not listed.
        String sugar = item();
        setSafety(sugar, "5");
        id(call(post("/inventories"), stock(sugar, "10", "0", null)), "inventoryId");

        List<JsonNode> lines = new ArrayList<>();
        JsonNode body = objectMapper.readTree(call(get("/stock-alerts/reorder?projectId=" + DEMO_PROJECT))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        body.forEach(line -> {
            String itemId = line.path("itemId").asText();
            if (itemId.equals(salt) || itemId.equals(milk) || itemId.equals(sugar)) {
                lines.add(line);
            }
        });
        // Milk is short by all of its safety stock (100%), salt by 60%.
        assertThat(lines).extracting(line -> line.path("itemId").asText()).containsExactly(milk, salt);
        assertThat(lines.get(1).path("availableQuantity").decimalValue()).isEqualByComparingTo("8");
        assertThat(lines.get(1).path("shortageQuantity").decimalValue()).isEqualByComparingTo("12");
        assertThat(lines.get(1).path("leadTimeDays").asInt()).isEqualTo(3);
        assertThat(lines.get(1).path("unit").asText()).isEqualTo("kg");
        assertThat(lines.get(0).path("availableQuantity").decimalValue()).isEqualByComparingTo("0");
    }

    // ---- helpers ----

    private void setSafety(String itemId, String quantity) {
        Item item = itemRepository.findById(itemId).orElseThrow();
        item.setSafetyStockQty(new BigDecimal(quantity));
        itemRepository.save(item);
    }

    private String lotStock(String itemId, LocalDate expiryDate, String quantity) throws Exception {
        String lotId = id(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId
            + "\",\"lotNo\":\"AL-" + suffix() + "\",\"expiryDate\":\"" + expiryDate + "\"}"), "lotId");
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\",\"lotId\":\"" + lotId + "\"}"), "inventoryId");
    }

    private void setMinimum(String inventoryId, String minimum) {
        Inventory inventory = inventoryRepository.findById(inventoryId).orElseThrow();
        inventory.setMinThreshold(new BigDecimal(minimum));
        inventoryRepository.save(inventory);
    }

    /** The project's alerts that belong to one stock row. */
    private List<JsonNode> alertsOf(String inventoryId, boolean openOnly) throws Exception {
        String body = call(get("/stock-alerts?projectId=" + DEMO_PROJECT + "&openOnly=" + openOnly))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode alert : objectMapper.readTree(body).path("data")) {
            if (inventoryId.equals(alert.path("inventoryId").asText())) {
                mine.add(alert);
            }
        }
        return mine;
    }

    private String stock(String itemId, String quantity, String min, String max) {
        return stock(itemId, quantity, min, max, null);
    }

    private String stock(String itemId, String quantity, String min, String max, Long expectedVersion) {
        return "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":" + quantity
            + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\",\"minThreshold\":" + min
            + (max != null ? ",\"maxThreshold\":" + max : "")
            + (expectedVersion != null ? ",\"expectedVersion\":" + expectedVersion : "") + "}";
    }

    private void move(String inventoryId, String type, String quantity) throws Exception {
        call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());
    }

    private String item() {
        return item("N");
    }

    private String item(String lotManageYn) {
        String id = "itm-alert-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn(lotManageYn);
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
