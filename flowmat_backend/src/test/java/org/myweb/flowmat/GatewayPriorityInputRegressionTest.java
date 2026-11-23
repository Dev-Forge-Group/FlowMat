package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.workflow.api.ProcessConnectionController;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessConnectionCreateRequest;
import org.myweb.flowmat.domain.workflow.application.ProcessConnectionService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GatewayPriorityInputRegressionTest {
    private final ProcessConnectionService service = mock(ProcessConnectionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcessConnectionController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "-1.5", "2147483647.5"})
    void fractionalPrioritiesCannotSilentlyChooseAnotherRoute(String priority) throws Exception {
        mvc.perform(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(body(priority)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource(value = {"2|2", "2.0|2", "\"2\"|2", "-2147483648|-2147483648", "2147483647|2147483647", "null|"}, delimiter = '|')
    void existingWholeNumberRepresentationsKeepTheirValues(String priority, Integer expected) throws Exception {
        mvc.perform(post("/process-connections").contentType(MediaType.APPLICATION_JSON).content(body(priority)))
            .andExpect(status().isOk());
        ArgumentCaptor<ProcessConnectionCreateRequest> request = ArgumentCaptor.forClass(ProcessConnectionCreateRequest.class);
        verify(service).createConnection(request.capture());
        assertThat(request.getValue().priority()).isEqualTo(expected);
    }

    private static String body(String priority) {
        return "{\"workflowId\":\"wf\",\"fromProcessId\":\"from\",\"toProcessId\":\"to\",\"priority\":" + priority + "}";
    }
}
