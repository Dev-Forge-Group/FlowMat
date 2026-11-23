package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Equipment status history (docs/domain/equipment.md "상태 이력") against real Postgres. */
@AutoConfigureMockMvc
class EquipmentStatusHistoryIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void eachStatusChangeIsKeptWithItsNote() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String press = id(call(post("/equipments"), json(Map.of("projectId", DEMO_PROJECT, "equipmentCode", "STATUS-" + tag,
            "equipmentName", "status press", "equipmentType", "press"))), "equipmentId");
        String path = "/equipments/" + press;

        call(put(path), json(Map.of("equipmentStatus", "maintenance", "statusNote", " Bearing replaced "))).andExpect(status().isOk());
        // The same status again, and a change that leaves the status alone, keep nothing.
        call(put(path), json(Map.of("equipmentStatus", "maintenance", "statusNote", "again"))).andExpect(status().isOk());
        call(put(path), json(Map.of("equipmentName", "renamed press", "statusNote", "no status change"))).andExpect(status().isOk());
        call(put(path), json(Map.of("equipmentStatus", "Active"))).andExpect(status().isOk());

        call(get(path + "/status-history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].previousStatus").value("maintenance"))
            .andExpect(jsonPath("$.data[0].equipmentStatus").value("active"))
            .andExpect(jsonPath("$.data[0].note").doesNotExist())
            .andExpect(jsonPath("$.data[1].previousStatus").value("active"))
            .andExpect(jsonPath("$.data[1].equipmentStatus").value("maintenance"))
            .andExpect(jsonPath("$.data[1].note").value("Bearing replaced"))
            .andExpect(jsonPath("$.data[2].previousStatus").doesNotExist())
            .andExpect(jsonPath("$.data[2].equipmentStatus").value("active"))
            .andExpect(jsonPath("$.data[2].changedBy").value(DEMO_OWNER));

        mockMvc.perform(get(path + "/status-history").header("Authorization", "Bearer " + jwtProvider.generateAccessToken("status-outsider")))
            .andExpect(status().isForbidden());
        call(get("/equipments/no-such-equipment/status-history")).andExpect(status().isNotFound());
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
