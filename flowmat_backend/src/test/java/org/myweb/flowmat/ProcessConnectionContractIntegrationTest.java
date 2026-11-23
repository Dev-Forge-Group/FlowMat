package org.myweb.flowmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class ProcessConnectionContractIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ProcessConnectionRepository connectionRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void connectionContractIsFrozenInRevisionAndRejectsNegativeCapacity() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Connection contract "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String fromId = createProcess(workflowId, "Source");
        String toId = createProcess(workflowId, "Target");
        String connectionId = data(post("/process-connections")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + fromId
                + "\",\"toProcessId\":\"" + toId + "\",\"capacity\":12,"
                + "\"conditionExpr\":\"quantity > 0\",\"failurePolicy\":\"stop\"}"))
            .path("connectionId").asText();
        String revisionId = data(post("/workflows/" + workflowId + "/revisions"))
            .path("workflowRevisionId").asText();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\":20,\"failurePolicy\":\"skip\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capacity").value(20))
            .andExpect(jsonPath("$.data.failurePolicy").value("skip"));
        mockMvc.perform(auth(get("/workflows/" + workflowId + "/revisions/" + revisionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.snapshot.connections[0].capacity").value(12))
            .andExpect(jsonPath("$.data.snapshot.connections[0].conditionExpr").value("quantity > 0"))
            .andExpect(jsonPath("$.data.snapshot.connections[0].failurePolicy").value("stop"));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\":-1}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"capacity\":5,\"clearCapacity\":true}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("capacity cannot be supplied with clearCapacity."));
        mockMvc.perform(auth(get("/process-connections/" + connectionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.capacity").value(20));
    }

    @Test
    void explicitPortsMustHaveCompatibleDirectionsAndResourceTypes() throws Exception {
        String workflowId = data(post("/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Port linkage "
                + UUID.randomUUID() + "\"}"))
            .path("workflowId").asText();
        String fromId = createProcess(workflowId, "Source");
        String toId = createProcess(workflowId, "Target");
        String outputId = createPort(fromId, "output", "material");
        String wrongSourceId = createPort(fromId, "input", "material");
        String wrongTypeId = createPort(toId, "input", "data");
        String wrongDirectionId = createPort(toId, "output", "material");
        String validInputId = createPort(toId, "input", "material");

        mockMvc.perform(auth(connect(workflowId, fromId, toId, outputId, wrongTypeId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(connect(workflowId, fromId, toId, outputId, wrongDirectionId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(connect(workflowId, fromId, toId, wrongSourceId, validInputId)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(connect(workflowId, fromId, toId, outputId, validInputId)))
            .andExpect(status().isOk());
    }

    @Test
    void selfLinksAndDuplicatePortPairsAreRejected() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Link integrity "
                + UUID.randomUUID() + "\"}")) .path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String output = createPort(source, "output", "material");
        String input = createPort(target, "input", "material");

        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
                    + "\",\"toProcessId\":\"" + source + "\"}")))
            .andExpect(status().isBadRequest());
        data(connect(workflowId, source, target, output, input));
        mockMvc.perform(auth(connect(workflowId, source, target, output, input)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Ports are already connected."));
    }

    @Test
    void incompatibleItemAndUnitTypesAreRejected() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectId\":\"" + DEMO_PROJECT + "\",\"workflowName\":\"Link types "
                + UUID.randomUUID() + "\"}")) .path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String output = createPort(source, "output", "material");
        String countInput = data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + target + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"input\",\"quantity\":1,\"unit\":\"ea\"}"))
            .path("processIoId").asText();
        mockMvc.perform(auth(connect(workflowId, source, target, output, countInput)))
            .andExpect(status().isBadRequest());

        String input = createPort(target, "input", "material");
        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
                    + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                    + "\",\"toIoId\":\"" + input + "\",\"itemId\":\"not-a-real-item\"}")))
            .andExpect(status().isNotFound());
        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
                    + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output
                    + "\",\"toIoId\":\"" + input + "\",\"unit\":\"ea\"}")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(auth(connect(workflowId, source, target, output, input)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.itemId").value("itm_demo_mix_output"));
    }

    @Test
    void aSingleExplicitPortStillConstrainsConnectionItemAndUnit() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Partial port " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String output = createPort(source, "output", "material");
        String otherItem = data(post("/items").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "itemCode", "PORT-" + UUID.randomUUID(), "itemName", "Other item"))))
            .path("itemId").asText();
        String base = "{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + source
            + "\",\"toProcessId\":\"" + target + "\",\"fromIoId\":\"" + output + "\"";

        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"itemId\":\"" + otherItem + "\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Connection itemId must match the ports."));
        mockMvc.perform(auth(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"unit\":\"ea\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Connection unit type must match port units."));
    }

    @Test
    void unitCanBeClearedExplicitlyWithoutClearingItWhenOmitted() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Connection unit " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String connectionId = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target, "unit", "kg"))))
            .path("connectionId").asText();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":2}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unit").value("kg"));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"unit\":null}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unit").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"unit\":7}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("unit must be a string or null."));
    }

    @Test
    void staleConnectionLoadedBeforeWorkflowLockDoesNotOverwriteACommittedChange() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Concurrent connection " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String connectionId = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target, "priority", 1))))
            .path("connectionId").asText();

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.execute(status -> {
            org.junit.jupiter.api.Assertions.assertEquals(1,
                connectionRepository.findById(connectionId).orElseThrow().getPriority());
            TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
            concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.execute(otherStatus -> {
                try {
                    mockMvc.perform(auth(put("/process-connections/" + connectionId)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":2}")))
                        .andExpect(status().isOk());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                return null;
            });
            try {
                mockMvc.perform(auth(put("/process-connections/" + connectionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connectionLabel\":\"after\"}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.priority").value(2));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
            return null;
        });
        mockMvc.perform(auth(get("/process-connections/" + connectionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.priority").value(2))
            .andExpect(jsonPath("$.data.connectionLabel").value("after"));
    }

    @Test
    void changingPortRebindsGeneratedHandleToTheNewPort() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Connection handle " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String firstOutput = createPort(source, "output", "material");
        String secondOutput = createPort(source, "output", "material");
        String firstInput = createPort(target, "input", "material");
        String secondInput = createPort(target, "input", "material");
        String connectionId = data(connect(workflowId, source, target, firstOutput, firstInput))
            .path("connectionId").asText();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("fromIoId", secondOutput,
                    "toIoId", secondInput)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fromIoId").value(secondOutput))
            .andExpect(jsonPath("$.data.toIoId").value(secondInput))
            .andExpect(jsonPath("$.data.sourceHandle").value(secondOutput))
            .andExpect(jsonPath("$.data.targetHandle").value(secondInput));
        data(put("/process-connections/" + connectionId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceHandle\":\"custom-source\"}"));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("fromIoId", firstOutput)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sourceHandle").value("custom-source"));

        String unboundConnectionId = data(post("/process-connections")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target)))).path("connectionId").asText();
        mockMvc.perform(auth(put("/process-connections/" + unboundConnectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("fromIoId", firstOutput,
                    "toIoId", firstInput)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sourceHandle").value(firstOutput))
            .andExpect(jsonPath("$.data.targetHandle").value(firstInput));
        mockMvc.perform(auth(put("/process-connections/" + unboundConnectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromIoId\":\"\",\"toIoId\":\"\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sourceHandle").value("out-default"))
            .andExpect(jsonPath("$.data.targetHandle").value("in-default"));
    }

    @Test
    void numericFieldsPreserveOmittedValuesAndClearExplicitNulls() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Connection numbers " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String connectionId = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target, "flowRate", 12.5,
                "delayTimeSec", 3.5, "lossRate", 0.2, "priority", 4))))
            .path("connectionId").asText();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"connectionLabel\":\"changed\"}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate").value(12.5))
            .andExpect(jsonPath("$.data.delayTimeSec").value(3.5))
            .andExpect(jsonPath("$.data.lossRate").value(0.2))
            .andExpect(jsonPath("$.data.priority").value(4));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowRate\":null,\"delayTimeSec\":null,\"lossRate\":null,\"priority\":null}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowRate").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.delayTimeSec").value(0))
            .andExpect(jsonPath("$.data.lossRate").value(0))
            .andExpect(jsonPath("$.data.priority").value(0));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"flowRate\":\"fast\"}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("flowRate must be a number or null."));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lossRate\":10}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("lossRate must fit numeric(5,4)."));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":1.5}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("priority must be an integer."));
    }

    @Test
    void updatingOtherFieldsPreservesLabelButExplicitNullClearsIt() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Connection label " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String connectionId = data(post("/process-connections").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("workflowId", workflowId,
                "fromProcessId", source, "toProcessId", target, "connectionLabel", "Keep me"))))
            .path("connectionId").asText();

        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"priority\":2}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.connectionLabel").value("Keep me"));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"connectionLabel\":null}")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.connectionLabel").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"connectionLabel\":42}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("connectionLabel must be a string or null."));
        mockMvc.perform(auth(put("/process-connections/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("connectionLabel", "x".repeat(101))))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("connectionLabel must be at most 100 characters."));
    }

    @Test
    void targetRequiredSchemaPropertiesMustExistOnTheOutputWithTheSameType() throws Exception {
        String workflowId = data(post("/workflows").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("projectId", DEMO_PROJECT,
                "workflowName", "Schema compatibility " + UUID.randomUUID())))).path("workflowId").asText();
        String source = createProcess(workflowId, "Source");
        String target = createProcess(workflowId, "Target");
        String output = createSchemaPort(source, "output", "number");
        String wrongInput = createSchemaPort(target, "input", "string");
        String validInput = createSchemaPort(target, "input", "number");

        mockMvc.perform(auth(connect(workflowId, source, target, output, wrongInput)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Target port needs temperature (string)."));
        mockMvc.perform(auth(connect(workflowId, source, target, output, validInput)))
            .andExpect(status().isOk());
    }

    private String createSchemaPort(String processId, String direction, String type) throws Exception {
        return data(post("/process-ios").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("processId", processId,
                "itemId", "itm_demo_mix_output", "direction", direction, "quantity", 1,
                "unit", "kg", "schemaJson", Map.of("type", "object",
                    "properties", Map.of("temperature", Map.of("type", type)),
                    "required", java.util.List.of("temperature"))))))
            .path("processIoId").asText();
    }

    private String createPort(String processId, String direction, String resourceType) throws Exception {
        return data(post("/process-ios")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"processId\":\"" + processId + "\",\"itemId\":\"itm_demo_mix_output\","
                + "\"direction\":\"" + direction + "\",\"quantity\":1,\"unit\":\"kg\","
                + "\"resourceType\":\"" + resourceType + "\"}"))
            .path("processIoId").asText();
    }

    private MockHttpServletRequestBuilder connect(String workflowId, String fromId, String toId,
        String fromIoId, String toIoId) {
        return post("/process-connections")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"fromProcessId\":\"" + fromId
                + "\",\"toProcessId\":\"" + toId + "\",\"fromIoId\":\"" + fromIoId
                + "\",\"toIoId\":\"" + toIoId + "\"}");
    }

    private String createProcess(String workflowId, String name) throws Exception {
        return data(post("/processes")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"workflowId\":\"" + workflowId + "\",\"processName\":\"" + name + "\"}"))
            .path("processId").asText();
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
