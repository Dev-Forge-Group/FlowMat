package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.exception.BusinessException;

class FlowRunHttpBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readsOnlyPinnedSchemaFourBindingAndRejectsGateways() throws Exception {
        var snapshot = mapper.readTree("""
            {"schemaVersion":4,"processes":[{"processId":"n","nodeType":"process"}],
             "processIos":[{"processIoId":"out","processId":"n","direction":"output"}],
             "nodeBindings":[{"processId":"n","profileVersionId":"pv","operationId":"write",
              "outputPortId":"out","requestMapping":{"from":""},"responseMapping":{"from":""}}]}
            """);
        var binding = FlowRunHttpBinding.read(snapshot, "n");
        assertEquals("pv", binding.profileVersionId());
        assertEquals("out", binding.outputPortId());
        assertFalse(binding.toString().contains("requestMapping"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("processes").get(0)).put("nodeType", "exclusive");
        assertThrows(BusinessException.class, () -> FlowRunHttpBinding.read(snapshot, "n"));
    }

    @Test void duplicateBindingAndForeignOutputPortFailClosed() throws Exception {
        var snapshot = mapper.readTree("""
            {"schemaVersion":4,"processes":[{"processId":"n"}],
             "processIos":[{"processIoId":"out","processId":"other","direction":"output"}],
             "nodeBindings":[{"processId":"n","profileVersionId":"pv","operationId":"write",
              "outputPortId":"out","requestMapping":{"from":""},"responseMapping":{"from":""}}]}
            """);
        assertThrows(BusinessException.class, () -> FlowRunHttpBinding.read(snapshot, "n"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("processIos").get(0)).put("processId", "n");
        ((com.fasterxml.jackson.databind.node.ArrayNode)snapshot.path("nodeBindings")).add(snapshot.path("nodeBindings").get(0).deepCopy());
        assertThrows(BusinessException.class, () -> FlowRunHttpBinding.read(snapshot, "n"));
    }

    @Test void terminalOutputStillEnforcesPublishedSchemaAndRule() throws Exception {
        var snapshot = mapper.readTree("""
            {"processIos":[{"processIoId":"out","processId":"n","direction":"output",
             "schemaJson":{"type":"object","properties":{"accepted":{"type":"boolean"}},"required":["accepted"]},
             "validationRule":"quantity > 0"}]}
            """);
        assertThrows(BusinessException.class, () -> FlowRunPortContracts.requirePublishedOutput(snapshot, "n", "out", mapper.readTree("{\"quantity\":1}")));
        assertThrows(BusinessException.class, () -> FlowRunPortContracts.requirePublishedOutput(snapshot, "n", "out", mapper.readTree("{\"quantity\":0,\"attrs\":{\"accepted\":true}}")));
        assertDoesNotThrow(() -> FlowRunPortContracts.requirePublishedOutput(snapshot, "n", "out", mapper.readTree("{\"quantity\":1,\"attrs\":{\"accepted\":true}}")));
    }
}
