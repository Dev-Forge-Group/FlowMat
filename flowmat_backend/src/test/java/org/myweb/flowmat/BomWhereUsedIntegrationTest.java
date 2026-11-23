package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** BOM where-used against real Postgres. */
@AutoConfigureMockMvc
class BomWhereUsedIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ItemRepository itemRepository;

    @Test
    void whereUsedListsApprovedThenDraftsThenRetiredRevisions() throws Exception {
        String flour = item("unit_kg");
        String bread = item("unit_ea");
        String cake = item("unit_ea");

        String breadV1 = bom(bread, "Bread");
        line(breadV1, flour, "20");
        approve(breadV1);
        String breadV2 = id(call(post("/boms/" + breadV1 + "/revisions")).andExpect(status().isOk()), "bomId");
        // Approval retires nothing (docs/domain/multi-level-bom.md M1), so v1 is retired first.
        call(post("/boms/" + breadV1 + "/retire")).andExpect(status().isOk());
        approve(breadV2);
        String cakeDraft = bom(cake, "Cake");
        line(cakeDraft, flour, "7.5");

        call(get("/boms/where-used?projectId=" + DEMO_PROJECT + "&itemId=" + flour))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].bomId").value(breadV2))
            .andExpect(jsonPath("$.data[0].bomStatus").value("approved"))
            .andExpect(jsonPath("$.data[0].bomVersion").value(2))
            .andExpect(jsonPath("$.data[0].targetItemId").value(bread))
            .andExpect(jsonPath("$.data[0].lineQuantity").value(20))
            .andExpect(jsonPath("$.data[1].bomId").value(cakeDraft))
            .andExpect(jsonPath("$.data[1].bomStatus").value("draft"))
            .andExpect(jsonPath("$.data[1].lineQuantity").value(7.5))
            .andExpect(jsonPath("$.data[2].bomId").value(breadV1))
            .andExpect(jsonPath("$.data[2].bomStatus").value("retired"));

        // An item nothing uses, a missing item, and someone outside the project.
        call(get("/boms/where-used?projectId=" + DEMO_PROJECT + "&itemId=" + cake))
            .andExpect(jsonPath("$.data.length()").value(0));
        call(get("/boms/where-used").param("projectId", DEMO_PROJECT).param("itemId", "")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/boms/where-used?projectId=" + DEMO_PROJECT + "&itemId=" + flour)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("where-used-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private String bom(String targetItemId, String name) throws Exception {
        return id(call(post("/boms"), "{\"projectId\":\"" + DEMO_PROJECT + "\",\"targetItemId\":\"" + targetItemId
            + "\",\"bomName\":\"" + name + "\",\"baseQuantity\":100,\"baseUnit\":\"ea\"}").andExpect(status().isOk()), "bomId");
    }

    private void line(String bomId, String itemId, String quantity) throws Exception {
        call(post("/boms/" + bomId + "/lines"), "{\"childItemId\":\"" + itemId + "\",\"quantity\":" + quantity + ",\"unit\":\"kg\"}")
            .andExpect(status().isOk());
    }

    private void approve(String bomId) throws Exception {
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());
    }

    private String item(String unitId) {
        String id = "itm-wu-" + suffix();
        Item item = new Item();
        item.setItemId(id);
        item.setProjectId(DEMO_PROJECT);
        item.setItemCode(id.toUpperCase());
        item.setItemName(id);
        item.setItemType("material");
        item.setResourceCategory("material");
        item.setUnitId(unitId);
        item.setItemStatus("active");
        item.setLotManageYn("N");
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
