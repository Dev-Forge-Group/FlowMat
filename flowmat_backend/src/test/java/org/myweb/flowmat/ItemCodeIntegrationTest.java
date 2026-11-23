package org.myweb.flowmat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Item codes are unique among a project's active items (docs/domain/item-import.md) against real Postgres. */
@AutoConfigureMockMvc
class ItemCodeIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void aCodeInUseIsRefusedUntilItsItemIsDeleted() throws Exception {
        String code = "CODE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String first = objectMapper.readTree(create(code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data").path("itemId").asText();

        create(code)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(containsString("Item code " + code + " already exists")));
        // A deleted item frees its code.
        mockMvc.perform(delete("/items/" + first).header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)))
            .andExpect(status().isOk());
        create(code).andExpect(status().isOk());
    }

    @Test
    void aCodeCanBeRenamedToOneNotInUse() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String first = objectMapper.readTree(create("RN-A-" + tag).andReturn().getResponse().getContentAsString()).path("data").path("itemId").asText();
        create("RN-B-" + tag).andExpect(status().isOk());

        rename(first, "RN-B-" + tag).andExpect(status().isConflict());
        rename(first, "RN-C-" + tag)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.itemCode").value("RN-C-" + tag));
        // Its own code again is no change, and the old code is free now.
        rename(first, "RN-C-" + tag).andExpect(status().isOk());
        create("RN-A-" + tag).andExpect(status().isOk());
    }

    private ResultActions rename(String itemId, String code) throws Exception {
        return mockMvc.perform(put("/items/" + itemId).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("itemCode", code)))
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private ResultActions create(String code) throws Exception {
        return mockMvc.perform(post("/items").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT, "itemCode", code, "itemName", code.toLowerCase())))
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }
}
