package org.myweb.flowmat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.api.FlowRunStepController;
import org.myweb.flowmat.domain.flowrun.application.FlowRunStepService;
import org.myweb.flowmat.domain.production.api.ProductionRunStepController;
import org.myweb.flowmat.domain.production.application.ProductionRunStepService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No authentication, service arithmetic or database is started in this converter/HTTP test. */
class ExactSnapshotInputHttpRegressionTest {
    private FlowRunStepService service;
    private ProductionRunStepService productionSteps;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FlowRunStepService.class);
        productionSteps = mock(ProductionRunStepService.class);
        var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new FlowRunStepController(service), new ProductionRunStepController(productionSteps))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"steps,inputSnapshot,1e100000000", "steps,inputSnapshot,1e-100000000",
        "steps/step/complete,outputSnapshot,1e100000000", "steps/step/complete,outputSnapshot,1e-100000000",
        "steps/step/preview,outputSnapshot,1e100000000", "steps/step/preview,outputSnapshot,1e-100000000"})
    void excessiveScaleIsAnInputErrorBeforeAnyServiceCall(String path, String field, String number) throws Exception {
        mvc.perform(post("/flow-runs/run/" + path).contentType(MediaType.APPLICATION_JSON)
            .content("{\"nodeId\":\"A\",\"" + field + "\":{\"quantity\":" + number + "}}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"outputSnapshot\":{\"quantity\":1e}}"})
    void malformedBodiesAreAlsoInputErrorsWithoutCallingService(String body) throws Exception {
        mvc.perform(post("/flow-runs/run/steps/step/preview").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e100000000", "1e-100000000"})
    void productionRejectsExcessiveScaleWithoutCallingService(String number) throws Exception {
        mvc.perform(post("/production-runs/run/steps/step/complete").contentType(MediaType.APPLICATION_JSON)
            .content("{\"outputSnapshot\":{\"quantity\":" + number + "}}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service, productionSteps);
    }
}
