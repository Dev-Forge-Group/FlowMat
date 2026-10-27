package org.myweb.flowmat.domain.inventory.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.inventory.application.InventoryCountPlanService;
import org.myweb.flowmat.domain.inventory.application.InventoryCountService;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlan;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlanLine;
import org.myweb.flowmat.domain.inventory.repository.InventoryCountPlanLineRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryCountPlanRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Timeout(10)
class CountPlanQuantityUnderflowRegressionTest {
    private static final String PROJECT = "count-plan-project";
    private static final String PLAN = "count-plan";
    private static final String LINE = "count-line";
    private static final String ACTOR = "count-owner";
    private static final String PATH = "/inventory-count-plans/" + PLAN + "/lines/" + LINE;

    private final InventoryCountPlanRepository plans = mock(InventoryCountPlanRepository.class);
    private final InventoryCountPlanLineRepository entries = mock(InventoryCountPlanLineRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final InventoryCountPlanLine line = new InventoryCountPlanLine();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InventoryCountPlan plan = new InventoryCountPlan();
        plan.setPlanId(PLAN);
        plan.setProjectId(PROJECT);
        plan.setStatus("open");
        line.setLineId(LINE);
        line.setPlanId(PLAN);
        line.setInventoryId("count-stock");
        line.setItemId("count-item");
        line.setBaselineQuantity(new BigDecimal("10.0000"));
        line.setCheckpointQuantity(new BigDecimal("10.0000"));
        line.setEntryVersion(0L);
        when(plans.findForUpdate(PLAN)).thenReturn(Optional.of(plan));
        when(entries.findAllByPlanIdOrderByInventoryIdAsc(PLAN)).thenReturn(List.of(line));
        when(entries.saveAndFlush(any(InventoryCountPlanLine.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(access.requireCurrentUserId()).thenReturn(ACTOR);
        when(access.resolveCurrentUserRole(PROJECT)).thenReturn("owner");
        InventoryCountPlanService service = new InventoryCountPlanService(plans, entries,
            mock(InventoryRepository.class), mock(InventoryCountService.class), access,
            mock(ProjectMemberQuery.class), mock(NotificationCommand.class), mock(IdGenerator.class));
        mvc = MockMvcBuilders.standaloneSetup(new InventoryCountPlanController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @ParameterizedTest
    @CsvSource({
        "1e-2147483647,0.0000",
        "0,0.0000",
        "0.000049999,0.0000",
        "0.00005,0.0001",
        "1.23456,1.2346",
        "9999999999.9999499,9999999999.9999"
    })
    void quantitiesInsideTheRangeAreRoundedHalfUpAndSaved(String number, String expected) throws Exception {
        // The maximum scale overflows JDK power construction immediately; never replace it with a large intermediate exponent.
        MockHttpServletResponse response = send(number, 0L);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("success").asBoolean());
        assertEquals(new BigDecimal(expected), line.getCountedQuantity());
        assertEquals(ACTOR, line.getCountedBy());
        assertNotNull(line.getCountedAt());
        verify(entries).saveAndFlush(same(line));
        verify(access).requireProjectWriteAccess(PROJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "9999999999.99995", "10000000000"})
    void outOfRangeQuantitiesAreRefusedWithoutChangingTheMeasurement(String number) throws Exception {
        MockHttpServletResponse response = send(number, 0L);

        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("countedQuantity"));
        assertNull(line.getCountedQuantity());
        assertNull(line.getCountedBy());
        assertNull(line.getCountedAt());
        verify(entries, never()).saveAndFlush(any(InventoryCountPlanLine.class));
        verify(access).requireProjectWriteAccess(PROJECT);
    }

    @Test
    void aStaleVersionCannotReplaceAnotherCountersMeasurement() throws Exception {
        BigDecimal recorded = new BigDecimal("2.0000");
        OffsetDateTime recordedAt = OffsetDateTime.parse("2030-01-10T12:00:00Z");
        line.setEntryVersion(1L);
        line.setCountedQuantity(recorded);
        line.setCountedBy("another-counter");
        line.setCountedAt(recordedAt);

        MockHttpServletResponse response = send("1", 0L);

        assertEquals(409, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("changed by another request"));
        assertEquals(recorded, line.getCountedQuantity());
        assertEquals("another-counter", line.getCountedBy());
        assertEquals(recordedAt, line.getCountedAt());
        assertEquals(1L, line.getEntryVersion());
        verify(entries, never()).saveAndFlush(any(InventoryCountPlanLine.class));
        verify(access).requireProjectWriteAccess(PROJECT);
    }

    @Test
    void theSameCounterCanReplayAnUnderflowingQuantityWithoutWritingAgain() throws Exception {
        BigDecimal recorded = new BigDecimal("0.0000");
        OffsetDateTime recordedAt = OffsetDateTime.parse("2030-01-10T12:00:00Z");
        line.setEntryVersion(1L);
        line.setCountedQuantity(recorded);
        line.setCountedBy(ACTOR);
        line.setCountedAt(recordedAt);

        MockHttpServletResponse response = send("1e-2147483647", 0L);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("success").asBoolean());
        assertEquals(recorded, line.getCountedQuantity());
        assertEquals(ACTOR, line.getCountedBy());
        assertEquals(recordedAt, line.getCountedAt());
        assertEquals(1L, line.getEntryVersion());
        verify(entries, never()).saveAndFlush(any(InventoryCountPlanLine.class));
        verify(access).requireProjectWriteAccess(PROJECT);
    }

    private MockHttpServletResponse send(String number, long expectedVersion) throws Exception {
        return mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedEntryVersion\":" + expectedVersion + ",\"countedQuantity\":" + number + "}"))
            .andReturn().getResponse();
    }
}
