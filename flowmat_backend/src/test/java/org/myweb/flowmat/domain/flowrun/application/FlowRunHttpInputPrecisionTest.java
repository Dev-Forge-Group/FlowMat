package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStartRequest;

class FlowRunHttpInputPrecisionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void callerInputRetainsDecimalPrecisionAndScaleWithoutChangingDefaultMapper() throws Exception {
        var request = json.readValue("""
            {"workflowId":"workflow","workflowRevisionId":"revision","runType":"actual",
             "inputPayload":{"quantity":9007199254740993.123456789,"attrs":{"scaled":1.2300}}}
            """, FlowRunStartRequest.class);
        assertEquals(new BigDecimal("9007199254740993.123456789"), request.inputPayload().path("quantity").decimalValue());
        assertEquals(new BigDecimal("1.2300"), request.inputPayload().path("attrs").path("scaled").decimalValue());
        assertTrue(json.readTree("{\"number\":1.25}").path("number").isDouble());
    }

    @Test void excessiveDecimalScaleIsRejectedBeforeRunCreation() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> json.readValue("""
            {"workflowId":"workflow","workflowRevisionId":"revision","runType":"actual",
             "inputPayload":{"quantity":1e100000000}}
            """, FlowRunStartRequest.class));
    }
}
