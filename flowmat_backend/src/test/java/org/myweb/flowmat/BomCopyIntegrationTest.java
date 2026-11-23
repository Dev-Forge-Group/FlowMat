package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Copying a BOM to another product (docs/domain/inventory-bom-lot-contract.md §5 "BOM 복사") against real Postgres. */
@AutoConfigureMockMvc
class BomCopyIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void anotherProductStartsFromTheSameMaterialsAsADraft() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String white = item("BC-WHITE-" + tag, "unit_ea");
        String brown = item("BC-BROWN-" + tag, "unit_ea");
        String flour = item("BC-FLOUR-" + tag, "unit_kg");
        String salt = item("BC-SALT-" + tag, "unit_kg");
        String bomId = id(call(post("/boms"), json(Map.of("projectId", DEMO_PROJECT, "targetItemId", white, "bomName", "White " + tag,
            "baseQuantity", 10, "baseUnit", "ea"))), "bomId");
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", flour, "quantity", 5, "unit", "kg"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/lines"), json(Map.of("childItemId", salt, "quantity", 200, "unit", "g"))).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/submit")).andExpect(status().isOk());
        call(post("/boms/" + bomId + "/approve")).andExpect(status().isOk());

        String copyId = id(call(post("/boms/" + bomId + "/copy"), json(Map.of("targetItemId", brown, "bomName", "Brown " + tag))), "bomId");
        call(get("/boms/" + copyId))
            .andExpect(jsonPath("$.data.targetItemId").value(brown))
            .andExpect(jsonPath("$.data.bomName").value("Brown " + tag))
            .andExpect(jsonPath("$.data.bomVersion").value(1))
            .andExpect(jsonPath("$.data.bomStatus").value("draft"))
            .andExpect(jsonPath("$.data.baseQuantity").value(10.0))
            .andExpect(jsonPath("$.data.lines.length()").value(2))
            .andExpect(jsonPath("$.data.lines[0].childItemId").value(flour))
            .andExpect(jsonPath("$.data.lines[1].unit").value("g"))
            .andExpect(jsonPath("$.data.note").value(containsString("Copied from White " + tag + " v1")));
        // The source is untouched.
        call(get("/boms/" + bomId)).andExpect(jsonPath("$.data.bomStatus").value("approved"));

        // Not to itself, not to one of its materials, not to a product that already has a BOM.
        call(post("/boms/" + bomId + "/copy"), json(Map.of("targetItemId", white))).andExpect(status().isBadRequest());
        call(post("/boms/" + bomId + "/copy"), json(Map.of("targetItemId", flour))).andExpect(status().isBadRequest());
        call(post("/boms/" + bomId + "/copy"), json(Map.of("targetItemId", brown))).andExpect(status().isConflict());
        mockMvc.perform(post("/boms/" + bomId + "/copy").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("targetItemId", item("BC-OTHER-" + tag, "unit_ea"))))
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("bom-copy-outsider")))
            .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private String item(String code, String unitId) throws Exception {
        return id(call(post("/items"), json(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase(),
            "unitId", unitId))), "itemId");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String id(ResultActions result, String field) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path(field).asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
