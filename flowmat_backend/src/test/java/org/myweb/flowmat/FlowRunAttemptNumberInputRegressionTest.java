package org.myweb.flowmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.flowrun.api.FlowRunStepController;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepCompleteRequest;
import org.myweb.flowmat.domain.flowrun.api.dto.request.FlowRunStepFailRequest;
import org.myweb.flowmat.domain.flowrun.application.FlowRunStepService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** EP8: HTTP binding must not turn a fractional attempt into the current running attempt. No DB is used. */
class FlowRunAttemptNumberInputRegressionTest {
    private FlowRunStepService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FlowRunStepService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FlowRunStepController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest(name = "{0}: attemptNo {1} is rejected before the execution service")
    @MethodSource("invalidAttempts")
    void doesNotTruncateFractionalAttemptsOrWrapOutOfRangeAttempts(String action, String token) throws Exception {
        mvc.perform(post("/flow-runs/run/steps/step/" + action)
                .contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("attemptNo must be a whole number."));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "preview"})
    void snapshotErrorsKeepTheirExistingContractMessage(String action) throws Exception {
        mvc.perform(post("/flow-runs/run/steps/step/" + action).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptNo\":1,\"outputSnapshot\":{\"quantity\":1e100000000}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "inputSnapshot and outputSnapshot must be valid JSON with numbers within the safe conversion limits."));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}: attemptNo {1} keeps its previous meaning")
    @MethodSource("validAttempts")
    void preservesWholeNumbersAndOptionalLatestAttempt(String action, String token, Integer expected) throws Exception {
        mvc.perform(post("/flow-runs/run/steps/step/" + action)
                .contentType(MediaType.APPLICATION_JSON).content(body(token)))
            .andExpect(status().isOk());
        if (action.equals("fail")) {
            var captured = ArgumentCaptor.forClass(FlowRunStepFailRequest.class);
            verify(service).fail(eq("run"), eq("step"), captured.capture());
            assertEquals(expected, captured.getValue().attemptNo());
            assertEquals("WORKER_FAILURE", captured.getValue().errorCode());
        } else {
            var captured = ArgumentCaptor.forClass(FlowRunStepCompleteRequest.class);
            if (action.equals("complete")) verify(service).complete(eq("run"), eq("step"), captured.capture());
            else verify(service).preview(eq("run"), eq("step"), captured.capture());
            assertEquals(expected, captured.getValue().attemptNo());
            assertEquals(7, captured.getValue().outputSnapshot().path("quantity").asInt());
        }
    }

    private static Stream<Arguments> invalidAttempts() {
        return Stream.of("complete", "fail", "preview").flatMap(action ->
            Stream.of("0.5", "1.5", "-1.5", "2147483648", "-2147483649")
                .map(token -> Arguments.of(action, token)));
    }

    private static Stream<Arguments> validAttempts() {
        return Stream.of("complete", "fail", "preview").flatMap(action -> Stream.of(
            Arguments.of(action, "1", 1), Arguments.of(action, "1.0", 1), Arguments.of(action, "\"1\"", 1),
            Arguments.of(action, "null", null), Arguments.of(action, "omitted", null)));
    }

    private static String body(String token) {
        String attempt = token.equals("omitted") ? "" : ",\"attemptNo\":" + token;
        return "{\"errorCode\":\"WORKER_FAILURE\",\"outputSnapshot\":{\"quantity\":7}" + attempt + "}";
    }
}
