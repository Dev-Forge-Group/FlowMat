package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Drafting a process from a description (docs/architecture/adr/ADR-047-process-draft-from-a-description.md) on a server
 * that has it off, as every test and development server does: signed out 401, an empty description 400, otherwise 503,
 * and the availability the home screen reads is false.
 */
@AutoConfigureMockMvc
class ProcessDraftIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void aServerWithoutDraftingSaysSo() throws Exception {
        mockMvc.perform(post("/process-drafts").contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"melon milk\"}"))
            .andExpect(status().isUnauthorized());
        call("{\"description\":\"  \"}").andExpect(status().isBadRequest());
        call("{\"description\":\"We bottle melon milk.\"}").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("Drafting from a description is not set up on this server."));
        // The home screen asks first, and hides the card here.
        mockMvc.perform(get("/process-drafts/availability")
                .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("draft-" + UUID.randomUUID().toString().substring(0, 8))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false));
    }

    private ResultActions call(String body) throws Exception {
        return mockMvc.perform(post("/process-drafts").contentType(MediaType.APPLICATION_JSON).content(body)
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken("draft-" + UUID.randomUUID().toString().substring(0, 8))));
    }
}
