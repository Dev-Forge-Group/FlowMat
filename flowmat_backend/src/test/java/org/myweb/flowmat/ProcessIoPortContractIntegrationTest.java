package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ProcessIoPortContractIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void portContractIsStoredAndFrozenInPublishedRevision() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port contract "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String processId = data(post("/processes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Mixer\"}"))
            .path("processId").asText();
        JsonNode port = data(post("/process-ios")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"input\",\"quantity\":2,\"unit\":\"kg\","
                + "\"role\":\"feed\",\"resourceType\":\"material\",\"requiredYn\":\"N\","
                + "\"schemaJson\":{\"type\":\"object\"},\"validationRule\":\"quantity > 0\"}"));
        String portId = port.path("processIoId").asText();
        org.junit.jupiter.api.Assertions.assertEquals("feed", port.path("role").asText());
        org.junit.jupiter.api.Assertions.assertEquals("object", port.path("schemaJson").path("type").asText());

        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();
        data(put("/process-ios/" + portId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"product\",\"schemaJson\":{\"type\":\"object\","
                + "\"properties\":{\"batch\":{\"type\":\"string\"}}}}"));

        mockMvc.perform(auth(get("/process-ios/" + portId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.role").value("product"));
        mockMvc.perform(auth(get("/workflows/" + workflowId + "/revisions/" + revisionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.snapshot.processIos[0].role").value("feed"))
            .andExpect(jsonPath("$.data.snapshot.processIos[0].schemaJson.type").value("object"));

        mockMvc.perform(auth(put("/process-ios/" + portId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaJson\":[1,2]}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put("/process-ios/" + portId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaJson\":{\"type\":\"string\"}}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put("/process-ios/" + portId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaJson\":{\"type\":\"object\",\"properties\":{},"
                    + "\"required\":[\"missing\"]}}")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void portDirectionFlagsAndQuantityAreValidatedOnCreateAndUpdate() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port values "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String processId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Mixer\"}"))
            .path("processId").asText();
        String base = "{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
            + "\"direction\":\"output\",\"quantity\":1,\"unit\":\"kg\"";

        mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("\"output\"", "\"sideways\"") + "}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("direction")));
        mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"requiredYn\":\"X\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("requiredYn")));
        mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"allowShortageYn\":\"X\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("allowShortageYn")));
        mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                .content(base.replace("\"quantity\":1", "\"quantity\":-1") + "}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quantity")));
        for (String invalidQuantity : new String[] { "0.00001", "10000000000" }) {
            mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                    .content(base.replace("\"quantity\":1", "\"quantity\":" + invalidQuantity) + "}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("quantity")));
        }

        String portId = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content(base.replace("\"output\"", "\" OUTPUT \"")
                + ",\"requiredYn\":\"n\",\"allowShortageYn\":\"y\"}"))
            .path("processIoId").asText();
        mockMvc.perform(auth(get("/process-ios/" + portId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.direction").value("output"))
            .andExpect(jsonPath("$.data.colorScheme").value("emerald"))
            .andExpect(jsonPath("$.data.requiredYn").value("N"))
            .andExpect(jsonPath("$.data.allowShortageYn").value("Y"));

        for (String invalid : new String[] {
            "{\"direction\":\"sideways\"}", "{\"requiredYn\":\"X\"}",
            "{\"allowShortageYn\":\"X\"}", "{\"quantity\":-1}",
            "{\"quantity\":0.00001}", "{\"quantity\":10000000000}"
        }) {
            mockMvc.perform(auth(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
                    .content(invalid)))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void simultaneousConnectionAndPortChangeCannotLeaveAnIncompatibleGraph() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port race "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String source = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Source\"}"))
            .path("processId").asText();
        String target = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Target\"}"))
            .path("processId").asText();
        String output = createPort(source, "output");
        String input = createPort(target, "input");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> connect = workers.submit(() -> {
                start.await();
                return mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
                        + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                        + "\",\"toIoId\":\"" + input + "\"}")))
                    .andReturn().getResponse().getStatus();
            });
            Future<Integer> change = workers.submit(() -> {
                start.await();
                return mockMvc.perform(auth(put("/process-ios/" + output).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"resourceType\":\"energy\"}")))
                    .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int connectionStatus = connect.get(15, TimeUnit.SECONDS);
            int changeStatus = change.get(15, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(1,
                (connectionStatus == 200 ? 1 : 0) + (changeStatus == 200 ? 1 : 0));
            org.junit.jupiter.api.Assertions.assertTrue(connectionStatus == 200 || connectionStatus == 400);
            org.junit.jupiter.api.Assertions.assertTrue(changeStatus == 200 || changeStatus == 409);

            JsonNode liveConnections = data(get("/process-connections").param("workflowId", workflowId));
            JsonNode livePort = data(get("/process-ios/" + output));
            if (liveConnections.size() > 0) {
                org.junit.jupiter.api.Assertions.assertEquals("material", livePort.path("resourceType").asText());
            } else {
                org.junit.jupiter.api.Assertions.assertEquals("energy", livePort.path("resourceType").asText());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void schemaCanBeClearedExplicitlyWithoutClearingOnUnrelatedUpdates() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Clear schema "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String processId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Source\"}"))
            .path("processId").asText();
        String portId = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"output\",\"quantity\":1,\"unit\":\"kg\","
                + "\"schemaJson\":{\"type\":\"object\"}}"))
            .path("processIoId").asText();

        data(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"ioName\":\"Renamed\"}"));
        org.junit.jupiter.api.Assertions.assertEquals("object", data(get("/process-ios/" + portId))
            .path("schemaJson").path("type").asText());

        mockMvc.perform(auth(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaJson\":{\"type\":\"object\"},\"clearSchema\":true}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("schemaJson")));
        data(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"clearSchema\":true}"));
        JsonNode cleared = data(get("/process-ios/" + portId));
        org.junit.jupiter.api.Assertions.assertTrue(cleared.path("schemaJson").isNull()
            || cleared.path("schemaJson").isMissingNode());
    }

    @Test
    void connectionFieldsMustFitDatabaseBounds() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Capacity "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String source = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Source\"}"))
            .path("processId").asText();
        String target = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Target\"}"))
            .path("processId").asText();
        String output = createPort(source, "output");
        String input = createPort(target, "input");
        String base = "{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
            + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
            + "\",\"toIoId\":\"" + input + "\"";
        for (String invalidCapacity : new String[] { "0.00001", "1000000000000000" }) {
            mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                    .content(base + ",\"capacity\":" + invalidCapacity + "}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity")));
        }
        Map<String, String[]> invalidNumbers = Map.of(
            "flowRate", new String[] { "0.00001", "10000000000" },
            "delayTimeSec", new String[] { "0.001", "100000000" },
            "lossRate", new String[] { "0.00001", "10" });
        for (var field : invalidNumbers.entrySet()) {
            for (String invalid : field.getValue()) {
                mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                        .content(base + ",\"" + field.getKey() + "\":" + invalid + "}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
            }
        }
        Map<String, Integer> textLimits = Map.of("sourceHandle", 50, "targetHandle", 50,
            "connectionType", 30, "connectionLabel", 100, "unit", 20);
        for (var field : textLimits.entrySet()) {
            mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                    .content(base + ",\"" + field.getKey() + "\":\""
                        + "x".repeat(field.getValue() + 1) + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
        }
        String connectionId = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(base + ",\"capacity\":1}"))
            .path("connectionId").asText();
        for (String invalidCapacity : new String[] { "0.00001", "1000000000000000" }) {
            mockMvc.perform(auth(put("/process-connections/" + connectionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"capacity\":" + invalidCapacity + "}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity")));
        }
        for (var field : invalidNumbers.entrySet()) {
            for (String invalid : field.getValue()) {
                mockMvc.perform(auth(put("/process-connections/" + connectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + field.getKey() + "\":" + invalid + "}")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
            }
        }
        for (var field : textLimits.entrySet()) {
            mockMvc.perform(auth(put("/process-connections/" + connectionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(field.getKey(),
                        "x".repeat(field.getValue() + 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
        }
    }

    @Test
    void portTextFieldsAreRejectedBeforeDatabaseLengthErrors() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port lengths "
                + UUID.randomUUID() + "\"}")).path("workflowId").asText();
        String processId = data(post("/processes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"Source\"}"))
            .path("processId").asText();
        Map<String, Object> base = Map.of("processId", processId, "itemId", "itm_demo_mix_output",
            "direction", "output", "quantity", 1, "unit", "kg");
        Map<String, Integer> limits = Map.of("ioName", 100, "ioType", 30, "unit", 20, "colorScheme", 30);
        for (var field : limits.entrySet()) {
            String invalid = "x".repeat(field.getValue() + 1);
            Map<String, Object> request = new HashMap<>(base);
            request.put(field.getKey(), invalid);
            mockMvc.perform(auth(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
        }
        String portId = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(base))).path("processIoId").asText();
        for (var field : limits.entrySet()) {
            mockMvc.perform(auth(put("/process-ios/" + portId).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(field.getKey(),
                        "x".repeat(field.getValue() + 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field.getKey())));
        }
    }

    private String createPort(String processId, String direction) throws Exception {
        return data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"" + direction + "\",\"quantity\":1,\"unit\":\"kg\"}"))
            .path("processIoId").asText();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String json = mockMvc.perform(auth(request)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).path("data");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER));
    }
}
