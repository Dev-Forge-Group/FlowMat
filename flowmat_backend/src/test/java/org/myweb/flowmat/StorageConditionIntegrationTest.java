package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A place's storage condition (docs/domain/storage-location.md SL1-SL4) on real Postgres. A new project of its own, so the
 * demo project keeps free-text locations for the other tests.
 */
@AutoConfigureMockMvc
class StorageConditionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aPlaceKeepsAFreeTextStorageConditionThatTheListShows() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String project = data(call(post("/projects"), Map.of("projectName", "Storage " + tag, "ownerId", DEMO_OWNER))).path("projectId").asText();
        JsonNode cold = data(call(post("/storage-locations"), Map.of("projectId", project, "locationCode", "COLD", "locationType", "warehouse",
            "storageCondition", "  Chilled   2-8 C ")));
        assertEquals("Chilled 2-8 C", cold.path("storageCondition").asText());
        JsonNode bin = data(call(post("/storage-locations"), Map.of("projectId", project, "locationCode", "B1", "locationType", "bin",
            "parentLocationId", cold.path("locationId").asText())));
        assertTrue(bin.path("storageCondition").isNull(), bin.toString());
        String binPath = "/storage-locations/" + bin.path("locationId").asText();

        // Given, it is set; left out, it stays; empty, it is cleared.
        assertEquals("Frozen", data(call(put(binPath), Map.of("storageCondition", "Frozen"))).path("storageCondition").asText());
        assertEquals("Frozen", data(call(put(binPath), Map.of("locationName", "Freezer bin"))).path("storageCondition").asText());
        JsonNode list = data(call(get("/storage-locations").param("projectId", project), null));
        assertEquals(List.of("Chilled 2-8 C", "Frozen"),
            List.of(list.get(0).path("storageCondition").asText(), list.get(1).path("storageCondition").asText()));
        assertTrue(data(call(put(binPath), Map.of("storageCondition", ""))).path("storageCondition").isNull());

        // Text that cannot be stored or is too long is refused, and the place keeps what it had.
        call(put(binPath), Map.of("storageCondition", "x".repeat(101))).andExpect(status().isBadRequest());
        call(put(binPath), Map.of("storageCondition", "Cold\u0000")).andExpect(status().isBadRequest());
        call(post("/storage-locations"), Map.of("projectId", project, "locationCode", "B2", "locationType", "bin",
            "storageCondition", "y".repeat(101))).andExpect(status().isBadRequest());
        assertTrue(data(call(get("/storage-locations").param("projectId", project), null)).get(1).path("storageCondition").isNull());
        callAs("unrelated-user", put(binPath), Map.of("storageCondition", "Ambient")).andExpect(status().isForbidden());
    }

    private JsonNode data(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(200, response.getStatus(), body.path("message").asText());
        return body.path("data");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return callAs(DEMO_OWNER, request, body);
    }

    private ResultActions callAs(String userId, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(userId));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
        return mockMvc.perform(request);
    }
}
