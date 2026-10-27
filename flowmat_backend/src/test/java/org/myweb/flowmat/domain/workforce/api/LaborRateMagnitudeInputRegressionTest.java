package org.myweb.flowmat.domain.workforce.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.application.WorkerLaborRateService;
import org.myweb.flowmat.domain.workforce.domain.entity.WorkerLaborRate;
import org.myweb.flowmat.domain.workforce.repository.WorkerLaborRateRepository;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real JSON binding and hourly-rate validation, with no database or authentication fixture. */
@Timeout(10)
class LaborRateMagnitudeInputRegressionTest {
    private static final String PROJECT = "labor-rate-project";
    private static final String WORKER = "labor-rate-worker";
    private static final String OWNER = "labor-rate-owner";
    private static final LocalDate TODAY = LocalDate.of(2030, 1, 10);
    private static final String PATH = "/projects/" + PROJECT + "/workers/" + WORKER + "/labor-rates";

    private final WorkerLaborRateRepository repository = mock(WorkerLaborRateRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectMemberQuery members = mock(ProjectMemberQuery.class);
    private final ProjectCalendarQuery calendars = mock(ProjectCalendarQuery.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<WorkerLaborRate> saved = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(members.canRead(PROJECT, WORKER)).thenReturn(true);
        when(access.requireCurrentUserId()).thenReturn(OWNER);
        when(calendars.today(PROJECT)).thenReturn(TODAY);
        when(ids.generate()).thenReturn("labor-rate-1");
        when(repository.findAllByProjectIdAndUserIdOrderBySetAtDescRateIdDesc(PROJECT, WORKER)).thenReturn(saved);
        when(repository.saveAndFlush(any(WorkerLaborRate.class))).thenAnswer(invocation -> {
            WorkerLaborRate row = invocation.getArgument(0);
            saved.add(row);
            return row;
        });
        WorkerLaborRateService service = new WorkerLaborRateService(repository, access, members, calendars, ids);
        mvc = MockMvcBuilders.standaloneSetup(new WorkerLaborRateController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e2147483647", "10e2147483647", "100e2147483647"})
    void anExtremeExponentIsRefusedBeforeNormalizingOrStoringTheRate(String number) throws Exception {
        // These small coefficients overflow scale immediately; do not substitute an exponent that allocates huge powers of ten.
        assertRefused(send(number, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "10000000000", "1.23456"})
    void existingSignWholeDigitAndFractionLimitsStillApply(String number) throws Exception {
        assertRefused(send(number, false));
    }

    @ParameterizedTest
    @CsvSource({"null,true", "0,false", "0e2147483647,false", "0e-2147483647,false", "9999999999.9999,false"})
    void acceptedRatesStillSaveExactlyFromTheStartOrTheProjectDate(String number, boolean fromStart) throws Exception {
        MockHttpServletResponse response = send(number, fromStart);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("success").asBoolean());
        assertEquals(1, saved.size());
        WorkerLaborRate row = saved.getFirst();
        assertEquals("null".equals(number) ? null : new BigDecimal(number).setScale(4), row.getHourlyRate());
        assertEquals(fromStart ? null : TODAY, row.getEffectiveFrom());
        assertEquals(PROJECT, row.getProjectId());
        assertEquals(WORKER, row.getUserId());
        assertEquals(OWNER, row.getSetBy());
        verify(repository).saveAndFlush(any(WorkerLaborRate.class));
        verify(access).requireProjectOwnerAccess(PROJECT);
        verify(members).canRead(PROJECT, WORKER);
    }

    private MockHttpServletResponse send(String number, boolean fromStart) throws Exception {
        String start = fromStart ? ",\"fromStart\":true" : ",\"effectiveFrom\":\"" + TODAY + "\"";
        return mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
            .content("{\"hourlyRate\":" + number + start + "}"))
            .andReturn().getResponse();
    }

    private void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("hourlyRate"));
        assertTrue(saved.isEmpty());
        verify(repository, never()).saveAndFlush(any(WorkerLaborRate.class));
        verify(access).requireProjectOwnerAccess(PROJECT);
        verify(members).canRead(PROJECT, WORKER);
    }
}
