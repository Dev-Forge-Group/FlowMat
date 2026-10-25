package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Flow rules read every catalog property of an item, not only what the catalog's view holds
 * (docs/architecture/adr/ADR-018-entity-boundary.md decision 5).
 */
@AutoConfigureMockMvc
class ItemRuleFactsIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void aRuleOnAnItemPropertyOutsideTheViewStillApplies() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String project = call(post("/projects"), Map.of("projectName", "Rule facts " + tag, "ownerId", DEMO_OWNER), 200)
            .path("data").path("projectId").asText();
        String fish = item(project, "RF-FISH-" + tag, "Frozen");
        String salt = item(project, "RF-SALT-" + tag, "Dry");
        for (String itemId : new String[] {fish, salt}) {
            call(post("/flow-rules"), Map.of("projectId", project, "targetType", "item", "targetId", itemId,
                "ruleName", "No frozen stock " + tag, "conditionExpression", "item.storageCondition == 'Frozen'",
                "actionType", "block", "enabledYn", "Y"), 200);
        }

        JsonNode refused = call(post("/inventories"), Map.of("projectId", project, "itemId", fish, "quantity", 5), 400);
        assertEquals("No frozen stock " + tag, refused.path("message").asText(), refused.toString());
        call(post("/inventories"), Map.of("projectId", project, "itemId", salt, "quantity", 5), 200);
    }

    private String item(String projectId, String code, String condition) throws Exception {
        return call(post("/items"), Map.of("projectId", projectId, "itemCode", code, "itemName", code.toLowerCase(),
            "itemType", "material", "unitId", "unit_kg", "details", Map.of("storageCondition", condition)), 200)
            .path("data").path("itemId").asText();
    }

    /** The whole answer, so a refusal's message can be read. */
    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        request.header("Authorization", "Bearer " + jwt.generateAccessToken(DEMO_OWNER));
        request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        JsonNode parsed = mapper.readTree(response.getContentAsString());
        assertEquals(expected, response.getStatus(), parsed.path("message").asText());
        return parsed;
    }
}
