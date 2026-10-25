package org.myweb.flowmat.domain.purchasing.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockPlaceQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReceiptCommand;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRecordQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReturnCommand;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseCreditRequest;
import org.myweb.flowmat.domain.purchasing.application.PurchaseOrderService;
import org.myweb.flowmat.domain.purchasing.application.PurchaseSettingService;
import org.myweb.flowmat.domain.purchasing.application.SupplierItemService;
import org.myweb.flowmat.domain.purchasing.application.SupplierService;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseApprovalStep;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCredit;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCreditChange;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrder;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderLine;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseReturn;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseSetting;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.myweb.flowmat.domain.purchasing.domain.entity.SupplierItem;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseApprovalStepRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseCreditChangeRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseCreditRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderApprovalRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderDateChangeRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderLineRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseReceiptRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseReturnRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseSettingRepository;
import org.myweb.flowmat.domain.purchasing.repository.SupplierItemRepository;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.domain.quality.application.publicapi.NonconformityQuery;
import org.myweb.flowmat.global.exception.GlobalExceptionHandler;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real JSON and public service validation; repository mocks keep magnitude checks independent of a database. */
@Timeout(10)
class PurchasingMagnitudeInputRegressionTest {

    private static final String PROJECT = "magnitude-project";
    private static final String ITEM = "magnitude-item";
    private static final String SUPPLIER = "magnitude-supplier";
    private static final String OWNER = "magnitude-owner";
    private static final String APPROVER = "magnitude-approver";
    private static final String ORDER = "magnitude-order";
    private static final String RETURN = "magnitude-return";
    private static final String SUPPLIER_NAME = "Magnitude supplier";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PurchaseSettingRepository settings = mock(PurchaseSettingRepository.class);
    private final PurchaseApprovalStepRepository steps = mock(PurchaseApprovalStepRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderLineRepository lines = mock(PurchaseOrderLineRepository.class);
    private final PurchaseReturnRepository returns = mock(PurchaseReturnRepository.class);
    private final PurchaseCreditRepository credits = mock(PurchaseCreditRepository.class);
    private final PurchaseCreditChangeRepository creditChanges = mock(PurchaseCreditChangeRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final SupplierItemRepository supplierItems = mock(SupplierItemRepository.class);
    private final SupplierService supplierList = mock(SupplierService.class);
    private final ProjectMemberQuery projectMembers = mock(ProjectMemberQuery.class);
    private final CatalogQuery catalog = mock(CatalogQuery.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PurchaseSettingService settingService = new PurchaseSettingService(settings, steps, orders, projectMembers, access, ids);
        SupplierItemService supplierItemService = new SupplierItemService(supplierItems, suppliers, catalog, access, ids);
        PurchaseOrderService orderService = new PurchaseOrderService(orders, lines, mock(PurchaseReceiptRepository.class),
            mock(PurchaseOrderDateChangeRepository.class), returns, credits, creditChanges, mock(PurchaseOrderApprovalRepository.class),
            supplierList, suppliers, supplierItems, settingService, mock(NotificationCommand.class), projectMembers, catalog,
            mock(StockReceiptCommand.class), mock(StockReturnCommand.class), mock(StockRecordQuery.class), mock(LotQuery.class),
            mock(NonconformityQuery.class), mock(StockPlaceQuery.class), access, ids);
        mvc = MockMvcBuilders.standaloneSetup(new PurchaseSettingController(settingService),
                new SupplierItemController(supplierItemService), new PurchaseOrderController(orderService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

        CatalogItemView item = new CatalogItemView(ITEM, PROJECT, "MAG-1", "Magnitude item", null, "kg", "mass",
            null, null, "active", "N", null, null, null, "N");
        when(catalog.findItems(anyCollection())).thenReturn(Map.of(ITEM, item));
        when(access.requireCurrentUserId()).thenReturn(OWNER);
        when(projectMembers.ownerOf(PROJECT)).thenReturn(OWNER);
        when(projectMembers.canWrite(PROJECT, APPROVER)).thenReturn(true);
        when(supplierList.resolve(PROJECT, SUPPLIER_NAME)).thenReturn(SUPPLIER_NAME);
        AtomicInteger sequence = new AtomicInteger();
        when(ids.generate()).thenAnswer(invocation -> "magnitude-" + sequence.incrementAndGet());
        when(settings.saveAndFlush(any(PurchaseSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(supplierItems.saveAndFlush(any(SupplierItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Supplier supplier = new Supplier();
        supplier.setSupplierId(SUPPLIER);
        supplier.setProjectId(PROJECT);
        supplier.setSupplierName(SUPPLIER_NAME);
        supplier.setActiveYn("Y");
        when(suppliers.findById(SUPPLIER)).thenReturn(Optional.of(supplier));

        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(ORDER);
        order.setProjectId(PROJECT);
        order.setOrderNumber("PO-0001");
        order.setSupplierName(SUPPLIER_NAME);
        order.setStatus("received");
        when(orders.findForUpdate(ORDER)).thenReturn(Optional.of(order));
        PurchaseReturn back = new PurchaseReturn();
        back.setReturnId(RETURN);
        back.setPurchaseOrderId(ORDER);
        when(returns.findById(RETURN)).thenReturn(Optional.of(back));
    }

    @ParameterizedTest(name = "Jackson keeps the bounded exponent token {0} as BigDecimal")
    @ValueSource(strings = {"1e2147483647", "100e2147483647"})
    void exponentInputSafelyBindsWithoutExpandingItsCoefficient(String number) throws Exception {
        PurchaseCreditRequest request = mapper.readValue("{\"amount\":" + number + "}", PurchaseCreditRequest.class);

        assertEquals(-2147483647, request.amount().scale());
        assertEquals(new BigInteger(number.substring(0, number.indexOf('e'))), request.amount().unscaledValue());
    }

    @ParameterizedTest(name = "{0} rejects overflowing exponent {1} as input error")
    @MethodSource("overflowingExponents")
    void excessiveExponentIsRejectedWithoutAnUnexpectedServerError(Endpoint endpoint, String number) throws Exception {
        assertTooLarge(send(endpoint, number));
    }

    @ParameterizedTest(name = "{0} retains its ordinary whole-digit limit")
    @MethodSource("endpoints")
    void oneMoreWholeDigitIsStillRejected(Endpoint endpoint) throws Exception {
        assertTooLarge(send(endpoint, endpoint.credit ? "100000000000000000000" : "10000000000"));
    }

    @ParameterizedTest(name = "{0} retains its maximum valid magnitude and four decimals")
    @MethodSource("endpoints")
    void maximumAllowedMagnitudeStillReachesPersistenceUnchanged(Endpoint endpoint) throws Exception {
        String number = endpoint.credit ? "99999999999999999999.9999" : "9999999999.9999";
        MockHttpServletResponse response = send(endpoint, number);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("success").asBoolean(), response.getContentAsString());
        assertSavedMagnitude(endpoint, new BigDecimal(number));
    }

    private MockHttpServletResponse send(Endpoint endpoint, String number) throws Exception {
        String body;
        MockHttpServletRequestBuilder request;
        switch (endpoint) {
            case APPROVAL_LIMIT -> {
                request = put("/purchase-settings");
                body = "{\"projectId\":\"" + PROJECT + "\",\"approvalLimit\":" + number + "}";
            }
            case APPROVAL_STEP_VALUE -> {
                request = put("/purchase-settings");
                body = "{\"projectId\":\"" + PROJECT + "\",\"approvalSteps\":[{\"fromValue\":" + number
                    + ",\"approverId\":\"" + APPROVER + "\"}]}";
            }
            case SUPPLIER_ITEM_PRICE -> {
                request = put("/supplier-items");
                body = "{\"projectId\":\"" + PROJECT + "\",\"itemId\":\"" + ITEM + "\",\"supplierId\":\"" + SUPPLIER
                    + "\",\"unitPrice\":" + number + "}";
            }
            case DRAFT_QUANTITY, DRAFT_UNIT_PRICE -> {
                request = post("/purchase-orders");
                body = "{\"projectId\":\"" + PROJECT + "\",\"supplierName\":\"" + SUPPLIER_NAME
                    + "\",\"lines\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":"
                    + (endpoint == Endpoint.DRAFT_QUANTITY ? number : "1") + ",\"unitPrice\":"
                    + (endpoint == Endpoint.DRAFT_UNIT_PRICE ? number : "1") + "}]}";
            }
            case CREDIT_AMOUNT, CREDIT_CHANGE_AMOUNT -> {
                String path = "/purchase-orders/" + ORDER + "/returns/" + RETURN + "/credit";
                request = endpoint == Endpoint.CREDIT_AMOUNT ? post(path) : put(path);
                body = "{\"amount\":" + number
                    + (endpoint == Endpoint.CREDIT_CHANGE_AMOUNT ? ",\"reason\":\"Supplier corrected the credit\"}" : "}");
                if (endpoint == Endpoint.CREDIT_CHANGE_AMOUNT) {
                    PurchaseCredit original = new PurchaseCredit();
                    original.setReturnId(RETURN);
                    original.setPurchaseOrderId(ORDER);
                    original.setAmount(BigDecimal.ONE);
                    when(credits.findById(RETURN)).thenReturn(Optional.of(original));
                }
            }
            default -> throw new AssertionError("Missing endpoint " + endpoint);
        }
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private void assertTooLarge(MockHttpServletResponse response) throws Exception {
        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(mapper.readTree(response.getContentAsString()).path("message").asText().contains("too large"),
            response.getContentAsString());
        verify(settings, never()).saveAndFlush(any());
        verify(steps, never()).saveAllAndFlush(any());
        verify(supplierItems, never()).saveAndFlush(any());
        verify(orders, never()).saveAndFlush(any());
        verify(lines, never()).saveAllAndFlush(any());
        verify(credits, never()).saveAndFlush(any());
        verify(creditChanges, never()).saveAndFlush(any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertSavedMagnitude(Endpoint endpoint, BigDecimal expected) {
        BigDecimal actual;
        switch (endpoint) {
            case APPROVAL_LIMIT -> {
                ArgumentCaptor<PurchaseSetting> saved = ArgumentCaptor.forClass(PurchaseSetting.class);
                verify(settings).saveAndFlush(saved.capture());
                actual = saved.getValue().getApprovalLimit();
            }
            case APPROVAL_STEP_VALUE -> {
                ArgumentCaptor<List> saved = ArgumentCaptor.forClass(List.class);
                verify(steps).saveAllAndFlush(saved.capture());
                actual = ((PurchaseApprovalStep) saved.getValue().get(0)).getFromValue();
            }
            case SUPPLIER_ITEM_PRICE -> {
                ArgumentCaptor<SupplierItem> saved = ArgumentCaptor.forClass(SupplierItem.class);
                verify(supplierItems).saveAndFlush(saved.capture());
                actual = saved.getValue().getUnitPrice();
            }
            case DRAFT_QUANTITY, DRAFT_UNIT_PRICE -> {
                ArgumentCaptor<List> saved = ArgumentCaptor.forClass(List.class);
                verify(lines).saveAllAndFlush(saved.capture());
                PurchaseOrderLine line = (PurchaseOrderLine) saved.getValue().get(0);
                actual = endpoint == Endpoint.DRAFT_QUANTITY ? line.getQuantity() : line.getUnitPrice();
            }
            case CREDIT_AMOUNT -> {
                ArgumentCaptor<PurchaseCredit> saved = ArgumentCaptor.forClass(PurchaseCredit.class);
                verify(credits).saveAndFlush(saved.capture());
                actual = saved.getValue().getAmount();
            }
            case CREDIT_CHANGE_AMOUNT -> {
                ArgumentCaptor<PurchaseCreditChange> saved = ArgumentCaptor.forClass(PurchaseCreditChange.class);
                verify(creditChanges).saveAndFlush(saved.capture());
                actual = saved.getValue().getAmount();
            }
            default -> throw new AssertionError("Missing endpoint " + endpoint);
        }
        assertEquals(0, expected.compareTo(actual));
        assertEquals(4, actual.scale());
    }

    private static Stream<Arguments> overflowingExponents() {
        // Both tokens overflow scale immediately; never use an exponent that attempts a huge coefficient allocation.
        return Arrays.stream(Endpoint.values()).flatMap(endpoint -> Stream.of("1e2147483647", "100e2147483647")
            .map(number -> Arguments.of(endpoint, number)));
    }

    private static Stream<Endpoint> endpoints() {
        return Arrays.stream(Endpoint.values());
    }

    private enum Endpoint {
        APPROVAL_LIMIT(false),
        APPROVAL_STEP_VALUE(false),
        SUPPLIER_ITEM_PRICE(false),
        DRAFT_QUANTITY(false),
        DRAFT_UNIT_PRICE(false),
        CREDIT_AMOUNT(true),
        CREDIT_CHANGE_AMOUNT(true);

        private final boolean credit;

        Endpoint(boolean credit) {
            this.credit = credit;
        }
    }
}
