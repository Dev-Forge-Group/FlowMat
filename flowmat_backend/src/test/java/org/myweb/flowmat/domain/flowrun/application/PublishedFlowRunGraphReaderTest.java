package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class PublishedFlowRunGraphReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsRootsAndOrdinaryRoutesInPublishedOrder() throws Exception {
        FlowRunGraph graph = read("""
            {"processes":[{"processId":"second"},{"processId":"source"},{"processId":"last"},{"processId":"first"}],
             "connections":[
               {"connectionId":"to-last","fromProcessId":"source","toProcessId":"last"},
               {"connectionId":"to-first","fromProcessId":"source","toProcessId":"first"}]}
            """);

        assertEquals(List.of("second", "source"), graph.roots());
        assertEquals(List.of(new FlowRunGraph.Route("to-last", "last"), new FlowRunGraph.Route("to-first", "first")),
            graph.routes("source", json("{}")));
        assertThrows(UnsupportedOperationException.class, () -> graph.roots().add("extra"));
    }

    @Test
    void retainsHistoricalDefaultsAndTextWithoutTrimming() throws Exception {
        FlowRunGraph graph = read("""
            {"processes":[{"processId":" source "},{"processId":"target"}],"nodePolicies":null,
             "connections":[{"connectionId":"edge","fromProcessId":" source ","toProcessId":"target",
               "failurePolicy":" ","conditionExpr":" ","priority":1.5,"defaultRoute":false,
               "lossRate":0.5,"delayTimeSec":9}]}
            """);

        assertEquals(List.of(" source "), graph.roots());
        assertEquals(FlowRunGraph.NodePolicy.DEFAULT, graph.policy(" source "));
        assertEquals(FlowRunGraph.NodePolicy.DEFAULT, graph.policy("unknown"));
        assertEquals("stop", graph.failurePolicy("edge"));
        assertEquals("stop", graph.failurePolicy(null));
        assertNull(graph.delay("edge"));
        assertEquals(BigDecimal.ZERO, graph.delaySeconds("missing"));
        assertFalse(graph.quantityChanges(" source ", "edge"));
        assertEquals(List.of(new FlowRunGraph.Decision("edge", "target", true)),
            graph.preview(" source ", json("{\"quantity\":2}")));
    }

    @Test
    void appliesLossAndDelayOnlyFromTheirPublishedSchemaVersions() throws Exception {
        for (int version : List.of(1, 2, 3)) {
            ObjectNode snapshot = snapshot();
            snapshot.put("schemaVersion", version);
            ObjectNode edge = (ObjectNode) snapshot.path("connections").get(0);
            edge.put("lossRate", new BigDecimal("0.1250"));
            edge.put("delayTimeSec", new BigDecimal("0.2500"));
            edge.put("delayBasis", "working");
            FlowRunGraph graph = PublishedFlowRunGraphReader.read(snapshot);

            assertEquals(version >= 2, graph.quantityChanges("source", "edge"));
            assertEquals(version >= 3 ? new BigDecimal("0.2500") : BigDecimal.ZERO, graph.delaySeconds("edge"));
            assertEquals(version >= 3 ? Duration.ofMillis(250) : null, graph.delay("edge"));
            assertTrue(graph.workingDelay("edge"));
            FlowRunGraph.Decision decision = graph.preview("source", json("{\"quantity\":8}")).get(0);
            assertEquals(version >= 2 ? new BigDecimal("7.0000") : null, decision.quantity());
            if (version >= 2) assertEquals(new BigDecimal("0.1250"), decision.loss().rate());
            else assertNull(decision.loss());
        }
    }

    @Test
    void ignoresLegacyInvalidLossAndDelayAndNonnumericOptionalValues() throws Exception {
        ObjectNode snapshot = snapshot();
        ObjectNode edge = (ObjectNode) snapshot.path("connections").get(0);
        edge.put("lossRate", -2);
        edge.put("delayTimeSec", -3);
        FlowRunGraph legacy = PublishedFlowRunGraphReader.read(snapshot);
        assertFalse(legacy.quantityChanges("source", "edge"));
        assertNull(legacy.delay("edge"));

        snapshot.put("schemaVersion", 2);
        edge.put("lossRate", 0);
        assertNull(PublishedFlowRunGraphReader.read(snapshot).delay("edge"));

        snapshot.put("schemaVersion", 3);
        edge.put("lossRate", "0.25");
        edge.put("delayTimeSec", "4");
        edge.put("delayBasis", "Working");
        FlowRunGraph current = PublishedFlowRunGraphReader.read(snapshot);
        assertFalse(current.quantityChanges("source", "edge"));
        assertNull(current.delay("edge"));
        assertFalse(current.workingDelay("edge"));
    }

    @Test
    void keepsDecimalCapacityAndFixedSplitQuantitiesExact() throws Exception {
        ObjectNode snapshot = (ObjectNode) json("""
            {"processes":[{"processId":"source"},{"processId":"split","nodeType":"SpLiT"},{"processId":"target"}],
             "connections":[
               {"connectionId":"incoming","fromProcessId":"source","toProcessId":"split"},
               {"connectionId":"fixed","fromProcessId":"split","toProcessId":"target","splitKind":"fixed"}]}
            """);
        ObjectNode edge = (ObjectNode) snapshot.path("connections").get(1);
        edge.put("capacity", new BigDecimal("1.2500"));
        edge.put("splitValue", new BigDecimal("1.2500"));
        FlowRunGraph graph = PublishedFlowRunGraphReader.read(snapshot);

        assertTrue(graph.split("split"));
        assertTrue(graph.gateway("split"));
        assertEquals(new BigDecimal("1.2500"),
            graph.preview("split", json("{\"quantity\":1.25}")).get(0).quantity());

        ObjectNode ordinary = snapshot();
        ((ObjectNode) ordinary.path("connections").get(0)).put("capacity", new BigDecimal("1.250000000000000001"));
        FlowRunGraph precise = PublishedFlowRunGraphReader.read(ordinary);
        ObjectNode output = mapper.createObjectNode();
        output.put("quantity", new BigDecimal("1.250000000000000002"));
        BusinessException failure = assertThrows(BusinessException.class, () -> precise.routes("source", output));
        assertEquals("Connection edge capacity exceeded.", failure.getMessage());
    }

    @Test
    void readsGatewayKindsPrioritiesDefaultsAndJoinInputs() throws Exception {
        FlowRunGraph graph = read("""
            {"processes":[{"processId":"source"},{"processId":"choice","nodeType":"EXCLUSIVE"},
               {"processId":"join","nodeType":"Join"},{"processId":"other"}],
             "connections":[
               {"connectionId":"start","fromProcessId":"source","toProcessId":"choice"},
               {"connectionId":"fallback","fromProcessId":"choice","toProcessId":"other","priority":-1,"defaultRoute":true},
               {"connectionId":"first","fromProcessId":"choice","toProcessId":"join","priority":2,"conditionExpr":"quantity > 1"},
               {"connectionId":"second","fromProcessId":"choice","toProcessId":"join","priority":2},
               {"connectionId":"last","fromProcessId":"other","toProcessId":"join"}]}
            """);

        assertTrue(graph.exclusive("choice"));
        assertTrue(graph.join("join"));
        assertTrue(graph.gateway("join"));
        assertFalse(graph.gateway("source"));
        assertEquals(3, graph.stillComing("join", List.of("source")));
        assertEquals(List.of(new FlowRunGraph.Decision("fallback", "other", false, "default_not_needed"),
                new FlowRunGraph.Decision("first", "join", true, "first_match"),
                new FlowRunGraph.Decision("second", "join", false, "exclusive_not_chosen")),
            graph.preview("choice", json("{\"quantity\":2}")));
    }

    @Test
    void sourcePortUnitFallbackAndPublishedRulesRemainActive() throws Exception {
        FlowRunGraph graph = read("""
            {"processes":[{"processId":"source"},{"processId":"target"}],
             "processIos":[
               {"processIoId":"out","processId":"source","direction":"output","unit":"kg","validationRule":"quantity >= 1"},
               {"processIoId":"in","processId":"target","direction":"input",
                 "schemaJson":{"type":"object","properties":{"grade":{"type":"string"}},"required":["grade"]}}],
             "connections":[{"connectionId":"edge","fromProcessId":"source","toProcessId":"target",
               "fromIoId":"out","toIoId":"in","conditionExpr":"attrs.grade = 'A'"}]}
            """);

        assertEquals(List.of(new FlowRunGraph.Route("edge", "target")),
            graph.routes("source", json("{\"quantity\":1,\"unit\":\"kg\",\"attrs\":{\"grade\":\"A\"}}")));
        BusinessException unit = assertThrows(BusinessException.class,
            () -> graph.routes("source", json("{\"quantity\":1,\"unit\":\"ea\",\"attrs\":{\"grade\":\"A\"}}")));
        assertEquals("Connection edge unit does not match outputSnapshot.unit.", unit.getMessage());
        BusinessException rule = assertThrows(BusinessException.class,
            () -> graph.routes("source", json("{\"quantity\":0,\"attrs\":{\"grade\":\"A\"}}")));
        assertEquals("Port out validationRule did not pass.", rule.getMessage());
    }

    @Test
    void readsPolicyBoundsAndPartialPolicyDefaults() throws Exception {
        ObjectNode snapshot = snapshot();
        snapshot.set("nodePolicies", json("""
            [{"processId":"source","timeoutSeconds":604800,"retryLimit":0,"retryDelaySeconds":86400,
              "retryBackoff":"exponential","maxRetryDelaySeconds":604800,"concurrencyLimit":1000},
             {"processId":"target","concurrencyLimit":1}]
            """));
        FlowRunGraph graph = PublishedFlowRunGraphReader.read(snapshot);

        assertEquals(new FlowRunGraph.NodePolicy(604800, 0, 86400, true, 604800, 1000), graph.policy("source"));
        assertEquals(new FlowRunGraph.NodePolicy(null, 3, 0, false, null, 1), graph.policy("target"));
    }

    @Test
    void preservesValidationOrderBeforeReadingConnections() throws Exception {
        assertInvalid("{\"processes\":[],\"connections\":[],\"nodePolicies\":{}}",
            "Published revision has no executable graph.");
        assertInvalid("""
            {"processes":[{"processId":"a"},{"processId":"a"}],"connections":[],"nodePolicies":{}}
            """, "Published revision has duplicate process IDs.");
        ObjectNode snapshot = snapshot();
        snapshot.set("nodePolicies", json("{}"));
        snapshot.putNull("processIos");
        assertInvalid(snapshot, "Published revision has invalid nodePolicies.");
        snapshot.set("nodePolicies", json("[{\"processId\":\"source\",\"retryLimit\":1.5}]"));
        assertInvalid(snapshot, "Published revision has an invalid node policy: source");
        snapshot.remove("nodePolicies");
        assertInvalid(snapshot, "Published revision has invalid processIos.");
        snapshot.set("processIos", json("""
            [{"processIoId":"out","validationRule":"quantity == 2","schemaJson":{"type":"array"}}]
            """));
        assertInvalid(snapshot, "Published revision has invalid port out validationRule.");
    }

    @Test
    void preservesValidationOrderWithinAConnection() throws Exception {
        ObjectNode snapshot = snapshot();
        snapshot.put("schemaVersion", 3);
        ObjectNode edge = (ObjectNode) snapshot.path("connections").get(0);
        edge.put("toProcessId", "missing");
        edge.put("conditionExpr", "quantity == 2");
        edge.put("capacity", -1);
        edge.put("failurePolicy", "invalid");
        edge.put("fromIoId", "missing-out");
        edge.put("toIoId", "missing-in");
        edge.put("lossRate", 1);
        edge.put("delayTimeSec", -1);
        assertInvalid(snapshot, "Published revision connection refers to a missing process: edge");
        edge.put("toProcessId", "target");
        assertInvalid(snapshot, "Published revision has invalid connection edge conditionExpr.");
        edge.remove("conditionExpr");
        assertInvalid(snapshot, "Published revision has invalid capacity: edge");
        edge.remove("capacity");
        assertInvalid(snapshot, "Published revision has invalid failure policy: edge");
        edge.remove("failurePolicy");
        assertInvalid(snapshot, "Published revision connection edge has an invalid fromIoId.");
        edge.remove("fromIoId");
        assertInvalid(snapshot, "Published revision connection edge has an invalid toIoId.");
        edge.remove("toIoId");
        assertInvalid(snapshot, "Published revision has an invalid lossRate: edge");
        edge.remove("lossRate");
        assertInvalid(snapshot, "Published revision has an invalid delayTimeSec: edge");
    }

    @Test
    void validatesDuplicateConnectionsAfterTheirFieldsAndRejectsPartialCycles() throws Exception {
        ObjectNode snapshot = snapshot();
        ObjectNode duplicate = ((ObjectNode) snapshot.path("connections").get(0)).deepCopy();
        duplicate.put("conditionExpr", "quantity == 2");
        ((com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("connections")).add(duplicate);
        assertInvalid(snapshot, "Published revision has invalid connection edge conditionExpr.");
        duplicate.remove("conditionExpr");
        assertInvalid(snapshot, "Published revision has duplicate connection IDs.");
        assertInvalid("""
            {"processes":[{"processId":"root"},{"processId":"a"},{"processId":"b"}],"connections":[
               {"connectionId":"ab","fromProcessId":"a","toProcessId":"b"},
               {"connectionId":"ba","fromProcessId":"b","toProcessId":"a"}]}
            """, "Graph revisions with cycles cannot execute.");
    }

    @Test
    void validatesJoinAndSplitInputsBeforeCycleDetection() throws Exception {
        ObjectNode snapshot = (ObjectNode) json("""
            {"processes":[{"processId":"join","nodeType":"join"},{"processId":"split","nodeType":"split"},
               {"processId":"a"},{"processId":"b"}],"connections":[
               {"connectionId":"ab","fromProcessId":"a","toProcessId":"b"},
               {"connectionId":"ba","fromProcessId":"b","toProcessId":"a"}]}
            """);
        assertInvalid(snapshot, "Published revision Join join has no incoming connection.");
        ((ObjectNode) snapshot.path("processes").get(0)).remove("nodeType");
        assertInvalid(snapshot, "Published revision Split split has no incoming connection.");
    }

    private FlowRunGraph read(String snapshot) throws Exception {
        return PublishedFlowRunGraphReader.read(json(snapshot));
    }

    private ObjectNode snapshot() throws Exception {
        return (ObjectNode) json("""
            {"processes":[{"processId":"source"},{"processId":"target"}],
             "connections":[{"connectionId":"edge","fromProcessId":"source","toProcessId":"target"}]}
            """);
    }

    private void assertInvalid(String snapshot, String message) throws Exception {
        assertInvalid(json(snapshot), message);
    }

    private void assertInvalid(JsonNode snapshot, String message) {
        BusinessException failure = assertThrows(BusinessException.class,
            () -> PublishedFlowRunGraphReader.read(snapshot));
        assertEquals(ErrorCode.CONFLICT, failure.getErrorCode());
        assertEquals(message, failure.getMessage());
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
