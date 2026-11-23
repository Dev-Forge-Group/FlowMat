package org.myweb.flowmat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
import org.myweb.flowmat.domain.inventory.application.LotService;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cancelling a run item reverses stock, marks the item and rebuilds the genealogy in one transaction: when the last
 * step fails, the first two are rolled back too (docs/domain/inventory-bom-lot-contract.md §3).
 */
@AutoConfigureMockMvc
class RunItemCancelRollbackIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;
    @MockitoSpyBean private LotService lotService;

    @Test
    void aFailingGenealogyRebuildLeavesStockItemAndGenealogyUntouched() throws Exception {
        String raw = item(true);
        String product = item(true);
        String rawLot = createLot(raw);
        String productLot = createLot(product);
        String rawStock = id(createStock(raw, rawLot, "20"), "inventoryId");
        String productStock = id(createStock(product, productLot, "0"), "inventoryId");
        String runId = id(call(post("/production-runs/start"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowId\":\""
            + DEMO_WORKFLOW + "\",\"plannedOutputQty\":1}").andExpect(status().isOk()), "productionRunId");
        String inputId = id(record(runId, rawStock, raw, "input", "5"), "productionRunItemId");
        record(runId, productStock, product, "output", "4");

        doThrow(new IllegalStateException("simulated genealogy failure")).when(lotService).clearRunTrace(anyString());

        call(post("/production-runs/" + runId + "/items/" + inputId + "/cancel"), "{\"reason\":\"Wrong LOT\"}")
            .andExpect(status().is5xxServerError());

        call(get("/inventories/" + rawStock)).andExpect(jsonPath("$.data.quantity").value(15));
        call(get("/production-runs/" + runId + "/items"))
            .andExpect(jsonPath("$.data[?(@.productionRunItemId == '" + inputId + "')].cancelled").value(false));
        call(get("/lots/" + productLot + "/trace")).andExpect(jsonPath("$.data.nodes.length()").value(1));
        call(get("/inventory-transactions").param("inventoryId", rawStock))
            .andExpect(jsonPath("$.data[?(@.transactionType == 'reversal')]").isEmpty());
    }

    private ResultActions record(String runId, String inventoryId, String itemId, String direction, String qty) throws Exception {
        return call(post("/production-runs/" + runId + "/items"), "{\"inventoryId\":\"" + inventoryId + "\",\"itemId\":\"" + itemId
            + "\",\"direction\":\"" + direction + "\",\"plannedQty\":" + qty + ",\"actualQty\":" + qty + ",\"unit\":\"kg\"}")
            .andExpect(status().isOk());
    }

    private String item(boolean lotTracked) {
        String id = "itm-rb-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId("unit_kg");
        item.setItemStatus("active");
        item.setLotManageYn(lotTracked ? "Y" : "N");
        item.setDeletedYn("N");
        itemRepository.save(item);
        return id;
    }

    private String createLot(String itemId) throws Exception {
        return id(call(post("/lots"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"lotNo\":\"RB-"
            + suffix() + "\"}").andExpect(status().isOk()), "lotId");
    }

    private ResultActions createStock(String itemId, String lotId, String quantity) throws Exception {
        return call(post("/inventories"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"itemId\":\"" + itemId + "\",\"quantity\":"
            + quantity + ",\"location\":\"WH-" + suffix().substring(0, 6) + "\",\"lotId\":\"" + lotId + "\"}");
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
