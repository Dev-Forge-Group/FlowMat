package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WorkflowRevisionIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void publishingFreezesDraftAndRetirementKeepsItsSnapshot() throws Exception {
        String originalName = "Revision draft " + UUID.randomUUID();
        String workflowId = data(post("/workflows")
            .header("Authorization", bearer(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"" + originalName + "\"}"))
            .path("workflowId").asText();
        String processId = data(post("/processes")
            .header("Authorization", bearer(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Original node\"}"))
            .path("processId").asText();

        JsonNode first = data(post("/workflows/" + workflowId + "/revisions")
            .header("Authorization", bearer(DEMO_OWNER)));
        String firstId = first.path("workflowRevisionId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(1, first.path("revisionNo").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(originalName,
            first.path("snapshot").path("workflow").path("workflowName").asText());
        org.junit.jupiter.api.Assertions.assertTrue(first.path("snapshot").path("canvasSnapshot").isObject());
        org.junit.jupiter.api.Assertions.assertTrue(first.path("snapshot").path("simulationConfig").isObject());
        org.junit.jupiter.api.Assertions.assertEquals("Original node",
            first.path("snapshot").path("processes").path(0).path("processName").asText());

        mockMvc.perform(put("/workflows/" + workflowId)
                .header("Authorization", bearer(DEMO_OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowName\":\"Changed draft\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/processes/" + processId)
                .header("Authorization", bearer(DEMO_OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"processName\":\"Changed node\"}"))
            .andExpect(status().isOk());

        JsonNode second = data(post("/workflows/" + workflowId + "/revisions")
            .header("Authorization", bearer(DEMO_OWNER)));
        org.junit.jupiter.api.Assertions.assertEquals(2, second.path("revisionNo").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("Changed draft",
            second.path("snapshot").path("workflow").path("workflowName").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Changed node",
            second.path("snapshot").path("processes").path(0).path("processName").asText());

        mockMvc.perform(get("/workflows/" + workflowId + "/revisions/" + firstId)
                .header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.snapshot.workflow.workflowName").value(originalName))
            .andExpect(jsonPath("$.data.snapshot.processes[0].processName").value("Original node"));
        mockMvc.perform(get("/workflows/" + workflowId + "/revisions")
                .header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].revisionNo").value(2))
            .andExpect(jsonPath("$.data[1].revisionNo").value(1))
            .andExpect(jsonPath("$.data[0].snapshot").doesNotExist());

        mockMvc.perform(post("/workflows/" + workflowId + "/revisions/" + firstId + "/retire")
                .header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("retired"))
            .andExpect(jsonPath("$.data.snapshot.workflow.workflowName").value(originalName));
        mockMvc.perform(post("/workflows/" + workflowId + "/revisions/" + firstId + "/retire")
                .header("Authorization", bearer(DEMO_OWNER)))
            .andExpect(status().isConflict());

        mockMvc.perform(get("/workflows/" + workflowId + "/revisions")
                .header("Authorization", bearer("unrelated-user")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/workflows/" + workflowId + "/revisions")
                .header("Authorization", bearer("unrelated-user")))
            .andExpect(status().isForbidden());
    }

    @Test
    void concurrentPublishersReceiveDistinctRevisionNumbers() throws Exception {
        String workflowId = data(post("/workflows")
            .header("Authorization", bearer(DEMO_OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Parallel "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> publish = () -> {
                start.await();
                return mockMvc.perform(post("/workflows/" + workflowId + "/revisions")
                    .header("Authorization", bearer(DEMO_OWNER)))
                    .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = workers.submit(publish);
            Future<Integer> second = workers.submit(publish);
            start.countDown();
            int firstStatus = first.get();
            int secondStatus = second.get();
            org.junit.jupiter.api.Assertions.assertTrue(firstStatus == 200 || firstStatus == 409);
            org.junit.jupiter.api.Assertions.assertTrue(secondStatus == 200 || secondStatus == 409);
            org.junit.jupiter.api.Assertions.assertTrue(firstStatus == 200 || secondStatus == 200);
            if (firstStatus == 409 || secondStatus == 409) {
                mockMvc.perform(post("/workflows/" + workflowId + "/revisions")
                        .header("Authorization", bearer(DEMO_OWNER)))
                    .andExpect(status().isOk());
            }
            mockMvc.perform(get("/workflows/" + workflowId + "/revisions")
                    .header("Authorization", bearer(DEMO_OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].revisionNo").value(2))
                .andExpect(jsonPath("$.data[1].revisionNo").value(1));
        } finally {
            workers.shutdownNow();
        }
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
        throws Exception {
        String json = mockMvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("data");
    }

    private String bearer(String userId) {
        return "Bearer " + jwtProvider.generateAccessToken(userId);
    }
}
