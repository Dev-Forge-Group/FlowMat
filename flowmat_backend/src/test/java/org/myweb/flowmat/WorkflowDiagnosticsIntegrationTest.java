package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.repository.ProcessConnectionRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessIoRepository;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.global.security.JwtProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Structured workflow diagnostics (archify's diagnostic shape): every issue carries evidence, the edits that clear it and
 * the warnings it hides; starts and ends are checked once a workflow uses Input or Output nodes; a broken connection hides
 * only the warnings its absence causes, never an error; and applying a suggested fix clears its issue.
 */
@AutoConfigureMockMvc
class WorkflowDiagnosticsIntegrationTest extends IntegrationTestSupport {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessIoRepository portRepository;
    @Autowired private ProcessConnectionRepository connectionRepository;

    @Test
    void startsAndEndsAreCheckedOnceTheWorkflowUsesInputAndOutputNodes() throws Exception {
        Graph g = new Graph();
        g.node("In", "input");
        g.node("Mix", "process");
        g.node("Out", "output");
        g.node("Stray", "process");
        g.node("Dead", "process");
        g.node("Buffer", "storage");
        g.node("Lonely", "process");
        g.link("In", "Mix");
        g.link("Mix", "Out");
        g.link("Stray", "Mix");
        g.link("Mix", "Dead");
        g.link("Buffer", "Out");

        JsonNode report = g.validation();
        assertEquals(0, report.path("errors").asInt());
        for (JsonNode issue : report.path("issues")) {
            assertTrue(issue.path("evidence").isObject(), issue.toString());
            assertTrue(issue.path("supportedFixes").isArray(), issue.toString());
            assertTrue(issue.path("suppresses").isArray(), issue.toString());
        }
        JsonNode root = only(report, "UNEXPECTED_ROOT");
        assertEquals("warning", root.path("severity").asText());
        assertEquals(g.id("Stray"), root.path("processId").asText());
        assertEquals("process", root.path("evidence").path("nodeType").asText());
        assertEquals(List.of("input", "storage"), strings(root.path("evidence").path("allowedRootKinds")));
        assertEquals(List.of("connect an earlier step into \"Stray\"",
            "change \"Stray\" to an Input node if material enters the workflow there"), strings(root.path("supportedFixes")));
        JsonNode end = only(report, "UNEXPECTED_TERMINAL");
        assertEquals(g.id("Dead"), end.path("processId").asText());
        assertEquals(List.of("output", "storage"), strings(end.path("evidence").path("allowedTerminalKinds")));
        // The isolated process is not also reported as an unexpected start and end: its warning explains both.
        JsonNode lonely = only(report, "PROCESS_ISOLATED");
        assertEquals(g.id("Lonely"), lonely.path("processId").asText());
        assertEquals(List.of("UNEXPECTED_ROOT", "UNEXPECTED_TERMINAL"), strings(lonely.path("suppresses")));
        assertEquals(List.of("connect \"Lonely\" to the step before or after it"), strings(lonely.path("supportedFixes")));

        // Warnings never block publishing.
        call(post("/workflows/" + g.workflow + "/revisions")).andExpect(status().isOk());

        // Applying a suggested fix clears its issue.
        var stray = processRepository.findById(g.id("Stray")).orElseThrow();
        stray.setNodeType("input");
        processRepository.saveAndFlush(stray);
        assertFalse(codes(g.validation()).contains("UNEXPECTED_ROOT"));
    }

    @Test
    void workflowsWithoutInputOrOutputNodesHaveNoStartOrEndChecks() throws Exception {
        Graph g = new Graph();
        g.node("Read", "process");
        g.node("Write", "process");
        g.link("Read", "Write");
        JsonNode report = g.validation();
        assertEquals(0, report.path("errors").asInt());
        assertEquals(0, report.path("warnings").asInt());
    }

    @Test
    void aBrokenConnectionHidesOnlyTheWarningsItsAbsenceCauses() throws Exception {
        Graph g = new Graph();
        g.node("In", "input");
        g.node("Gate", "exclusive");
        g.node("Left", "process");
        g.node("Right", "process");
        g.link("In", "Gate");
        g.link("Gate", "Left");
        String broken = g.link("Gate", "Right");
        g.requiredInput("Right");
        var connection = connectionRepository.findById(broken).orElseThrow();
        connection.setFromIoId("deleted-port");
        connectionRepository.saveAndFlush(connection);

        JsonNode report = g.validation();
        Set<String> codes = Set.copyOf(codes(report));
        // Without the broken connection, Right is isolated and an unexpected start and the gateway has one route; all
        // three follow from the one broken connection, so its error lists them instead.
        JsonNode orphan = only(report, "CONNECTION_ORPHAN");
        assertEquals(Set.of("PROCESS_ISOLATED", "UNEXPECTED_ROOT", "GATEWAY_SINGLE_ROUTE"),
            Set.copyOf(strings(orphan.path("suppresses"))));
        assertEquals(List.of("fromPort"), strings(orphan.path("evidence").path("missing")));
        assertEquals(List.of("delete the connection from \"Gate\" to \"Right\""), strings(orphan.path("supportedFixes")));
        assertFalse(codes.contains("PROCESS_ISOLATED"));
        assertFalse(codes.contains("UNEXPECTED_ROOT"));
        assertFalse(codes.contains("GATEWAY_SINGLE_ROUTE"));
        // Errors are never hidden: the required input still counts.
        assertTrue(codes.contains("REQUIRED_INPUT_UNCONNECTED"));
        assertEquals(2, report.path("errors").asInt());

        connection.setFromIoId(null);
        connectionRepository.saveAndFlush(connection);
        JsonNode repaired = g.validation();
        assertEquals(1, repaired.path("errors").asInt());
        assertFalse(codes(repaired).contains("GATEWAY_SINGLE_ROUTE"));
        assertFalse(codes(repaired).contains("PROCESS_ISOLATED"));
    }

    @Test
    void suggestedFixesClearTheirIssues() throws Exception {
        Graph g = new Graph();
        g.node("From", "process");
        g.node("To", "process");
        String link = g.link("From", "To");
        String port = g.requiredInput("To");
        var connection = connectionRepository.findById(link).orElseThrow();
        connection.setDefaultRouteYn("Y");
        connection.setLossRate(new BigDecimal("5"));
        connection.setDelayTimeSec(new BigDecimal("-30"));
        connectionRepository.saveAndFlush(connection);

        JsonNode report = g.validation();
        assertEquals(List.of("clear \"default\" on the connection from \"From\" to \"To\""),
            strings(only(report, "DEFAULT_ROUTE_OUTSIDE_GATEWAY").path("supportedFixes")));
        JsonNode loss = only(report, "LOSS_RATE_RANGE");
        assertEquals("lossRate", loss.path("evidence").path("field").asText());
        assertEquals(List.of("set the loss rate on the connection from \"From\" to \"To\" to 0.05 (5% as a fraction)"),
            strings(loss.path("supportedFixes")));
        assertEquals(List.of("set the delay on the connection from \"From\" to \"To\" to 30 s"),
            strings(only(report, "DELAY_RANGE").path("supportedFixes")));
        JsonNode required = only(report, "REQUIRED_INPUT_UNCONNECTED");
        assertEquals(List.of("connect an output to input port \"Feed\" of \"To\"", "make input port \"Feed\" of \"To\" optional"),
            strings(required.path("supportedFixes")));

        connection.setDefaultRouteYn("N");
        connection.setLossRate(new BigDecimal("0.05"));
        connection.setDelayTimeSec(new BigDecimal("30"));
        connectionRepository.saveAndFlush(connection);
        var feed = portRepository.findById(port).orElseThrow();
        feed.setRequiredYn("N");
        portRepository.saveAndFlush(feed);
        assertEquals(0, g.validation().path("errors").asInt());
    }

    private final class Graph {
        final String workflow;
        final Map<String, String> nodes = new HashMap<>();

        Graph() throws Exception {
            workflow = data(post("/workflows").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("projectId", DEMO_PROJECT, "workflowName", "Diagnostics " + UUID.randomUUID())))).path("workflowId")
                .asText();
        }

        String id(String name) {
            return nodes.get(name);
        }

        void node(String name, String type) throws Exception {
            nodes.put(name, data(post("/processes").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "processName", name, "nodeType", type)))).path("processId").asText());
        }

        String link(String from, String to) throws Exception {
            return data(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("workflowId", workflow, "fromProcessId", id(from), "toProcessId", id(to))))).path("connectionId").asText();
        }

        String requiredInput(String name) throws Exception {
            return data(post("/process-ios").contentType(MediaType.APPLICATION_JSON).content(json(
                Map.of("processId", id(name), "itemId", "itm_demo_mix_output", "ioName", "Feed", "direction", "input",
                    "quantity", 1, "unit", "kg", "requiredYn", "Y")))).path("processIoId").asText();
        }

        JsonNode validation() throws Exception {
            return data(get("/workflows/" + workflow + "/validation"));
        }
    }

    private static JsonNode only(JsonNode report, String code) {
        List<JsonNode> found = new ArrayList<>();
        report.path("issues").forEach(issue -> { if (code.equals(issue.path("code").asText())) found.add(issue); });
        assertEquals(1, found.size(), code + " in " + report);
        return found.get(0);
    }

    private static List<String> codes(JsonNode report) {
        return strings(report.path("issues").findValues("code"));
    }

    private static List<String> strings(Iterable<JsonNode> values) {
        List<String> found = new ArrayList<>();
        values.forEach(value -> found.add(value.asText()));
        return found;
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtProvider.generateAccessToken(DEMO_OWNER)));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(call(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .path("data");
    }
}
