package org.myweb.flowmat;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Which nonconformity holds a defect, for the run and LOT defect lists (docs/domain/nonconformity.md). */
@AutoConfigureMockMvc
class NonconformityDefectLinkIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void defectsGatheredOnAnNcrShowItsNumberUntilItIsCancelled() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String item = id(call(post("/items"), Map.of("projectId", DEMO_PROJECT, "itemCode", "LINK-" + tag,
            "itemName", "link " + tag, "itemType", "material", "unitId", "unit_kg")), "itemId");
        String crack = defect(item, "Crack " + tag);
        String dent = defect(item, "Dent " + tag);
        String scratch = defect(item, "Scratch " + tag);
        links().andExpect(jsonPath("$.data[?(@.defectLogId == '" + crack + "')]").value(empty()));

        JsonNode ncr = data(call(post("/nonconformities"), Map.of("projectId", DEMO_PROJECT, "title", "Housings " + tag,
            "defectLogIds", List.of(crack, dent))));
        String ncrNo = ncr.path("ncrNo").asText();
        links()
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + crack + "')].ncrNo").value(contains(ncrNo)))
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + crack + "')].status").value(contains("open")))
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + dent + "')].nonconformityId")
                .value(contains(ncr.path("nonconformityId").asText())))
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + scratch + "')]").value(empty()));

        // Cancelling lets the defects go, so they can be gathered again.
        call(post("/nonconformities/" + ncr.path("nonconformityId").asText() + "/cancel"), Map.of("note", "Raised twice"))
            .andExpect(status().isOk());
        links()
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + crack + "')]").value(empty()))
            .andExpect(jsonPath("$.data[?(@.defectLogId == '" + dent + "')]").value(empty()));

        mockMvc.perform(get("/nonconformities/defect-links").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("unrelated-user")))
            .andExpect(status().isForbidden());
    }

    private ResultActions links() throws Exception {
        return mockMvc.perform(get("/nonconformities/defect-links").param("projectId", DEMO_PROJECT)
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk());
    }

    private String defect(String itemId, String type) throws Exception {
        return id(call(post("/defects"), Map.of("projectId", DEMO_PROJECT, "itemId", itemId, "quantity", 2,
            "defectType", type, "severity", "major")), "defectLogId");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private String id(ResultActions result, String field) throws Exception {
        return data(result).path(field).asText();
    }
}
