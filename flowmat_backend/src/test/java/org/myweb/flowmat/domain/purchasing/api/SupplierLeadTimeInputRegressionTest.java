package org.myweb.flowmat.domain.purchasing.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.purchasing.application.SupplierService;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real HTTP binding and supplier validation; no database or expanded exponent coefficients. */
@Timeout(10)
class SupplierLeadTimeInputRegressionTest {
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SupplierController(new SupplierService(suppliers, access, ids)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        Supplier supplier = new Supplier();
        supplier.setSupplierId("supplier-1");
        supplier.setProjectId("project-1");
        supplier.setSupplierName("Original supplier");
        supplier.setActiveYn("Y");
        supplier.setLeadTimeDays(5);
        supplier.setVersion(0);
        when(suppliers.findForUpdate("supplier-1")).thenReturn(Optional.of(supplier));
        when(ids.generate()).thenReturn("supplier-1");
        when(access.requireCurrentUserId()).thenReturn("owner-1");
        when(suppliers.saveAndFlush(any(Supplier.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest(name = "{0} rejects leadTimeDays {1} without saving")
    @MethodSource("invalidDays")
    void invalidDaysAreClientErrors(boolean update, String token) throws Exception {
        MockHttpServletResponse response = send(update, token);

        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("leadTimeDays"),
            response.getContentAsString());
        verify(suppliers, never()).saveAndFlush(any(Supplier.class));
    }

    @ParameterizedTest(name = "{0} stores accepted leadTimeDays {1} as {2}")
    @MethodSource("validDays")
    void acceptedDaysRetainTheirMeaning(boolean update, String token, Integer expected) throws Exception {
        MockHttpServletResponse response = send(update, token);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        ArgumentCaptor<Supplier> saved = ArgumentCaptor.forClass(Supplier.class);
        verify(suppliers).saveAndFlush(saved.capture());
        assertEquals(expected, saved.getValue().getLeadTimeDays());
    }

    private MockHttpServletResponse send(boolean update, String token) throws Exception {
        String body = "{\"projectId\":\"project-1\",\"supplierName\":\"Updated supplier\",\"leadTimeDays\":"
            + token + ",\"expectedVersion\":0}";
        return mvc.perform((update ? put("/suppliers/supplier-1") : post("/suppliers"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private static Stream<Arguments> invalidDays() {
        return Stream.of(false, true).flatMap(update -> Stream.of(
            "10e2147483647", "100e2147483647", "1e2147483647", "1e-2147483647", "366", "1.5", "-1")
            .map(token -> Arguments.of(update, token)));
    }

    private static Stream<Arguments> validDays() {
        return Stream.of(false, true).flatMap(update -> Stream.of(
            new Object[] {"0", 0}, new Object[] {"365", 365}, new Object[] {"365.0000", 365},
            new Object[] {"3.65e2", 365}, new Object[] {"0e2147483647", 0},
            new Object[] {"0e-2147483647", 0}, new Object[] {"null", null})
            .map(value -> Arguments.of(update, value[0], value[1])));
    }
}
