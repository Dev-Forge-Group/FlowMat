package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Stock received from a spreadsheet (docs/domain/stock-import.md) against real Postgres. */
@AutoConfigureMockMvc
class StockImportIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void rowsReceiveIntoExistingRecordsOrMakeNewOnesAndTheFileIsSavedWholeOrNotAtAll() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String flour = "SI-FLOUR-" + tag;
        String sugar = "SI-SUGAR-" + tag;
        String flourId = item(flour, false);
        String sugarId = item(sugar, true);
        String saltId = item("SI-SALT-" + tag, true);
        String shelfA = "SI-A-" + tag;
        String shelfB = "SI-B-" + tag;
        String flourAtA = id(call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", flourId, "quantity", 10,
            "location", shelfA))), "inventoryId");
        call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", sugarId, "lotNo", "SI-OLD-" + tag, "expiryDate", "2026-12-31")))
            .andExpect(status().isOk());
        call(post("/lots"), json(Map.of("projectId", DEMO_PROJECT, "itemId", saltId, "lotNo", "SI-SALTLOT-" + tag))).andExpect(status().isOk());
        String newLot = "SI-NEW-" + tag;

        List<Map<String, String>> good = List.of(
            row("itemCode", flour, "location", shelfA, "quantity", "5"),
            row("itemCode", flour, "location", shelfB, "quantity", "3"),
            row("itemCode", sugar, "location", shelfA, "lotNo", newLot, "quantity", "4", "expiryDate", "2027-01-31"),
            row("itemCode", sugar, "location", shelfB, "lotNo", newLot, "quantity", "2"));
        List<Map<String, String>> all = new ArrayList<>(good);
        all.addAll(List.of(
            row("itemCode", sugar, "location", shelfA, "quantity", "1"),
            row("itemCode", flour, "location", "SI-C-" + tag, "lotNo", "L-X", "quantity", "1"),
            row("itemCode", "NOPE-" + tag, "quantity", "1"),
            row("itemCode", flour, "location", shelfB, "quantity", "0"),
            row("itemCode", sugar, "location", "SI-C-" + tag, "lotNo", "SI-OLD-" + tag, "quantity", "1", "expiryDate", "2030-01-01"),
            row("itemCode", sugar, "location", "SI-C-" + tag, "lotNo", "SI-SALTLOT-" + tag, "quantity", "1"),
            row("itemCode", sugar, "location", "SI-D-" + tag, "lotNo", "SI-OTHER-" + tag, "quantity", "1", "expiryDate", "31/01/2027")));

        // Seven bad rows: nothing is received, not even the four good ones.
        importRows(false, all)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(7))
            .andExpect(jsonPath("$.data.rows[0].action").value("receive"))
            .andExpect(jsonPath("$.data.rows[2].message").value("new LOT " + newLot))
            .andExpect(jsonPath("$.data.rows[4].message").value(containsString("give the LOT number")))
            .andExpect(jsonPath("$.data.rows[5].message").value(containsString("not LOT-tracked")))
            .andExpect(jsonPath("$.data.rows[6].message").value(containsString("No item has code")))
            .andExpect(jsonPath("$.data.rows[7].message").value(containsString("greater than 0")))
            .andExpect(jsonPath("$.data.rows[7].message").value(containsString("more than once")))
            .andExpect(jsonPath("$.data.rows[8].message").value(containsString("already exists with expiry 2026-12-31")))
            .andExpect(jsonPath("$.data.rows[9].message").value(containsString("for a different item")))
            .andExpect(jsonPath("$.data.rows[10].message").value(containsString("2027-01-31")));
        call(get("/inventories/" + flourAtA)).andExpect(jsonPath("$.data.quantity").value(10));
        call(get("/lots").param("projectId", DEMO_PROJECT)).andExpect(jsonPath("$.data[*].lotNo").value(not(hasItem(newLot))));

        // The good rows: one receipt into the existing record, three new records and one new LOT.
        importRows(false, good)
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.created").value(3))
            .andExpect(jsonPath("$.data.received").value(1))
            .andExpect(jsonPath("$.data.newLots").value(1));
        call(get("/inventories/" + flourAtA)).andExpect(jsonPath("$.data.quantity").value(15));
        call(get("/inventory-transactions").param("projectId", DEMO_PROJECT).param("inventoryId", flourAtA))
            .andExpect(jsonPath("$.data[0].transactionType").value("receipt"))
            .andExpect(jsonPath("$.data[0].referenceType").value("stock_import"));
        String newLotId = null;
        for (JsonNode lot : data(call(get("/lots").param("projectId", DEMO_PROJECT)))) {
            if (lot.path("lotNo").asText().equals(newLot)) {
                newLotId = lot.path("lotId").asText();
                assertThat(lot.path("expiryDate").asText()).isEqualTo("2027-01-31");
            }
        }
        assertThat(newLotId).isNotNull();
        double onNewLot = 0;
        double flourAtB = 0;
        for (JsonNode record : data(call(get("/inventories").param("projectId", DEMO_PROJECT)))) {
            if (newLotId.equals(record.path("lotId").asText())) {
                onNewLot += record.path("quantity").asDouble();
            }
            if (flourId.equals(record.path("itemId").asText()) && shelfB.equals(record.path("location").asText())) {
                flourAtB += record.path("quantity").asDouble();
            }
        }
        assertThat(onNewLot).isEqualTo(6.0);
        assertThat(flourAtB).isEqualTo(3.0);

        importRows(false, List.of()).andExpect(status().isBadRequest());
        mockMvc.perform(post("/inventories/import").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("projectId", DEMO_PROJECT, "dryRun", true, "rows", good)))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("stock-import-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void rowsLeftOutAreNotCheckedAndTheRestIsReceivedTogether() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String oats = "SK-OATS-" + tag;
        String oatsId = item(oats, false);
        String shelf = "SK-A-" + tag;
        List<Map<String, String>> file = List.of(
            row("itemCode", oats, "location", shelf, "quantity", "5"),
            row("itemCode", "NOPE-" + tag, "quantity", "1"),
            row("itemCode", oats, "location", shelf, "quantity", "2"));

        // As a whole the file is refused: an unknown item, and the same place twice.
        importRows(false, file, null)
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(2))
            .andExpect(jsonPath("$.data.skipped").value(0))
            .andExpect(jsonPath("$.data.rows[2].message").value(containsString("more than once")));
        // Leaving out only the unknown item still leaves the repeated place, so nothing is received.
        importRows(false, file, List.of(2))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(1))
            .andExpect(jsonPath("$.data.rows[1].action").value("skipped"));
        assertThat(stockOf(oatsId, shelf)).isEqualTo(0.0);
        // A left-out row is not checked: without the first oats line, the second is the only one at that place.
        importRows(true, file, List.of(1, 2))
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.rows[0].action").value("skipped"))
            .andExpect(jsonPath("$.data.rows[2].action").value("create"));

        // Without the two wrong lines the rest is received, and only that.
        importRows(false, file, List.of(2, 3))
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.created").value(1))
            .andExpect(jsonPath("$.data.received").value(0))
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.skipped").value(2));
        assertThat(stockOf(oatsId, shelf)).isEqualTo(5.0);

        // Rows that are not in the file, and a file left out whole, are refused.
        importRows(true, file, List.of(4)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("rows 1 to 3")));
        importRows(true, file, List.of(0)).andExpect(status().isBadRequest());
        importRows(true, file, List.of(1, 2, 3)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Every row is left out")));
        assertThat(stockOf(oatsId, shelf)).isEqualTo(5.0);
    }

    // ---- helpers ----

    private ResultActions importRows(boolean dryRun, List<Map<String, String>> rows) throws Exception {
        return importRows(dryRun, rows, null);
    }

    private ResultActions importRows(boolean dryRun, List<Map<String, String>> rows, List<Integer> skipRows) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "dryRun", dryRun, "rows", rows));
        if (skipRows != null) {
            body.put("skipRows", skipRows);
        }
        return call(post("/inventories/import"), json(body));
    }

    /** The item's quantity on hand at a place, over all its records there. */
    private double stockOf(String itemId, String location) throws Exception {
        double quantity = 0;
        for (JsonNode record : data(call(get("/inventories").param("projectId", DEMO_PROJECT)))) {
            if (itemId.equals(record.path("itemId").asText()) && location.equals(record.path("location").asText())) {
                quantity += record.path("quantity").asDouble();
            }
        }
        return quantity;
    }

    private String item(String code, boolean lotTracked) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", "unit_kg", "lotManageYn", lotTracked ? "Y" : "N"))), "itemId");
    }

    private static Map<String, String> row(String... pairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
