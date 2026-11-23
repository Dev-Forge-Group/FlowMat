package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Item import from a spreadsheet (docs/domain/item-import.md) against real Postgres. */
@AutoConfigureMockMvc
class ItemImportIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void everyRowIsCheckedFirstAndTheFileIsSavedWholeOrNotAtAll() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String stocked = "IMP-A-" + tag;
        String bran = "IMP-B-" + tag;
        String stockedId = objectMapper.readTree(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", stocked,
                "itemName", "Old name", "unitId", "unit_kg"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data").path("itemId").asText();
        call(post("/inventories"), json(Map.of("projectId", DEMO_PROJECT, "itemId", stockedId, "quantity", 5, "location", "IMP-" + tag)))
            .andExpect(status().isOk());

        Map<String, String> newBran = row("itemCode", bran, "itemName", "Bran", "unitCode", "KG", "safetyStockQty", "5",
            "leadTimeDays", "3", "unitCost", "1.5", "lotTracked", "yes");
        Map<String, String> pricier = row("itemCode", stocked, "unitCost", "2");

        // One file with problems: every row is reported and nothing is saved, even though it was not a dry run.
        importRows(false, newBran, pricier,
                row("itemCode", bran, "itemName", "Bran again"),
                row("itemCode", "IMP-C-" + tag, "itemName", "Cane", "unitCode", "parsec"),
                row("itemCode", "IMP-D-" + tag, "itemName", "Dust", "unitCost", "-1", "leadTimeDays", "1.5"),
                row("itemCode", "IMP-E-" + tag))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.created").value(1))
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.errors").value(4))
            .andExpect(jsonPath("$.data.rows[1].message").value("unit cost"))
            .andExpect(jsonPath("$.data.rows[2].message").value(containsString("more than once")))
            .andExpect(jsonPath("$.data.rows[3].message").value(containsString("Unit parsec")))
            .andExpect(jsonPath("$.data.rows[4].message").value(containsString("cannot be negative")))
            .andExpect(jsonPath("$.data.rows[4].message").value(containsString("whole number")))
            .andExpect(jsonPath("$.data.rows[5].message").value(containsString("needs a name")));
        items().andExpect(jsonPath("$.data[*].itemCode").value(not(hasItem(bran))));

        // The good rows alone are saved, through the ordinary item rules.
        importRows(false, newBran, pricier)
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.created").value(1))
            .andExpect(jsonPath("$.data.updated").value(1));
        String branItem = "$.data[?(@.itemCode == '" + bran + "')]";
        String stockedItem = "$.data[?(@.itemCode == '" + stocked + "')]";
        items()
            .andExpect(jsonPath(branItem + ".unitId").value(hasItem("unit_kg")))
            .andExpect(jsonPath(branItem + ".lotManageYn").value(hasItem("Y")))
            .andExpect(jsonPath(branItem + ".unitCost").value(hasItem(1.5)))
            .andExpect(jsonPath(branItem + ".safetyStockQty").value(hasItem(5.0)))
            .andExpect(jsonPath(branItem + ".leadTimeDays").value(hasItem(3)))
            .andExpect(jsonPath(stockedItem + ".unitCost").value(hasItem(2.0)))
            .andExpect(jsonPath(stockedItem + ".itemName").value(hasItem("Old name")));

        // The same file again changes nothing.
        importRows(false, newBran, pricier)
            .andExpect(jsonPath("$.data.unchanged").value(2))
            .andExpect(jsonPath("$.data.created").value(0))
            .andExpect(jsonPath("$.data.updated").value(0));

        // LOT tracking stays while the item has stock; a dry run of a good change saves nothing.
        importRows(false, row("itemCode", stocked, "lotTracked", "Y"))
            .andExpect(jsonPath("$.data.errors").value(1))
            .andExpect(jsonPath("$.data.rows[0].message").value(containsString("LOT tracking")));
        importRows(true, row("itemCode", stocked, "itemName", "New name"))
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.applied").value(false));
        items().andExpect(jsonPath(stockedItem + ".itemName").value(hasItem("Old name")));

        importRows(false).andExpect(status().isBadRequest());
        mockMvc.perform(post("/items/import").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("projectId", DEMO_PROJECT, "dryRun", true, "rows", List.of(pricier))))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("item-import-outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void rowsLeftOutAreNotCheckedAndTheRestIsSavedTogether() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String oats = "SKP-A-" + tag;
        String rye = "SKP-B-" + tag;
        call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", rye, "itemName", "Rye", "unitId", "unit_kg")))
            .andExpect(status().isOk());
        List<Map<String, String>> file = List.of(
            row("itemCode", oats, "itemName", "Oats", "unitCode", "kg"),
            row("itemCode", "SKP-C-" + tag, "itemName", "Cane", "unitCode", "parsec"),
            row("itemCode", rye, "unitCost", "3"),
            row("itemCode", oats, "itemName", "Oats, the other line", "unitCode", "kg"));

        // As a whole the file is refused: a unit that does not exist, and a code twice.
        importRows(false, file, null)
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(2))
            .andExpect(jsonPath("$.data.skipped").value(0))
            .andExpect(jsonPath("$.data.rows[3].message").value(containsString("more than once")));
        items().andExpect(jsonPath("$.data[*].itemCode").value(not(hasItem(oats))));

        // Leaving out only the wrong unit still leaves the repeated code, so nothing is saved.
        importRows(false, file, List.of(2))
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(1))
            .andExpect(jsonPath("$.data.skipped").value(1))
            .andExpect(jsonPath("$.data.rows[1].action").value("skipped"));
        items().andExpect(jsonPath("$.data[*].itemCode").value(not(hasItem(oats))));

        // A left-out row is not checked: without the first oats line, the second one is the only one and is fine.
        importRows(true, file, List.of(1, 2))
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.rows[0].action").value("skipped"))
            .andExpect(jsonPath("$.data.rows[3].action").value("create"));

        // Without the two wrong lines the rest is saved in one go, and the lines left out are not.
        importRows(false, file, List.of(2, 4))
            .andExpect(jsonPath("$.data.applied").value(true))
            .andExpect(jsonPath("$.data.created").value(1))
            .andExpect(jsonPath("$.data.updated").value(1))
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.skipped").value(2))
            .andExpect(jsonPath("$.data.rows[1].action").value("skipped"))
            .andExpect(jsonPath("$.data.rows[3].action").value("skipped"));
        items()
            .andExpect(jsonPath("$.data[?(@.itemCode == '" + oats + "')].itemName").value(hasItem("Oats")))
            .andExpect(jsonPath("$.data[?(@.itemCode == '" + rye + "')].unitCost").value(hasItem(3.0)))
            .andExpect(jsonPath("$.data[*].itemCode").value(not(hasItem("SKP-C-" + tag))));
        // The same request again changes nothing.
        importRows(false, file, List.of(2, 4))
            .andExpect(jsonPath("$.data.created").value(0))
            .andExpect(jsonPath("$.data.updated").value(0))
            .andExpect(jsonPath("$.data.unchanged").value(2));

        // Rows that are not in the file, and a file left out whole, are refused.
        importRows(true, file, List.of(5)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("rows 1 to 4")));
        importRows(true, file, List.of(0)).andExpect(status().isBadRequest());
        importRows(true, file, List.of(1, 2, 3, 4)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Every row is left out")));
    }

    // ---- helpers ----

    @SafeVarargs
    private ResultActions importRows(boolean dryRun, Map<String, String>... rows) throws Exception {
        return call(post("/items/import"), json(Map.of("projectId", DEMO_PROJECT, "dryRun", dryRun, "rows", List.of(rows))));
    }

    private ResultActions importRows(boolean dryRun, List<Map<String, String>> rows, List<Integer> skipRows) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("projectId", DEMO_PROJECT, "dryRun", dryRun, "rows", rows));
        if (skipRows != null) {
            body.put("skipRows", skipRows);
        }
        return call(post("/items/import"), json(body));
    }

    private ResultActions items() throws Exception {
        return call(get("/items").param("projectId", DEMO_PROJECT));
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

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
