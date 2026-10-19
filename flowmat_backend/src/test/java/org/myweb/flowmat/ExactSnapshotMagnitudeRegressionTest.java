package org.myweb.flowmat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStepCompleteRequest;

import static org.junit.jupiter.api.Assertions.*;

/** Parser-only checks: hostile magnitudes never reach arithmetic, servers or a database. */
class ExactSnapshotMagnitudeRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"1e100000000", "1e-100000000", "1e100001", "1e-100001"})
    void productionRejectsExcessiveScaleBeforeArithmetic(String number) {
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
            "{\"outputSnapshot\":{\"quantity\":" + number + "}}", ProductionRunStepCompleteRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e100000000", "1e-100000000", "1e100001", "1e-100001"})
    void completeRejectsExcessiveScaleBeforeArithmetic(String number) {
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
            "{\"outputSnapshot\":{\"quantity\":" + number + "}}", FlowRunStepCompleteRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e100000000", "1e-100000000", "1e100001", "1e-100001"})
    void createRejectsExcessiveScaleInsideNestedAttributes(String number) {
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
            "{\"nodeId\":\"A\",\"inputSnapshot\":{\"attributes\":{\"values\":[" + number + "]}}}",
            FlowRunStepCreateRequest.class));
    }

    @Test
    void completeRetainsTheExistingExactPrecision() throws Exception {
        var request = mapper.readValue("{\"outputSnapshot\":{\"quantity\":999999999999999.9999}}",
            FlowRunStepCompleteRequest.class);
        assertEquals(0, new BigDecimal("999999999999999.9999")
            .compareTo(request.outputSnapshot().path("quantity").decimalValue()));
    }

    @Test
    void createRetainsTheExistingExactPrecision() throws Exception {
        var request = mapper.readValue("{\"nodeId\":\"A\",\"inputSnapshot\":{\"quantity\":0.0001}}",
            FlowRunStepCreateRequest.class);
        assertEquals(0, new BigDecimal("0.0001").compareTo(request.inputSnapshot().path("quantity").decimalValue()));
    }

    @Test
    void nullSnapshotsRemainOptional() throws Exception {
        assertNull(mapper.readValue("{\"outputSnapshot\":null}", FlowRunStepCompleteRequest.class).outputSnapshot());
        assertNull(mapper.readValue("{\"nodeId\":\"A\",\"inputSnapshot\":null}",
            FlowRunStepCreateRequest.class).inputSnapshot());
    }

    @Test
    void existingJacksonScaleBoundaryRemainsReadableWithoutExpandingNumbers() throws Exception {
        var request = mapper.readValue("{\"outputSnapshot\":{\"large\":1e100000,\"small\":1e-100000}}",
            FlowRunStepCompleteRequest.class);
        assertEquals(-100000, request.outputSnapshot().path("large").decimalValue().scale());
        assertEquals(100000, request.outputSnapshot().path("small").decimalValue().scale());
    }
}
