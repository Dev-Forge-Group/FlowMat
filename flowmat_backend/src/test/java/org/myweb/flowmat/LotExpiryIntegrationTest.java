package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Expired LOTs (docs/domain/lot-expiry.md) against real Postgres. */
@AutoConfigureMockMvc
class LotExpiryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectCalendarQuery calendar;
    @MockitoBean private Clock projectClock;

    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @BeforeEach
    void fixProjectClock() {
        // Seoul is already on the next day while UTC is still on October 8.
        when(projectClock.instant()).thenReturn(Instant.parse("2026-10-08T15:30:00Z"));
    }

    @Test
    void anExpiredLotCanBeScrappedButNotUsedOrReserved() throws Exception {
        String flour = item("unit_kg");
        String expired = lot(flour, calendar.today(DEMO_PROJECT).minusDays(1));
        String lastDay = lot(flour, calendar.today(DEMO_PROJECT));
        String expiredStock = stock(flour, expired, "10");
        String lastDayStock = stock(flour, lastDay, "10");

        call(get("/lots/" + expired)).andExpect(jsonPath("$.data.expired").value(true));
        call(get("/lots/" + lastDay)).andExpect(jsonPath("$.data.expired").value(false));

        move(expiredStock, "reserve", "1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("expired on " + calendar.today(DEMO_PROJECT).minusDays(1))));
        String runId = startRun();
        call(post("/production-runs/" + runId + "/items"), recording(expiredStock, flour, "2"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("cannot be used or reserved")));

        // Scrapping it is still possible, and a LOT on its expiry date is still usable.
        move(expiredStock, "issue", "10").andExpect(status().isOk());
        call(post("/production-runs/" + runId + "/items"), recording(lastDayStock, flour, "2")).andExpect(status().isOk());
    }

    @Test
    void readinessDoesNotCountExpiredStock() throws Exception {
        String product = item("unit_ea");
        String salt = item("unit_kg");
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Brine\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}"), "bomId");
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + salt + "\",\"quantity\":1,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String orderId = id(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Brine\","
            + "\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"targetQuantity\":5,\"bomId\":\"" + bomId + "\"}"), "workOrderId");
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());

        stock(salt, lot(salt, calendar.today(DEMO_PROJECT).minusDays(3)), "100");
        stock(salt, lot(salt, calendar.today(DEMO_PROJECT).plusDays(30)), "2");
        call(get("/work-orders/" + orderId + "/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ready").value(false))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + salt + "')].availableQuantity").value(hasItem(2.0)))
            .andExpect(jsonPath("$.data.materials[?(@.itemId == '" + salt + "')].usableLots").value(hasItem(1)))
            // Salt has no unit cost, so the estimate says it is incomplete.
            .andExpect(jsonPath("$.data.checks[?(@.code == 'cost')].message").value(hasItem(containsString("no unit cost"))));
    }

    @Test
    void readinessWarnsAboutUsableLotsThatExpireSoon() throws Exception {
        String product = item("unit_ea");
        String sugar = item("unit_kg");
        String bomId = id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Syrup\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}"), "bomId");
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + sugar + "\",\"quantity\":1,\"unit\":\"kg\"}")
            .andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        String orderId = id(call(post("/work-orders"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workOrderTitle\":\"Syrup\","
            + "\"workflowId\":\"" + DEMO_WORKFLOW + "\",\"targetQuantity\":3,\"bomId\":\"" + bomId + "\"}"), "workOrderId");
        call(post("/work-orders/" + orderId + "/approve")).andExpect(status().isOk());
        String soonLot = lot(sugar, calendar.today(DEMO_PROJECT).plusDays(2));
        stock(sugar, soonLot, "5");

        // Still usable and counted, so ready, but the LOT that runs out in two days is called out.
        call(get("/work-orders/" + orderId + "/readiness"))
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'expiry')].status").value(hasItem("warn")))
            .andExpect(jsonPath("$.data.checks[?(@.code == 'expiry')].message")
                .value(hasItem(containsString("expires on " + calendar.today(DEMO_PROJECT).plusDays(2)))));
    }

    // ---- helpers ----

    private String item(String unitId) {
        String id = "itm-exp-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setLotManageYn("Y");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private String lot(String itemId, LocalDate expiryDate) throws Exception {
        return id(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"lotNo\":\"EX-"
            + suffix() + "\",\"expiryDate\":\"" + expiryDate + "\"}"), "lotId");
    }

    private String stock(String itemId, String lotId, String quantity) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\",\"lotId\":\"" + lotId + "\"}"), "inventoryId");
    }

    private ResultActions move(String inventoryId, String type, String quantity) throws Exception {
        return call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\"}");
    }

    private String startRun() throws Exception {
        return id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}"), "productionRunId");
    }

    private static String recording(String inventoryId, String itemId, String qty) {
        return "{\"inventoryId\":\"" + inventoryId + "\",\"itemId\":\"" + itemId + "\",\"direction\":\"input\",\"plannedQty\":"
            + qty + ",\"actualQty\":" + qty + ",\"unit\":\"kg\"}";
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
