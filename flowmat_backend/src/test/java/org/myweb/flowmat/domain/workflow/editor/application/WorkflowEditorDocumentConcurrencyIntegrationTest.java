package org.myweb.flowmat.domain.workflow.editor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.IntegrationTestSupport;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WorkflowEditorDocumentConcurrencyIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @Autowired ObjectMapper mapper;

    @Test
    void firstDocumentSaveSerializesAndRejectsStaleWriter() throws Exception {
        String bearer = "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER);
        String create = mockMvc.perform(post("/workflows")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Concurrency "
                    + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String workflowId = mapper.readTree(create).path("data").path("workflowId").asText();
        assertThat(workflowId).isNotBlank();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> save(workflowId, bearer, start));
            Future<Integer> second = pool.submit(() -> save(workflowId, bearer, start));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        mockMvc.perform(get("/workflows/{workflowId}/editor-document", workflowId)
                .header("Authorization", bearer))
            .andExpect(status().isOk());
    }

    private int save(String workflowId, String bearer, CountDownLatch start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return mockMvc.perform(put("/workflows/{workflowId}/editor-document", workflowId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"elements\":[]}"))
            .andReturn().getResponse().getStatus();
    }
}
