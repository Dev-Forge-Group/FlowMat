package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
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

/** Item details (docs/domain/item-details.md) through the existing item columns on real Postgres. */
@AutoConfigureMockMvc
class ItemDetailsIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void detailsAreStoredReplacedWholeAndKeptWhenOmitted() throws Exception {
        String tag = tag();
        Map<String, Object> details = new HashMap<>();
        details.put("itemGroup", " flour ");
        details.put("spec", "T55, 25 kg bag");
        details.put("barcode", "880" + tag);
        details.put("sku", "SKU-" + tag);
        details.put("storageCondition", "Dry, below 25 C");
        details.put("description", "Bread flour");
        String id = idOf(create("DT-" + tag, details)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.itemGroup").value("flour")));

        mockMvc.perform(get("/items/" + id).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.details.spec").value("T55, 25 kg bag"))
            .andExpect(jsonPath("$.data.details.barcode").value("880" + tag))
            .andExpect(jsonPath("$.data.details.storageCondition").value("Dry, below 25 C"))
            .andExpect(jsonPath("$.data.details.description").value("Bread flour"));

        // Without details only the named fields change.
        send(put("/items/" + id), Map.of("itemName", "renamed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.sku").value("SKU-" + tag));

        // With details every field is replaced: missing ones are cleared.
        send(put("/items/" + id), Map.of("details", Map.of("barcode", "880" + tag, "itemGroup", "baking")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/items/" + id).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.itemName").value("renamed"))
            .andExpect(jsonPath("$.data.details.itemGroup").value("baking"))
            .andExpect(jsonPath("$.data.details.barcode").value("880" + tag))
            .andExpect(jsonPath("$.data.details.spec").value(nullValue()))
            .andExpect(jsonPath("$.data.details.description").value(nullValue()));
    }

    @Test
    void aBarcodeBelongsToOneActiveItem() throws Exception {
        String tag = tag();
        String first = idOf(create("BC-A-" + tag, Map.of("barcode", "990" + tag)).andExpect(status().isOk()));
        String second = idOf(create("BC-B-" + tag, null).andExpect(status().isOk()));

        create("BC-C-" + tag, Map.of("barcode", "990" + tag))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Barcode 990" + tag + " is already used by item BC-A-" + tag)));
        send(put("/items/" + second), Map.of("details", Map.of("barcode", " 990" + tag + " ")))
            .andExpect(status().isConflict());
        // Its own barcode again is no conflict.
        send(put("/items/" + first), Map.of("details", Map.of("barcode", "990" + tag, "sku", "x")))
            .andExpect(status().isOk());
        // A deleted item frees its barcode.
        mockMvc.perform(delete("/items/" + first).header("Authorization", bearer())).andExpect(status().isOk());
        send(put("/items/" + second), Map.of("details", Map.of("barcode", "990" + tag)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.barcode").value("990" + tag));
        // Too long for the column: 400 naming the field.
        send(put("/items/" + second), Map.of("details", Map.of("spec", "x".repeat(201))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("spec takes at most 200")));
    }

    @Test
    void theImportMergesDetailsAndGuardsBarcodes() throws Exception {
        String tag = tag();
        String held = "IM-A-" + tag;
        create(held, Map.of("itemGroup", "g", "spec", "old spec", "barcode", "770" + tag)).andExpect(status().isOk());

        Map<String, String> newSpec = Map.of("itemCode", held, "spec", "new spec");
        Map<String, String> fresh = Map.of("itemCode", "IM-B-" + tag, "itemName", "b", "barcode", "771" + tag, "itemGroup", "flour");
        importRows(false, newSpec, fresh,
                Map.of("itemCode", "IM-C-" + tag, "itemName", "c", "barcode", "770" + tag),
                Map.of("itemCode", "IM-D-" + tag, "itemName", "d", "barcode", "771" + tag))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.applied").value(false))
            .andExpect(jsonPath("$.data.errors").value(2))
            .andExpect(jsonPath("$.data.rows[0].message").value("spec"))
            .andExpect(jsonPath("$.data.rows[2].message").value(containsString("already used by item " + held)))
            .andExpect(jsonPath("$.data.rows[3].message").value(containsString("more than once")));

        importRows(false, newSpec, fresh).andExpect(jsonPath("$.data.applied").value(true));
        String heldItem = "$.data[?(@.itemCode == '" + held + "')]";
        String freshItem = "$.data[?(@.itemCode == 'IM-B-" + tag + "')]";
        mockMvc.perform(get("/items").param("projectId", DEMO_PROJECT).header("Authorization", bearer()))
            .andExpect(jsonPath(heldItem + ".details.spec").value(org.hamcrest.Matchers.hasItem("new spec")))
            // Blank cells kept what was stored.
            .andExpect(jsonPath(heldItem + ".details.barcode").value(org.hamcrest.Matchers.hasItem("770" + tag)))
            .andExpect(jsonPath(heldItem + ".details.itemGroup").value(org.hamcrest.Matchers.hasItem("g")))
            .andExpect(jsonPath(freshItem + ".details.itemGroup").value(org.hamcrest.Matchers.hasItem("flour")));

        importRows(true, newSpec).andExpect(jsonPath("$.data.unchanged").value(1));
    }

    @SafeVarargs
    private ResultActions importRows(boolean dryRun, Map<String, String>... rows) throws Exception {
        return send(post("/items/import"), Map.of("projectId", DEMO_PROJECT, "dryRun", dryRun, "rows", java.util.List.of(rows)));
    }

    private ResultActions create(String code, Map<String, Object> details) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase()));
        if (details != null) {
            body.put("details", details);
        }
        return send(post("/items"), body);
    }

    private String idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path("itemId").asText();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
    }

    private static String tag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
