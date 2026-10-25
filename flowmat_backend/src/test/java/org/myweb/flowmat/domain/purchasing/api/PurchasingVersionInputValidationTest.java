package org.myweb.flowmat.domain.purchasing.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseSettingRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierItemRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierRequest;
import org.myweb.flowmat.domain.purchasing.application.PurchaseSettingService;
import org.myweb.flowmat.domain.purchasing.application.SupplierItemService;
import org.myweb.flowmat.domain.purchasing.application.SupplierService;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real JSON binding and controller error responses; service mocks keep this regression independent of a database. */
class PurchasingVersionInputValidationTest {

    private final PurchaseSettingService settings = mock(PurchaseSettingService.class);
    private final SupplierService suppliers = mock(SupplierService.class);
    private final SupplierItemService supplierItems = mock(SupplierItemService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PurchaseSettingController(settings), new SupplierController(suppliers),
            new SupplierItemController(supplierItems)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest(name = "{0} refuses fractional expectedVersion {1}")
    @MethodSource("fractionalVersions")
    void aFractionalVersionIsRejectedBeforeAnyServiceCanApplyTheRequest(Endpoint endpoint, String version) throws Exception {
        MockHttpServletResponse response = send(endpoint, version);

        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("expectedVersion"),
            response.getContentAsString());
        verifyNoInteractions(settings, suppliers, supplierItems);
    }

    @ParameterizedTest(name = "{0} preserves exact expectedVersion {1}")
    @MethodSource("exactVersions")
    void exactVersionFormatsStillReachTheServiceAsZero(Endpoint endpoint, String version) throws Exception {
        MockHttpServletResponse response = send(endpoint, version);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("success").asBoolean(), response.getContentAsString());
        switch (endpoint) {
            case PURCHASE_SETTINGS -> {
                ArgumentCaptor<PurchaseSettingRequest> request = ArgumentCaptor.forClass(PurchaseSettingRequest.class);
                verify(settings).set(request.capture());
                assertEquals(Long.valueOf(0), request.getValue().expectedVersion());
                verifyNoInteractions(suppliers, supplierItems);
            }
            case SUPPLIER -> {
                ArgumentCaptor<SupplierRequest> request = ArgumentCaptor.forClass(SupplierRequest.class);
                verify(suppliers).update(org.mockito.ArgumentMatchers.eq("supplier-input"), request.capture());
                assertEquals(Long.valueOf(0), request.getValue().expectedVersion());
                verifyNoInteractions(settings, supplierItems);
            }
            case SUPPLIER_ITEM -> {
                ArgumentCaptor<SupplierItemRequest> request = ArgumentCaptor.forClass(SupplierItemRequest.class);
                verify(supplierItems).set(request.capture());
                assertEquals(Long.valueOf(0), request.getValue().expectedVersion());
                verifyNoInteractions(settings, suppliers);
            }
        }
    }

    private MockHttpServletResponse send(Endpoint endpoint, String version) throws Exception {
        return mvc.perform(put(endpoint.path).contentType(MediaType.APPLICATION_JSON)
            .content(endpoint.fields + ",\"expectedVersion\":" + version + "}"))
            .andReturn().getResponse();
    }

    private static Stream<Arguments> fractionalVersions() {
        return versions("0.5", "-0.5", "1.0001");
    }

    private static Stream<Arguments> exactVersions() {
        return versions("0", "0.0", "\"0\"");
    }

    private static Stream<Arguments> versions(String... values) {
        return Arrays.stream(Endpoint.values()).flatMap(endpoint -> Arrays.stream(values)
            .map(version -> Arguments.of(endpoint, version)));
    }

    private enum Endpoint {
        PURCHASE_SETTINGS("/purchase-settings", "{\"projectId\":\"purchasing-input-project\",\"approvalLimit\":10.25"),
        SUPPLIER("/suppliers/supplier-input", "{\"supplierName\":\"Input supplier\",\"leadTimeDays\":5,\"active\":true"),
        SUPPLIER_ITEM("/supplier-items", "{\"projectId\":\"purchasing-input-project\",\"itemId\":\"item-input\","
            + "\"supplierId\":\"supplier-input\",\"supplierItemCode\":\"CODE-1\",\"unitPrice\":1.25");

        private final String path;
        private final String fields;

        Endpoint(String path, String fields) {
            this.path = path;
            this.fields = fields;
        }
    }
}
