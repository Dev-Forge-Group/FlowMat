package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /inventory-transactions is a stock-changing command (docs/domain/inventory-bom-lot-contract.md §2–3):
 * stock and history change together, retries are idempotent, and the stock invariants hold.
 */
@AutoConfigureMockMvc
class InventoryCommandIntegrationTest extends IntegrationTestSupport {

    private static final String DEMO_ITEM = "itm_demo_mix_output";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;

    private String inventoryId;

    @BeforeEach
    void createStock() {
        inventoryId = "invcmd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Inventory inventory = new Inventory();
        inventory.setInventoryId(inventoryId);
        inventory.setProjectId(DEMO_PROJECT);
        inventory.setItemId(DEMO_ITEM);
        inventory.setQuantity(new BigDecimal("10"));
        inventory.setReservedQuantity(BigDecimal.ZERO);
        inventory.setAvailableQuantity(new BigDecimal("10"));
        inventory.setInventoryStatus("available");
        inventory.setMinThreshold(BigDecimal.ZERO);
        inventory.setDeletedYn("N");
        inventoryRepository.save(inventory);
    }

    @Test
    void issueChangesStockAndHistoryTogetherAndIgnoresClientAuthor() throws Exception {
        move("{\"transactionType\":\"issue\",\"quantity\":4,\"requestId\":\"" + key() + "\",\"createdBy\":\"someone-else\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactionType").value("issue"))
            .andExpect(jsonPath("$.data.quantityDelta").value(-4))
            .andExpect(jsonPath("$.data.quantityAfter").value(6))
            .andExpect(jsonPath("$.data.availableAfter").value(6))
            .andExpect(jsonPath("$.data.createdBy").value(DEMO_OWNER));

        assertThat(stock().getQuantity()).isEqualByComparingTo("6");
    }

    @Test
    void resendingTheSameRequestIdDoesNotMoveStockTwice() throws Exception {
        String body = "{\"transactionType\":\"issue\",\"quantity\":3,\"requestId\":\"" + key() + "\"}";
        String first = move(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = move(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(id(second)).isEqualTo(id(first));
        assertThat(stock().getQuantity()).isEqualByComparingTo("7");
    }

    @Test
    void reusingARequestIdForDifferentContentIsAConflict() throws Exception {
        String requestId = key();
        move("{\"transactionType\":\"issue\",\"quantity\":1,\"requestId\":\"" + requestId + "\"}").andExpect(status().isOk());

        move("{\"transactionType\":\"issue\",\"quantity\":2,\"requestId\":\"" + requestId + "\"}")
            .andExpect(status().isConflict());
        assertThat(stock().getQuantity()).isEqualByComparingTo("9");
    }

    @Test
    void issuingMoreThanAvailableIsRefused() throws Exception {
        move("{\"transactionType\":\"issue\",\"quantity\":11,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Not enough available stock: 10 available, 11 needed."));
        assertThat(stock().getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void reservingBeyondAvailableIsRefused() throws Exception {
        move("{\"transactionType\":\"reserve\",\"quantity\":8,\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());

        move("{\"transactionType\":\"reserve\",\"quantity\":3,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isConflict());
        // Reserved stock is not available for issue either.
        move("{\"transactionType\":\"issue\",\"quantity\":3,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isConflict());
        Inventory after = stock();
        assertThat(after.getReservedQuantity()).isEqualByComparingTo("8");
        assertThat(after.getAvailableQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void internalAndUnknownTypesAreRejected() throws Exception {
        move("{\"transactionType\":\"production_input\",\"quantity\":1,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isBadRequest());
        move("{\"transactionType\":\"teleport\",\"quantity\":1,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isBadRequest());
        move("{\"transactionType\":\"receipt\",\"quantity\":1}").andExpect(status().isBadRequest());
        move("{\"transactionType\":\"receipt\",\"quantity\":0.00001,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isBadRequest());
    }

    @Test
    void aTransactionCanBeReversedOnceWithReason() throws Exception {
        String receipt = move("{\"transactionType\":\"receipt\",\"quantity\":5,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String receiptId = id(receipt);

        reverse(receiptId, "{\"requestId\":\"" + key() + "\",\"reason\":\"Received twice by mistake\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transactionType").value("reversal"))
            .andExpect(jsonPath("$.data.quantityDelta").value(-5))
            .andExpect(jsonPath("$.data.referenceType").value("inventory_transaction"))
            .andExpect(jsonPath("$.data.referenceId").value(receiptId))
            .andExpect(jsonPath("$.data.note").value("Received twice by mistake"));

        reverse(receiptId, "{\"requestId\":\"" + key() + "\",\"reason\":\"again\"}").andExpect(status().isConflict());
        assertThat(stock().getQuantity()).isEqualByComparingTo("10");
        assertThat(inventoryTransactionRepository.findAllByInventoryIdOrderByCreatedAtDesc(inventoryId)).hasSize(2);
    }

    @Test
    void reversingAReceiptWhoseStockWasUsedIsRefused() throws Exception {
        String receipt = move("{\"transactionType\":\"receipt\",\"quantity\":5,\"requestId\":\"" + key() + "\"}")
            .andReturn().getResponse().getContentAsString();
        move("{\"transactionType\":\"issue\",\"quantity\":12,\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());

        reverse(id(receipt), "{\"requestId\":\"" + key() + "\",\"reason\":\"wrong receipt\"}")
            .andExpect(status().isConflict());
        assertThat(stock().getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void quarantinedStockCannotBeIssuedUntilReleased() throws Exception {
        move("{\"transactionType\":\"quarantine\",\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());

        move("{\"transactionType\":\"issue\",\"quantity\":1,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This stock is quarantined. Release it before using it."));
        // Receiving more into quarantined stock is still allowed.
        move("{\"transactionType\":\"receipt\",\"quantity\":1,\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());

        move("{\"transactionType\":\"unquarantine\",\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());
        move("{\"transactionType\":\"issue\",\"quantity\":1,\"requestId\":\"" + key() + "\"}").andExpect(status().isOk());
        assertThat(stock().getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    void adjustmentNeedsADirectionAndCannotGoNegative() throws Exception {
        move("{\"transactionType\":\"adjustment\",\"quantity\":1,\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isBadRequest());
        move("{\"transactionType\":\"adjustment\",\"quantity\":11,\"direction\":\"decrease\",\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isConflict());
        move("{\"transactionType\":\"adjustment\",\"quantity\":2,\"direction\":\"increase\",\"requestId\":\"" + key() + "\"}")
            .andExpect(status().isOk());
        assertThat(stock().getQuantity()).isEqualByComparingTo("12");
    }

    private ResultActions move(String fields) throws Exception {
        String body = "{\"inventoryId\":\"" + inventoryId + "\"," + fields.substring(1);
        return mockMvc.perform(post("/inventory-transactions")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions reverse(String transactionId, String body) throws Exception {
        return mockMvc.perform(post("/inventory-transactions/" + transactionId + "/reversal")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private Inventory stock() {
        return inventoryRepository.findById(inventoryId).orElseThrow();
    }

    private String id(String responseBody) throws Exception {
        JsonNode node = objectMapper.readTree(responseBody);
        return node.path("data").path("inventoryTransactionId").asText();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
