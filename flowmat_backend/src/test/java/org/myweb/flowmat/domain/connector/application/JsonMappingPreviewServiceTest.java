package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class JsonMappingPreviewServiceTest {
    @Mock private ProjectAccessService access;
    @Mock private JsonMappingEngine engine;
    private JsonMappingPreviewService service;

    @BeforeEach
    void setUp() {
        service = new JsonMappingPreviewService(access, engine);
    }

    @Test
    void authorizesThenReadsExactJsonBeforeMapping() {
        when(engine.map(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.preview("project-1", stream("""
            {"version":1,"source":{"quantity":9007199254740993.123456789000},"mapping":{"from":""}}
            """));

        assertEquals(1, result.version());
        assertEquals(new BigDecimal("9007199254740993.123456789000"), result.result().path("quantity").decimalValue());
        ArgumentCaptor<JsonNode> source = ArgumentCaptor.forClass(JsonNode.class);
        InOrder calls = inOrder(access, engine);
        calls.verify(access).requireProjectWriteAccess("project-1");
        calls.verify(engine).map(source.capture(), any());
        calls.verifyNoMoreInteractions();
        assertEquals(result.result(), source.getValue());
        assertFalse(result.toString().contains("9007199254740993"));
    }

    @Test
    void aDeniedProjectDoesNotReadTheBodyOrRunAMapping() {
        BusinessException denied = new BusinessException(ErrorCode.FORBIDDEN);
        doThrow(denied).when(access).requireProjectWriteAccess("project-1");
        InputStream unreadable = new InputStream() {
            @Override public int read() { throw new AssertionError("unauthorized body was read"); }
        };

        assertSame(denied, assertThrows(BusinessException.class, () -> service.preview("project-1", unreadable)));

        verifyNoInteractions(engine);
    }

    @Test
    void readsAtMostTheByteLimitPlusOneWithoutContentLengthMetadata() {
        class EndlessBody extends InputStream {
            int consumed;
            @Override public int read() { consumed++; return ' '; }
            @Override public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) ' ');
                consumed += length;
                return length;
            }
        }
        EndlessBody body = new EndlessBody();

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("project-1", body));

        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertEquals(131073, body.consumed);
        assertTrue(rejected.getMessage().contains("body"));
        verifyNoInteractions(engine);
    }

    @Test
    void anInputReadFailureDoesNotExposeItsMessage() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private-example-marker"); }
        };

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.preview("project-1", broken));

        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertFalse(rejected.getMessage().contains("private-example-marker"));
        verifyNoInteractions(engine);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"version\":1,\"source\":{},\"mapping\":{\"literal\":1}} true",
        "{\"version\":1,\"source\":{},\"mapping\":{\"literal\":1}} {}",
        "{\"version\":1,\"source\":{},\"mapping\":{\"literal\":1,\"literal\":2}}"
    })
    void trailingRootsAndDuplicateKeysAreRejectedBeforeMapping(String body) {
        BusinessException rejected = assertThrows(BusinessException.class,
            () -> service.preview("project-1", stream(body)));

        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        verifyNoInteractions(engine);
    }

    @Test
    void theEnvelopeDepthIsLimitedBeforeConstructingAnUnboundedTree() {
        String body = "{\"version\":1,\"source\":" + "[".repeat(100) + "null" + "]".repeat(100)
            + ",\"mapping\":{\"from\":\"\"}}";

        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BusinessException.class,
            () -> service.preview("project-1", stream(body))).getErrorCode());
        verifyNoInteractions(engine);
    }

    private static InputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
