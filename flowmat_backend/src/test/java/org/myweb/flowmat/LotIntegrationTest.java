package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** LOT v1 (docs/domain/inventory-bom-lot-contract.md §6) against real Postgres. */
@AutoConfigureMockMvc
class LotIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void lotTrackedStockMustNameALotAndOthersMustNot() throws Exception {
        String tracked = item("unit_kg", true);
        String plain = item("unit_kg", false);

        createStock(tracked, null, "5").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("is LOT-tracked")));
        call(post("/lots"), lotBody(plain, "PLAIN-" + suffix())).andExpect(status().isBadRequest());

        String lotNo = "L-" + suffix();
        String lotId = createLot(tracked, lotNo);
        createStock(plain, lotId, "5").andExpect(status().isBadRequest());
        call(post("/lots"), lotBody(tracked, lotNo.toLowerCase())).andExpect(status().isConflict());
        createStock(tracked, lotId, "5").andExpect(status().isOk()).andExpect(jsonPath("$.data.lotNo").value(lotNo));

        // A second record for the same LOT at the same place is refused with a clear message.
        createStock(tracked, lotId, "1", "WH-SAME").andExpect(status().isOk());
        createStock(tracked, lotId, "1", "WH-SAME")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("LOT " + lotNo + " already has a stock record at WH-SAME. Receive into that record instead."));
    }

    @Test
    void aStockRecordFromAnotherItemsLotIsRefused() throws Exception {
        String first = item("unit_kg", true);
        String second = item("unit_kg", true);
        String lotOfFirst = createLot(first, "X-" + suffix());

        createStock(second, lotOfFirst, "1").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("different item")));
    }

    @Test
    void quarantiningOneRowQuarantinesTheWholeLotAndBlocksProductionInput() throws Exception {
        String material = item("unit_kg", true);
        String lotId = createLot(material, "Q-" + suffix());
        String rowA = id(createStock(material, lotId, "10", "WH-A"), "inventoryId");
        String rowB = id(createStock(material, lotId, "10", "WH-B"), "inventoryId");

        move(rowA, "quarantine", null).andExpect(status().isOk());
        call(get("/lots/" + lotId)).andExpect(jsonPath("$.data.lotStatus").value("quarantined"));
        call(get("/inventories/" + rowB)).andExpect(jsonPath("$.data.inventoryStatus").value("quarantined"));

        String runId = startRun();
        recordRunItem(runId, rowB, material, "input", "1", "kg")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This stock is quarantined. Release it before using it."));

        move(rowA, "unquarantine", null).andExpect(status().isOk());
        call(get("/lots/" + lotId)).andExpect(jsonPath("$.data.lotStatus").value("available"));
        recordRunItem(runId, rowB, material, "input", "1", "kg").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lotId").value(lotId));
    }

    @Test
    void productionOfALotTrackedItemNeedsItsLot() throws Exception {
        String material = item("unit_kg", true);
        String runId = startRun();

        recordRunItem(runId, null, material, "input", "1", "kg")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("choose the stock record (LOT)")));
    }

    @Test
    void genealogyTracesBackwardAndForwardAcrossRuns() throws Exception {
        String raw = item("unit_kg", true);
        String middle = item("unit_ea", true);
        String finished = item("unit_ea", true);
        String lotA = createLot(raw, "RA-" + suffix());
        String lotB = createLot(raw, "RB-" + suffix());
        String lotM = createLot(middle, "M-" + suffix());
        String lotF = createLot(finished, "F-" + suffix());
        String stockA = id(createStock(raw, lotA, "100"), "inventoryId");
        String stockB = id(createStock(raw, lotB, "50"), "inventoryId");
        String stockM = id(createStock(middle, lotM, "0"), "inventoryId");
        String stockF = id(createStock(finished, lotF, "0"), "inventoryId");

        // Run 1: A + B -> M. Output recorded between the inputs to check both recording orders.
        String run1 = startRun();
        recordRunItem(run1, stockA, raw, "input", "10", "kg").andExpect(status().isOk());
        recordRunItem(run1, stockM, middle, "output", "20", "ea").andExpect(status().isOk());
        recordRunItem(run1, stockB, raw, "input", "5", "kg").andExpect(status().isOk());
        // Run 2: M -> F.
        String run2 = startRun();
        recordRunItem(run2, stockM, middle, "input", "20", "ea").andExpect(status().isOk());
        recordRunItem(run2, stockF, finished, "output", "4", "ea").andExpect(status().isOk());

        call(get("/lots/" + lotF + "/trace").param("direction", "backward"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotM, lotA, lotB)))
            .andExpect(jsonPath("$.data.nodes[?(@.lot.lotId == '" + lotM + "')].depth").value(1))
            .andExpect(jsonPath("$.data.nodes[?(@.lot.lotId == '" + lotA + "')].depth").value(2))
            .andExpect(jsonPath("$.data.nodes[?(@.lot.lotId == '" + lotA + "')].consumedQty").value(10.0));
        call(get("/lots/" + lotA + "/trace").param("direction", "forward"))
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotM, lotF)));
        call(get("/lots/" + lotM)).andExpect(jsonPath("$.data.productionRunId").value(run1));

        // Production movements are corrected on the run, never reversed from the stock screen.
        JsonNode history = objectMapper.readTree(call(get("/inventory-transactions").param("inventoryId", stockA))
            .andReturn().getResponse().getContentAsString()).path("data");
        String productionInputId = null;
        for (JsonNode tx : history) {
            if ("production_input".equals(tx.path("transactionType").asText())) {
                productionInputId = tx.path("inventoryTransactionId").asText();
            }
        }
        call(post("/inventory-transactions/" + productionInputId + "/reversal"),
            "{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"wrong\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("corrected on their production run")));
    }

    @Test
    void aLotClosesOnlyWhenEmptyAndThenTakesNoMovements() throws Exception {
        String material = item("unit_kg", true);
        String lotId = createLot(material, "C-" + suffix());
        String stock = id(createStock(material, lotId, "3"), "inventoryId");

        call(post("/lots/" + lotId + "/close")).andExpect(status().isConflict());
        move(stock, "issue", "3").andExpect(status().isOk());
        call(get("/lots/" + lotId)).andExpect(jsonPath("$.data.lotStatus").value("consumed"));

        call(post("/lots/" + lotId + "/close")).andExpect(status().isOk()).andExpect(jsonPath("$.data.lotStatus").value("closed"));
        move(stock, "receipt", "1").andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("is closed")));
    }

    @Test
    void cancellingAnInputReturnsTheStockAndDropsItsGenealogy() throws Exception {
        String raw = item("unit_kg", true);
        String product = item("unit_kg", true);
        String rawLot = createLot(raw, "CR-" + suffix());
        String productLot = createLot(product, "CP-" + suffix());
        String rawStock = id(createStock(raw, rawLot, "20"), "inventoryId");
        String productStock = id(createStock(product, productLot, "0"), "inventoryId");

        String runId = startRun();
        String inputId = id(recordRunItem(runId, rawStock, raw, "input", "5", "kg").andExpect(status().isOk()), "productionRunItemId");
        recordRunItem(runId, productStock, product, "output", "4", "kg").andExpect(status().isOk());
        call(get("/inventories/" + rawStock)).andExpect(jsonPath("$.data.quantity").value(15));
        call(get("/lots/" + productLot + "/trace")).andExpect(jsonPath("$.data.nodes.length()").value(1));

        cancel(runId, inputId, "Wrong LOT picked")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cancelled").value(true))
            .andExpect(jsonPath("$.data.cancelReason").value("Wrong LOT picked"))
            .andExpect(jsonPath("$.data.cancelledBy").value(DEMO_OWNER));
        call(get("/inventories/" + rawStock)).andExpect(jsonPath("$.data.quantity").value(20));
        call(get("/lots/" + productLot + "/trace")).andExpect(jsonPath("$.data.nodes.length()").value(0));

        cancel(runId, inputId, "again").andExpect(status().isConflict());
    }

    @Test
    void cancellingOneOfSeveralRecordingsKeepsTheRestOfTheGenealogy() throws Exception {
        String raw = item("unit_kg", true);
        String product = item("unit_kg", true);
        String lotA = createLot(raw, "MA-" + suffix());
        String lotB = createLot(raw, "MB-" + suffix());
        String lotX = createLot(product, "MX-" + suffix());
        String lotY = createLot(product, "MY-" + suffix());
        String stockA = id(createStock(raw, lotA, "20"), "inventoryId");
        String stockB = id(createStock(raw, lotB, "20"), "inventoryId");
        String stockX = id(createStock(product, lotX, "0"), "inventoryId");
        String stockY = id(createStock(product, lotY, "0"), "inventoryId");

        // A + B -> X + Y: four edges.
        String runId = startRun();
        String inputA = id(recordRunItem(runId, stockA, raw, "input", "5", "kg").andExpect(status().isOk()), "productionRunItemId");
        recordRunItem(runId, stockB, raw, "input", "3", "kg").andExpect(status().isOk());
        recordRunItem(runId, stockX, product, "output", "4", "kg").andExpect(status().isOk());
        String outputY = id(recordRunItem(runId, stockY, product, "output", "2", "kg").andExpect(status().isOk()),
            "productionRunItemId");
        call(get("/lots/" + lotX + "/trace")).andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotA, lotB)));

        // Cancelling input A leaves B -> X and B -> Y, with B's recorded quantity.
        cancel(runId, inputA, "Wrong LOT").andExpect(status().isOk());
        call(get("/lots/" + lotX + "/trace"))
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotB)))
            .andExpect(jsonPath("$.data.nodes[0].consumedQty").value(3.0));
        call(get("/lots/" + lotY + "/trace")).andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotB)));
        call(get("/lots/" + lotB + "/trace").param("direction", "forward"))
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotX, lotY)));

        // Cancelling output Y leaves B -> X; only Y loses its "produced by this run" mark.
        cancel(runId, outputY, "Counted twice").andExpect(status().isOk());
        call(get("/lots/" + lotB + "/trace").param("direction", "forward"))
            .andExpect(jsonPath("$.data.nodes[*].lot.lotId").value(containsInAnyOrder(lotX)));
        call(get("/lots/" + lotY + "/trace")).andExpect(jsonPath("$.data.nodes.length()").value(0));
        call(get("/lots/" + lotX)).andExpect(jsonPath("$.data.productionRunId").value(runId));
        call(get("/lots/" + lotY)).andExpect(jsonPath("$.data.productionRunId").value(nullValue()));
        call(get("/inventories/" + stockA)).andExpect(jsonPath("$.data.quantity").value(20));
        call(get("/inventories/" + stockY)).andExpect(jsonPath("$.data.quantity").value(0));
    }

    @Test
    void cancellingAnOutputWhoseStockWasUsedIsRefused() throws Exception {
        String product = item("unit_kg", true);
        String productLot = createLot(product, "CU-" + suffix());
        String productStock = id(createStock(product, productLot, "0"), "inventoryId");
        String runId = startRun();
        String outputId = id(recordRunItem(runId, productStock, product, "output", "4", "kg").andExpect(status().isOk()),
            "productionRunItemId");
        move(productStock, "issue", "3").andExpect(status().isOk());

        cancel(runId, outputId, "Counted twice")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Not enough available stock")));
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(jsonPath("$.data[0].cancelled").value(false));
        call(get("/inventories/" + productStock)).andExpect(jsonPath("$.data.quantity").value(1));
    }

    @Test
    void itemsOfAFinishedRunCannotBeCancelled() throws Exception {
        String material = item("unit_kg", false);
        String runId = startRun();
        String itemId = id(recordRunItem(runId, null, material, "input", "1", "kg").andExpect(status().isOk()),
            "productionRunItemId");
        call(post("/production-runs/" + runId + "/finish"), "{\"actualOutputQty\":1}").andExpect(status().isOk());

        cancel(runId, itemId, "late fix").andExpect(status().isBadRequest());
    }

    private ResultActions cancel(String runId, String itemId, String reason) throws Exception {
        return call(post("/production-runs/" + runId + "/items/" + itemId + "/cancel"), "{\"reason\":\"" + reason + "\"}");
    }

    // ---- helpers ----

    private String item(String unitId, boolean lotTracked) {
        String id = "itm-lot-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setLotManageYn(lotTracked ? "Y" : "N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private String createLot(String itemId, String lotNo) throws Exception {
        return id(call(post("/lots"), lotBody(itemId, lotNo)).andExpect(status().isOk()), "lotId");
    }

    private static String lotBody(String itemId, String lotNo) {
        return "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"lotNo\":\"" + lotNo + "\"}";
    }

    private ResultActions createStock(String itemId, String lotId, String quantity) throws Exception {
        return createStock(itemId, lotId, quantity, "WH-" + suffix().substring(0, 6));
    }

    private ResultActions createStock(String itemId, String lotId, String quantity, String location) throws Exception {
        return call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"" + location + "\"" + (lotId != null ? ",\"lotId\":\"" + lotId + "\"" : "") + "}");
    }

    private ResultActions move(String inventoryId, String type, String quantity) throws Exception {
        return call(post("/inventory-transactions"), "{\"inventoryId\":\"" + inventoryId + "\",\"transactionType\":\"" + type
            + "\"," + (quantity != null ? "\"quantity\":" + quantity + "," : "") + "\"requestId\":\"" + UUID.randomUUID() + "\"}");
    }

    private String startRun() throws Exception {
        return id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}").andExpect(status().isOk()), "productionRunId");
    }

    private ResultActions recordRunItem(String runId, String inventoryId, String itemId, String direction, String qty, String unit)
        throws Exception {
        return call(post("/production-runs/" + runId + "/items"), "{"
            + (inventoryId != null ? "\"inventoryId\":\"" + inventoryId + "\"," : "")
            + "\"itemId\":\"" + itemId + "\",\"direction\":\"" + direction + "\",\"plannedQty\":" + qty
            + ",\"actualQty\":" + qty + ",\"unit\":\"" + unit + "\"}");
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
        return data.path(field).asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
