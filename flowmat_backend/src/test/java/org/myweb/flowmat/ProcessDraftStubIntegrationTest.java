package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;
import org.myweb.flowmat.domain.workflow.application.ProcessDraftModel;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Drafting with a model in place (ADR-047), a stand-in that answers without the network: a checked draft comes back, a
 * draft that fails its checks is a 502, past one person's hourly limit (2 here) a 429, and past the whole server's daily
 * limit (3 here, the cost ceiling) a 429 for anyone.
 */
@AutoConfigureMockMvc
@Import(ProcessDraftStubIntegrationTest.Stub.class)
@TestPropertySource(properties = {"app.ai.process-draft.hourly-limit=2", "app.ai.process-draft.daily-limit=3"})
class ProcessDraftStubIntegrationTest extends IntegrationTestSupport {

    @TestConfiguration
    static class Stub {
        @Bean
        ProcessDraftModel stubProcessDraftModel() {
            return description -> description.contains("broken")
                ? new ProcessDraftResponse("Broken", "", List.of(), List.of(), null)
                : org.myweb.flowmat.domain.workflow.application.ProcessDraftCheckTest.milk();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private StringRedisTemplate redis;

    @Test
    void aCheckedDraftComesBackAndABadOneOrTooManyDoNot() throws Exception {
        redis.delete("ai:process-draft:day:" + LocalDate.now(ZoneOffset.UTC));
        String user = "draft-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(get("/process-drafts/availability").header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true));
        call(user, "We bottle melon milk.").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].name").value("Mix"))
            .andExpect(jsonPath("$.data.target.item").value("MELON-MILK"));
        call(user, "Something broken").andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("The draft did not pass its checks")));
        call(user, "We bottle melon milk.").andExpect(status().isTooManyRequests());

        // Someone else: the server's third draft of the day comes back, the fourth is past the ceiling.
        String other = "draft-" + UUID.randomUUID().toString().substring(0, 8);
        call(other, "We bottle melon milk.").andExpect(status().isOk());
        call(other, "We bottle melon milk.").andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("This server has drafted 3 times today")));
    }

    private ResultActions call(String user, String description) throws Exception {
        return mockMvc.perform(post("/process-drafts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"" + description + "\"}")
            .header("Authorization", "Bearer " + jwtProvider.generateAccessToken(user)));
    }
}
