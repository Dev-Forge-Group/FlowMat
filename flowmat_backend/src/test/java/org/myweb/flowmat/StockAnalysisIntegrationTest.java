package org.myweb.flowmat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Consumption, days of cover and idle stock (docs/domain/stock-analysis.md) against real Postgres. */
@AutoConfigureMockMvc
class StockAnalysisIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void consumptionInTheWindowSetsTheCoverAndIdleStockIsCalledOut() throws Exception {
        String flour = item("2", 200);
        String salt = item(null, null);
        String untouched = item(null, null);
        String flourStock = stock(flour, "100");
        String saltStock = stock(salt, "5");

        // 10 issued 40 days ago falls outside a 30-day window; the reversed 5 never counts.
        backdate(move(flourStock, "issue", "10"), 40);
        move(flourStock, "issue", "20");
        String mistake = move(flourStock, "issue", "5");
        call(post("/inventory-transactions/" + mistake + "/reversal"),
            "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"wrong item\"}").andExpect(status().isOk());
        jdbcTemplate.update("update inventory_transaction set created_at = created_at - interval '50 days' "
            + "where inventory_id = ? and transaction_type = 'receipt'", saltStock);

        String flourLine = "$.data.lines[?(@.itemId == '" + flour + "')]";
        String saltLine = "$.data.lines[?(@.itemId == '" + salt + "')]";
        call(get("/stock-analysis").param("projectId", DEMO_PROJECT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days").value(30))
            .andExpect(jsonPath(flourLine + ".onHandQuantity").value(hasItem(70.0)))
            .andExpect(jsonPath(flourLine + ".usableQuantity").value(hasItem(70.0)))
            .andExpect(jsonPath(flourLine + ".stockValue").value(hasItem(140.0)))
            .andExpect(jsonPath(flourLine + ".consumedQuantity").value(hasItem(20.0)))
            .andExpect(jsonPath(flourLine + ".averageDailyConsumption").value(hasItem(0.6667)))
            // 70 lasts 105 days at 20 per 30 days, shorter than the 200-day lead time.
            .andExpect(jsonPath(flourLine + ".daysOfCover").value(hasItem(105.0)))
            .andExpect(jsonPath(flourLine + ".coverBelowLeadTime").value(hasItem(true)))
            .andExpect(jsonPath(flourLine + ".idleDays").value(hasItem(0)))
            .andExpect(jsonPath(flourLine + ".consumedValue").value(hasItem(40.0)))
            .andExpect(jsonPath(saltLine + ".consumedValue").value(hasItem(nullValue())))
            .andExpect(jsonPath(saltLine + ".abcClass").value(hasItem(nullValue())))
            // Salt was received 50 days ago and never used.
            .andExpect(jsonPath(saltLine + ".consumedQuantity").value(hasItem(0.0)))
            .andExpect(jsonPath(saltLine + ".daysOfCover").value(hasItem(nullValue())))
            .andExpect(jsonPath(saltLine + ".lastConsumedAt").value(hasItem(nullValue())))
            .andExpect(jsonPath(saltLine + ".idleDays").value(hasItem(50)))
            .andExpect(jsonPath(saltLine + ".stockValue").value(hasItem(nullValue())))
            .andExpect(jsonPath("$.data.lines[*].itemId").value(not(hasItem(untouched))));

        // A 60-day window takes in the old issue: 30 used, so 70 lasts 140 days.
        call(get("/stock-analysis").param("projectId", DEMO_PROJECT).param("days", "60"))
            .andExpect(jsonPath(flourLine + ".consumedQuantity").value(hasItem(30.0)))
            .andExpect(jsonPath(flourLine + ".daysOfCover").value(hasItem(140.0)));

        call(get("/stock-analysis").param("projectId", DEMO_PROJECT).param("days", "0")).andExpect(status().isBadRequest());
        call(get("/stock-analysis").param("projectId", DEMO_PROJECT).param("days", "366")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/stock-analysis").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("stock-analysis-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void onePlaceIsAnalysedWithThePlacesInsideIt() throws Exception {
        // Its own project: once a project lists places, free-text stock locations stop working there.
        String project = id(call(post("/projects"), "{\"projectName\":\"Analysis " + suffix() + "\",\"ownerId\":\"" + DEMO_OWNER + "\"}"),
            "projectId");
        String warehouse = place(project, "WH-A", "warehouse", null);
        place(project, "BIN-A1", "bin", warehouse);
        place(project, "WH-B", "warehouse", null);
        String bolt = projectItem(project);
        String inBin = projectStock(project, bolt, "10", "bin-a1");
        String elsewhere = projectStock(project, bolt, "50", "WH-B");
        move(inBin, "issue", "4");
        move(elsewhere, "issue", "20");

        String line = "$.data.lines[?(@.itemId == '" + bolt + "')]";
        call(get("/stock-analysis").param("projectId", project))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.location").doesNotExist())
            .andExpect(jsonPath(line + ".onHandQuantity").value(hasItem(36.0)))
            .andExpect(jsonPath(line + ".consumedQuantity").value(hasItem(24.0)));
        // The warehouse takes the bin inside it, whatever the case of the code asked for (A5).
        call(get("/stock-analysis").param("projectId", project).param("location", "wh-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.location").value("WH-A"))
            .andExpect(jsonPath(line + ".onHandQuantity").value(hasItem(6.0)))
            .andExpect(jsonPath(line + ".consumedQuantity").value(hasItem(4.0)));
        call(get("/stock-analysis").param("projectId", project).param("location", "WH-B"))
            .andExpect(jsonPath(line + ".onHandQuantity").value(hasItem(30.0)))
            .andExpect(jsonPath(line + ".consumedQuantity").value(hasItem(20.0)));
        call(get("/stock-analysis").param("projectId", project).param("location", "NOWHERE"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Location NOWHERE is not in this project's location list."));
    }

    // ---- helpers ----

    private String place(String project, String code, String type, String parent) throws Exception {
        return id(call(post("/storage-locations"), "{\"projectId\":\"" + project + "\",\"locationCode\":\"" + code
            + "\",\"locationType\":\"" + type + "\"" + (parent == null ? "" : ",\"parentLocationId\":\"" + parent + "\"") + "}")
            .andExpect(status().isOk()), "locationId");
    }

    private String projectStock(String project, String itemId, String quantity, String location) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + project + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"" + location + "\"}").andExpect(status().isOk()), "inventoryId");
    }

    private String projectItem(String project) {
        String id = "itm-splace-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(project);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private void backdate(String inventoryTransactionId, int days) {
        jdbcTemplate.update("update inventory_transaction set created_at = created_at - make_interval(days => ?) "
            + "where inventory_transaction_id = ?", days, inventoryTransactionId);
    }

    private String move(String inventoryId, String type, String quantity) throws Exception {
        return id(call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\",\"quantity\":" + quantity + ",\"requestId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk()),
            "inventoryTransactionId");
    }

    private String stock(String itemId, String quantity) throws Exception {
        return id(call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"SA-" + suffix().substring(0, 6) + "\"}").andExpect(status().isOk()), "inventoryId");
    }

    private String item(String unitCost, Integer leadTimeDays) {
        String id = "itm-sana-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn("N");
        item.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        item.setLeadTimeDays(leadTimeDays);
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
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
