package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.flowrun.api.FlowRunController;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStartRequest;
import org.myweb.flowmat.domain.flowrun.application.FlowRunService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Uses the real request converter and controller; no authentication, database or workflow is started. */
class FlowRunStartJsonInputRegressionTest {
    private FlowRunService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FlowRunService.class);
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new FlowRunController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest(name = "{0}: {1} is rejected before the service")
    @MethodSource("invalidRequests")
    void invalidStartJsonIsAnInputErrorWithoutStartingAnything(String path, String label, String body) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of("/flow-runs", "/flow-runs/graph").flatMap(path -> Stream.of(
            Arguments.of(path, "unfinished root object", "{"),
            Arguments.of(path, "array root", "[]"),
            Arguments.of(path, "null root", "null"),
            Arguments.of(path, "object workflowId", "{\"workflowId\":{},\"workflowRevisionId\":\"revision\",\"runType\":\"test\"}"),
            Arguments.of(path, "array workflowRevisionId", "{\"workflowId\":\"workflow\",\"workflowRevisionId\":[],\"runType\":\"test\"}"),
            Arguments.of(path, "object runType", "{\"workflowId\":\"workflow\",\"workflowRevisionId\":\"revision\",\"runType\":{}}"),
            Arguments.of(path, "invalid snapshot number", bodyWithNumber("1e")),
            Arguments.of(path, "positive excessive number scale", bodyWithNumber("1e100000000")),
            Arguments.of(path, "negative excessive number scale", bodyWithNumber("1e-100000000"))));
    }

    private static String bodyWithNumber(String token) {
        // Short tokens only: the exact snapshot reader rejects these scales before service arithmetic or storage.
        return "{\"workflowId\":\"workflow\",\"workflowRevisionId\":\"revision\",\"runType\":\"test\","
            + "\"inputPayload\":{\"quantity\":" + token + "}}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"/flow-runs", "/flow-runs/graph"})
    void aValidStartStillPassesExactNumbersNullAndDateStringsToTheSelectedService(String path) throws Exception {
        String body = "{\"workflowId\":\"workflow\",\"workflowRevisionId\":\"revision\",\"runType\":\"test\","
            + "\"inputPayload\":{\"quantity\":999999999999999.9999,\"scaled\":1.2300,"
            + "\"integer\":9007199254740993,\"nullable\":null,\"date\":\"2041-09-10\","
            + "\"nested\":[{\"value\":0.00000000000000000001}]}}";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<FlowRunStartRequest> captured = ArgumentCaptor.forClass(FlowRunStartRequest.class);
        if ("/flow-runs/graph".equals(path)) verify(service).startGraph(captured.capture());
        else verify(service).start(captured.capture());
        verifyNoMoreInteractions(service);
        FlowRunStartRequest request = captured.getValue();
        assertEquals("workflow", request.workflowId());
        assertEquals("revision", request.workflowRevisionId());
        assertEquals("test", request.runType());
        JsonNode input = request.inputPayload();
        assertEquals(new BigDecimal("999999999999999.9999"), input.path("quantity").decimalValue());
        assertEquals(new BigDecimal("1.2300"), input.path("scaled").decimalValue());
        assertEquals(9007199254740993L, input.path("integer").longValue());
        assertTrue(input.has("nullable") && input.path("nullable").isNull());
        assertEquals("2041-09-10", input.path("date").textValue());
        assertEquals(new BigDecimal("0.00000000000000000001"), input.path("nested").get(0).path("value").decimalValue());
    }
}
