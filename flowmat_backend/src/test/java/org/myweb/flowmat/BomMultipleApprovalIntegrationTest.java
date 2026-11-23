package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** Approved BOM revisions side by side for separate periods (docs/domain/multi-level-bom.md M1-M3) against real Postgres. */
@AutoConfigureMockMvc
class BomMultipleApprovalIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void approvalKeepsTheOtherRevisionAndRefusesAnOverlap() throws Exception {
        String product = item("unit_ea");
        String flour = item("unit_kg");
        String sugar = item("unit_kg");
        String v1 = approve(draftBom(product, flour, "kg"));
        String v2 = revision(v1, sugar, "kg");
        call(post("/boms/" + v2 + "/submit")).andExpect(status().isOk());

        // Both periods are open, so they overlap: approval refuses and v1 stays approved (M1, M2).
        call(post("/boms/" + v2 + "/approve"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("overlaps approved v1 (open → open)")));
        call(get("/boms/" + v1)).andExpect(jsonPath("$.data.bomStatus").value("approved"));
        call(get("/boms/" + v2)).andExpect(jsonPath("$.data.bomStatus").value("pending_approval"));

        // The owner ends v1; v2 goes back to draft for its own start; then both stay approved side by side.
        period(v1, null, "2030-01-31");
        call(post("/boms/" + v2 + "/reject"), "{\"note\":\"Give it a start date\"}").andExpect(status().isOk());
        period(v2, "2030-02-01", null);
        call(post("/boms/" + v2 + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + v2 + "/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomStatus").value("approved"))
            .andExpect(jsonPath("$.data.effectiveFrom").value("2030-02-01"));
        call(get("/boms/" + v1))
            .andExpect(jsonPath("$.data.bomStatus").value("approved"))
            .andExpect(jsonPath("$.data.effectiveTo").value("2030-01-31"));
        effective(product, "2030-01-31").andExpect(jsonPath("$.data.bomId").value(v1));
        effective(product, "2030-02-01").andExpect(jsonPath("$.data.bomId").value(v2));
    }

    @Test
    void undatedViewsUseTodaysRevisionAndLoopsCountEveryRevision() throws Exception {
        // Ten days either side, so the project's time zone cannot move "today" across a boundary.
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        String until = today.minusDays(10).toString();
        String from = today.minusDays(9).toString();
        String flour = item("unit_kg");
        String sugar = item("unit_kg");
        String dough = item("unit_ea");
        String bread = item("unit_ea");
        String cake = item("unit_ea");

        // Dough was made from flour until ten days ago and from sugar since.
        String oldDough = draftBom(dough, flour, "kg");
        period(oldDough, null, until);
        approve(oldDough);
        String newDough = revision(oldDough, sugar, "kg");
        period(newDough, from, null);
        approve(newDough);
        String breadBom = approve(draftBom(bread, dough, "ea"));

        // Exploding bread today goes through today's dough revision (M3).
        call(get("/boms/" + breadBom + "/explosion").param("quantity", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + dough + "')].bomId").value(hasItem(newDough)))
            .andExpect(jsonPath("$.data.materials[*].itemId").value(hasItem(sugar)))
            .andExpect(jsonPath("$.data.materials[*].itemId").value(not(hasItem(flour))));

        // Asked for a day in the old period, the views use that day's dough revision instead (M7).
        String past = today.minusDays(20).toString();
        call(get("/boms/" + breadBom + "/explosion").param("quantity", "1").param("on", past))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.asOf").value(past))
            .andExpect(jsonPath("$.data.lines[?(@.itemId == '" + dough + "')].bomId").value(hasItem(oldDough)))
            .andExpect(jsonPath("$.data.materials[*].itemId").value(hasItem(flour)));
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", flour).param("on", past))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.topProducts[*].itemId").value(hasItem(bread)));
        call(get("/boms/where-used/all-levels").param("projectId", DEMO_PROJECT).param("itemId", flour))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uses.length()").value(0));
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT).param("on", past))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.asOf").value(past));
        call(get("/boms/cost-rollup").param("projectId", DEMO_PROJECT).param("on", "2030-13-01"))
            .andExpect(status().isBadRequest());

        // Cake was made from bread until ten days ago. A bread revision made from cake would loop through that past
        // revision, so it is refused although today's cake revision does not use bread.
        String oldCake = draftBom(cake, bread, "ea");
        period(oldCake, null, until);
        approve(oldCake);
        String newCake = revision(oldCake, sugar, "kg");
        period(newCake, from, null);
        approve(newCake);
        String breadFromCake = revision(breadBom, dough, "ea");
        call(post("/boms/" + breadFromCake + "/lines"), line(cake, "ea")).andExpect(status().isOk());
        call(post("/boms/" + breadFromCake + "/submit"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("cannot contain itself")));
    }

    @Test
    void theReplacementHelperEndsTheEarlierRevisionTheDayBeforeInOneStep() throws Exception {
        String product = item("unit_ea");
        String flour = item("unit_kg");
        String sugar = item("unit_kg");
        String v1 = approve(draftBom(product, flour, "kg"));
        String v2 = revision(v1, sugar, "kg");
        call(post("/boms/" + v2 + "/submit")).andExpect(status().isOk());
        // Without a start day there is no day before to end v1 on.
        call(post("/boms/" + v2 + "/approve"), "{\"endEarlier\":true}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("has no start day")));
        call(post("/boms/" + v2 + "/reject"), "{\"note\":\"Give it a start date\"}").andExpect(status().isOk());
        period(v2, "2030-02-01", null);
        call(post("/boms/" + v2 + "/submit")).andExpect(status().isOk());

        // Plain approval still refuses the overlap (M2); with the helper it ends v1 on 2030-01-31 in the same step.
        call(post("/boms/" + v2 + "/approve")).andExpect(status().isConflict());
        call(post("/boms/" + v2 + "/approve"), "{\"endEarlier\":true,\"note\":\"Sugar from February\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bomStatus").value("approved"));
        call(get("/boms/" + v1)).andExpect(jsonPath("$.data.bomStatus").value("approved"))
            .andExpect(jsonPath("$.data.effectiveTo").value("2030-01-31"));
        call(get("/boms/" + v1 + "/effectivity"))
            .andExpect(jsonPath("$.data.history[0].effectiveTo").value("2030-01-31"))
            .andExpect(jsonPath("$.data.history[0].reason").value("Ended by approving v2, which starts 2030-02-01."));
        effective(product, "2030-01-31").andExpect(jsonPath("$.data.bomId").value(v1));
        effective(product, "2030-02-01").andExpect(jsonPath("$.data.bomId").value(v2));

        // A revision that starts on or after the new one's start is not ended for it: all or nothing.
        String v3 = revision(v2, flour, "kg");
        period(v3, "2030-01-15", "2030-03-31");
        call(post("/boms/" + v3 + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + v3 + "/approve"), "{\"endEarlier\":true}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("cannot be ended before 2030-01-15")));
        call(get("/boms/" + v1)).andExpect(jsonPath("$.data.effectiveTo").value("2030-01-31"));
    }

    // ---- helpers ----

    private String draftBom(String product, String material, String unit) throws Exception {
        String bomId = data(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + product
            + "\",\"bomName\":\"Dated BOM\",\"baseQuantity\":1,\"baseUnit\":\"ea\"}").andExpect(status().isOk()))
            .path("bomId").asText();
        call(post("/boms/" + bomId + "/lines"), line(material, unit)).andExpect(status().isOk());
        return bomId;
    }

    /** A new draft revision of the source whose only material is the one given. */
    private String revision(String sourceId, String material, String unit) throws Exception {
        String bomId = data(call(post("/boms/" + sourceId + "/revisions")).andExpect(status().isOk())).path("bomId").asText();
        for (JsonNode line : data(call(get("/boms/" + bomId))).path("lines")) {
            call(delete("/boms/" + bomId + "/lines/" + line.path("bomLineId").asText())).andExpect(status().isOk());
        }
        call(post("/boms/" + bomId + "/lines"), line(material, unit)).andExpect(status().isOk());
        return bomId;
    }

    private String approve(String bomId) throws Exception {
        if ("draft".equals(data(call(get("/boms/" + bomId))).path("bomStatus").asText())) {
            call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        }
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
        return bomId;
    }

    private void period(String bomId, String from, String to) throws Exception {
        int version = data(call(get("/boms/" + bomId + "/effectivity"))).path("periodVersion").asInt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID());
        body.put("effectiveFrom", from);
        body.put("effectiveTo", to);
        body.put("expectedPeriodVersion", version);
        body.put("reason", "Engineering change");
        call(post("/boms/" + bomId + "/effectivity"), objectMapper.writeValueAsString(body)).andExpect(status().isOk());
    }

    private ResultActions effective(String product, String day) throws Exception {
        return call(get("/boms/effective").param("projectId", DEMO_PROJECT).param("targetItemId", product).param("on", day))
            .andExpect(status().isOk());
    }

    /** One of the material, in a unit of its own (kg or ea). */
    private static String line(String itemId, String unit) {
        return "{\"childItemId\":\"" + itemId + "\",\"quantity\":1,\"unit\":\"" + unit + "\"}";
    }

    private String item(String unitId) {
        String id = "itm-mab-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
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

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}
