package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
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

/** Equipment details (docs/domain/equipment.md) round-trip through the existing equipment columns on real Postgres. */
@AutoConfigureMockMvc
class EquipmentDetailsIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void detailsAreStoredReplacedWholeAndKeptWhenOmitted() throws Exception {
        String code = "EQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Map<String, Object> details = new HashMap<>();
        details.put("manufacturer", " Acme ");
        details.put("modelName", "M-200");
        details.put("serialNo", "SN-1");
        details.put("capacityPerHour", 120.5);
        details.put("powerKwh", 3.75);
        details.put("waterLiter", 0);
        details.put("location", "Line 1");
        String id = objectMapper.readTree(send(post("/equipments"),
                Map.of("projectId", DEMO_PROJECT, "equipmentCode", code, "equipmentName", "Mixer", "equipmentType", "machine", "details", details))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.manufacturer").value("Acme"))
            .andReturn().getResponse().getContentAsString()).path("data").path("equipmentId").asText();

        // Read back from the table, not from the saved entity.
        mockMvc.perform(get("/equipments/" + id).header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.modelName").value("M-200"))
            .andExpect(jsonPath("$.data.details.capacityPerHour").value(120.5))
            .andExpect(jsonPath("$.data.details.powerKwh").value(3.75))
            .andExpect(jsonPath("$.data.details.waterLiter").value(0.0))
            .andExpect(jsonPath("$.data.details.location").value("Line 1"));

        // Without details only the named fields change.
        send(put("/equipments/" + id), Map.of("equipmentName", "Big mixer"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.serialNo").value("SN-1"));

        // With details every field is replaced: missing ones are cleared.
        send(put("/equipments/" + id), Map.of("details", Map.of("location", "Line 2", "powerKwh", 4)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/equipments/" + id).header("Authorization", bearer()))
            .andExpect(jsonPath("$.data.equipmentName").value("Big mixer"))
            .andExpect(jsonPath("$.data.details.location").value("Line 2"))
            .andExpect(jsonPath("$.data.details.powerKwh").value(4.0))
            .andExpect(jsonPath("$.data.details.manufacturer").value(nullValue()))
            .andExpect(jsonPath("$.data.details.capacityPerHour").value(nullValue()));
    }

    @Test
    void negativeOrTooLargeNumbersAreRefused() throws Exception {
        String id = objectMapper.readTree(send(post("/equipments"),
                Map.of("projectId", DEMO_PROJECT, "equipmentName", "Oven", "equipmentType", "machine"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.details.location").value(nullValue()))
            .andReturn().getResponse().getContentAsString()).path("data").path("equipmentId").asText();

        send(put("/equipments/" + id), Map.of("details", Map.of("capacityPerHour", -1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("capacityPerHour")));
        // power_kwh is numeric(10,4): six whole digits at most.
        send(put("/equipments/" + id), Map.of("details", Map.of("powerKwh", 1234567)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("powerKwh takes at most 6 whole digits")));
        send(put("/equipments/" + id), Map.of("details", Map.of("waterLiter", 0.12345)))
            .andExpect(status().isBadRequest());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)).header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
    }
}
