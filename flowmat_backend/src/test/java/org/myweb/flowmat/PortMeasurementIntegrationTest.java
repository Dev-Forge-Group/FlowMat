package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Port quantity and unit are optional outside material and product ports (docs/domain/port-measurement.md,
 * ADR-005 decision 1): a data flow runs with no dummy quantity or unit, and manufacturing ports still need both.
 */
@AutoConfigureMockMvc
class PortMeasurementIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void manufacturingPortsStillNeedAQuantityAndUnit() throws Exception {
        String workflow = workflow();
        String process = process(workflow, "Mix");
        call(post("/process-ios"), Map.of("processId", process, "direction", "input", "resourceType", "material"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Material and product ports need a quantity and unit."));
        call(post("/process-ios"), Map.of("processId", process, "direction", "output", "resourceType", "product", "quantity", 1))
            .andExpect(status().isBadRequest());
        String material = data(post("/process-ios"), Map.of("processId", process, "direction", "input",
            "resourceType", "material", "quantity", 2, "unit", "kg")).path("processIoId").asText();
        call(put("/process-ios/" + material), Map.of("clearMeasure", true)).andExpect(status().isBadRequest());

        // A data port becomes material only with a quantity and unit.
        String data = data(post("/process-ios"), Map.of("processId", process, "direction", "output", "resourceType", "data"))
            .path("processIoId").asText();
        call(put("/process-ios/" + data), Map.of("resourceType", "material", "ioType", "material"))
            .andExpect(status().isBadRequest());
        call(put("/process-ios/" + data), Map.of("resourceType", "material", "ioType", "material", "quantity", 1, "unit", "ea"))
            .andExpect(status().isOk());
    }

    @Test
    void otherPortsKeepNoQuantityOrUnitAndClearThem() throws Exception {
        String workflow = workflow();
        String process = process(workflow, "Meter");
        JsonNode energy = data(post("/process-ios"), Map.of("processId", process, "direction", "input", "resourceType", "energy",
            "unit", "kWh"));
        assertTrue(energy.path("quantity").isNull());
        assertEquals("kWh", energy.path("unit").asText());
        call(post("/process-ios"), Map.of("processId", process, "direction", "input", "resourceType", "energy", "quantity", 3))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A port quantity needs a unit."));

        String id = energy.path("processIoId").asText();
        assertEquals(12.5, data(put("/process-ios/" + id), Map.of("quantity", 12.5)).path("quantity").asDouble());
        JsonNode unitOnly = data(put("/process-ios/" + id), Map.of("clearMeasure", true, "unit", "MWh"));
        assertTrue(unitOnly.path("quantity").isNull());
        assertEquals("MWh", unitOnly.path("unit").asText());
        JsonNode cleared = data(put("/process-ios/" + id), Map.of("clearMeasure", true));
        assertTrue(cleared.path("quantity").isNull());
        assertTrue(cleared.path("unit").isNull());
    }

    @Test
    void aDataFlowWithoutQuantitiesOrUnitsPublishesAndRuns() throws Exception {
        String workflow = workflow();
        String read = process(workflow, "Read file");
        String write = process(workflow, "Write dataset");
        String out = data(post("/process-ios"), Map.of("processId", read, "direction", "output", "resourceType", "file"))
            .path("processIoId").asText();
        String in = data(post("/process-ios"), Map.of("processId", write, "direction", "input", "resourceType", "file"))
            .path("processIoId").asText();
        data(post("/process-connections"), Map.of("workflowId", workflow, "fromProcessId", read, "toProcessId", write,
            "fromIoId", out, "toIoId", in));
        // No unit is no warning; the only one left is about the ports having no schema.
        call(get("/workflows/" + workflow + "/validation"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.errors").value(0))
            .andExpect(jsonPath("$.data.issues[*].code").value(org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.equalTo("SCHEMA_UNVERIFIED"))));
        JsonNode revision = data(post("/workflows/" + workflow + "/revisions"), null);
        for (JsonNode port : revision.path("snapshot").path("processIos")) {
            assertTrue(port.path("quantity").isNull());
            assertTrue(port.path("unit").isNull());
        }
        String runId = data(post("/flow-runs/graph"), Map.of("workflowId", workflow,
            "workflowRevisionId", revision.path("workflowRevisionId").asText(), "runType", "test")).path("flowRunId").asText();
        String root = data(get("/flow-runs/" + runId + "/steps"), null).get(0).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + root + "/start"), null);
        data(post("/flow-runs/" + runId + "/steps/" + root + "/complete"), Map.of("outputSnapshot", Map.of("attrs", Map.of("rows", 3))));
        String next = data(get("/flow-runs/" + runId + "/steps"), null).get(1).path("stepId").asText();
        data(post("/flow-runs/" + runId + "/steps/" + next + "/start"), null);
        data(post("/flow-runs/" + runId + "/steps/" + next + "/complete"), Map.of("outputSnapshot", Map.of()));
        assertEquals("finished", data(post("/flow-runs/" + runId + "/finish"), Map.of()).path("status").asText());
    }

    private String workflow() throws Exception {
        return data(post("/workflows"), Map.of("projectId", DEMO_PROJECT, "workflowName", "Measure " + UUID.randomUUID()))
            .path("workflowId").asText();
    }

    private String process(String workflow, String name) throws Exception {
        return data(post("/processes"), Map.of("workflowId", workflow, "processName", name)).path("processId").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mapper.readTree(call(request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }
}
