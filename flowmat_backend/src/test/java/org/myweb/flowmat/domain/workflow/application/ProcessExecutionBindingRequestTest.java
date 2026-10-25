package org.myweb.flowmat.domain.workflow.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionBindingRequest;
import org.myweb.flowmat.global.exception.BusinessException;

class ProcessExecutionBindingRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "-1", "9223372036854775808", "true", "\"1\"", "[]", "{}"})
    void expectedVersionMustBeANonnegativeJsonInteger(String value) {
        assertThrows(Exception.class, () -> json.readValue("{\"expectedVersion\":" + value + "}", ProcessExecutionBindingRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"profileVersionId", "operationId", "outputPortId"})
    void idsAreNeverCoercedFromOtherJsonTypes(String field) {
        assertThrows(Exception.class, () -> json.readValue("{\"" + field + "\":42}", ProcessExecutionBindingRequest.class));
    }

    @Test
    void mappingLiteralsKeepTheirDecimalPrecision() throws Exception {
        var request = json.readValue("""
            {"profileVersionId":"v","operationId":"o","outputPortId":"p","expectedVersion":0,
             "requestMapping":{"literal":{"x":9007199254740993.123456789}},"responseMapping":{"from":""}}
            """, ProcessExecutionBindingRequest.class);
        assertEquals("9007199254740993.123456789", request.requestMapping().path("literal").path("x").decimalValue().toPlainString());
        assertEquals(0L, request.expectedVersion());
    }

    @Test
    void explicitNullsRepresentAClearWithoutLosingTheAcknowledgedVersion() throws Exception {
        var request = json.readValue("{\"expectedVersion\":2,\"profileVersionId\":null,\"requestMapping\":null}", ProcessExecutionBindingRequest.class);
        assertNull(request.profileVersionId());
        assertNull(request.requestMapping());
        assertEquals(2L, request.expectedVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"expectedVersion\":0,\"expectedVersion\":1}",
        "{\"expectedVersion\":0} {}", "{\"unknown\":1,\"expectedVersion\":0}",
        "{\"expectedVersion\":0,\"requestMapping\":{\"object\":{\"x\":{},\"x\":{}}}}", "null", "[]"})
    void boundedReaderRejectsDuplicateKeysTrailingJsonUnknownFieldsAndNonObjects(String body) {
        assertThrows(BusinessException.class, () -> ProcessExecutionBindingRequest.read(bytes(body)));
    }

    @Test
    void anOversizedStreamStopsAtTheRequestBudgetAndDoesNotIncludePayloadInTheError() {
        int[] read = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { read[0]++; return ' '; }
            @Override public int read(byte[] target, int offset, int length) {
                java.util.Arrays.fill(target, offset, offset + length, (byte) ' ');
                read[0] += length;
                return length;
            }
        };
        BusinessException error = assertThrows(BusinessException.class, () -> ProcessExecutionBindingRequest.read(endless));
        assertEquals(131073, read[0]);
        assertEquals("body must not exceed 131072 bytes.", error.getMessage());
    }

    @Test
    void boundedReaderAcceptsLongMaxVersionAndPreservesMappingLiteralsExactly() {
        var request = ProcessExecutionBindingRequest.read(bytes("""
            {"expectedVersion":9223372036854775807,"requestMapping":{"literal":9007199254740993.123456789}}
            """));
        assertEquals(Long.MAX_VALUE, request.expectedVersion());
        assertEquals("9007199254740993.123456789", request.requestMapping().path("literal").decimalValue().toPlainString());
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
