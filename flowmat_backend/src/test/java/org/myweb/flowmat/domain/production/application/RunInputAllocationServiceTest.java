package org.myweb.flowmat.domain.production.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockView;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.request.RunInputAllocationRequest;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.StockAllocation;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.StockAllocationRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;

@ExtendWith(MockitoExtension.class)
class RunInputAllocationServiceTest {

    @Mock private ProductionRunService productionRunService;
    @Mock private ProductionRunRepository productionRunRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private UnitConverter unitConverter;
    @Mock private StockAllocationRepository stockAllocationRepository;
    @Mock private StockAllocationService stockAllocationService;
    @Mock private org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository productionRunItemRepository;
    @Mock private ProductionPlanningReferences references;
    @Mock private org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;

    @InjectMocks private RunInputAllocationService service;

    private ProductionRun run;
    private LotView soon;
    private StockAllocation ownSoon;
    private StockAllocation ownLate;

    @BeforeEach
    void stockReservedForTheRunOrder() {
        run = new ProductionRun();
        run.setProductionRunId("run");
        run.setProjectId("project");
        run.setWorkOrderId("order");
        run.setRunType("actual");
        run.setRunStatus("running");
        CatalogItemView item = new CatalogItemView("item", "project", "FLOUR", null, "unit_kg", null, null, null, null, null, "Y", null, null, null, "N");
        soon = lot("soon", 5);
        LotView late = lot("late", 30);
        ownSoon = allocation("soon_stock", "5");
        ownLate = allocation("late_stock", "3");
        lenient().when(productionRunRepository.findForUpdate("run")).thenReturn(Optional.of(run));
        lenient().when(references.item("item")).thenReturn(Optional.of(item));
        lenient().when(unitConverter.toItemUnit(any(BigDecimal.class), eq("kg"), eq("unit_kg")))
            .thenAnswer(invocation -> new UnitConverter.Conversion(invocation.getArgument(0), "kg", "kg", false));
        lenient().when(projectCalendar.today("project")).thenReturn(java.time.LocalDate.now());
        lenient().when(references.lockItemStock("project", "item")).thenReturn(List.of(stock("late_stock", "late"), stock("soon_stock", "soon")));
        lenient().when(references.lots(any())).thenReturn(List.of(late, soon));
        lenient().when(stockAllocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc("order", "open"))
            .thenReturn(List.of(ownSoon, ownLate));
    }

    @Test
    void anActualRunCanTakeItsOwnReservedStockInExpiryOrder() {
        assertEquals(2, service.allocate("run", request("8")).size());
        ArgumentCaptor<ProductionRunItemRecordRequest> records = ArgumentCaptor.forClass(ProductionRunItemRecordRequest.class);
        verify(productionRunService).recordRunItemPart(eq("run"), records.capture(), eq(1));
        verify(productionRunService).recordRunItemPart(eq("run"), records.capture(), eq(2));
        assertEquals("soon_stock", records.getAllValues().get(0).inventoryId());
        assertEquals(0, new BigDecimal("5").compareTo(records.getAllValues().get(0).actualQty()));
        assertEquals("late_stock", records.getAllValues().get(1).inventoryId());
        assertEquals(0, new BigDecimal("3").compareTo(records.getAllValues().get(1).actualQty()));
    }

    @Test
    void anExpiredLotDoesNotBecomeUsableBecauseItWasReservedForTheOrder() {
        lenient().when(references.lots(any())).thenReturn(List.of(lot("late", 30), lot("soon", -1)));
        BusinessException exception = assertThrows(BusinessException.class, () -> service.allocate("run", request("8")));
        assertTrue(exception.getMessage().contains("Only 3 kg"));
        verifyNoInteractions(productionRunService);
    }

    @Test
    void consumedAndReleasedReservationsAreExcludedFromTheUsableQuantity() {
        ownSoon.setConsumedQuantity(new BigDecimal("2"));
        ownSoon.setReleasedQuantity(BigDecimal.ONE);
        ownLate.setConsumedQuantity(new BigDecimal("2"));
        BusinessException exception = assertThrows(BusinessException.class, () -> service.allocate("run", request("4")));
        assertTrue(exception.getMessage().contains("Only 3 kg"));
        verifyNoInteractions(productionRunService);
    }

    @Test
    void quantityIsRoundedBeforeCheckingTheUsableStockBoundary() {
        assertEquals(2, service.allocate("run", request("8.00004")).size());
        ArgumentCaptor<ProductionRunItemRecordRequest> records = ArgumentCaptor.forClass(ProductionRunItemRecordRequest.class);
        verify(productionRunService, times(2)).recordRunItemPart(eq("run"), records.capture(), anyInt());
        BigDecimal total = records.getAllValues().stream().map(ProductionRunItemRecordRequest::actualQty)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("8").compareTo(total));
    }

    @Test
    void aRoundedRemainderDoesNotCreateAZeroStockRecordingOnAnotherLot() {
        assertEquals(1, service.allocate("run", request("5.00004")).size());
        ArgumentCaptor<ProductionRunItemRecordRequest> record = ArgumentCaptor.forClass(ProductionRunItemRecordRequest.class);
        verify(productionRunService).recordRunItemPart(eq("run"), record.capture(), anyInt());
        assertEquals("soon_stock", record.getValue().inventoryId());
        assertEquals(0, new BigDecimal("5").compareTo(record.getValue().actualQty()));
    }

    @Test
    void aQuantityBelowStoredPrecisionIsRejectedBeforeRecording() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.allocate("run", request("0.00001")));
        assertTrue(exception.getMessage().contains("quantity"));
        assertTrue(exception.getMessage().contains("0.0001"));
        verifyNoInteractions(productionRunService);
    }

    @Test
    void aHalfwayQuantityUsesTheRoundedStockAmount() {
        assertEquals(1, service.allocate("run", request("0.00005")).size());
        ArgumentCaptor<ProductionRunItemRecordRequest> record = ArgumentCaptor.forClass(ProductionRunItemRecordRequest.class);
        verify(productionRunService).recordRunItemPart(eq("run"), record.capture(), anyInt());
        assertEquals(0, new BigDecimal("0.0001").compareTo(record.getValue().actualQty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"simulation", "test", "dry_run"})
    void aNonPhysicalRunCannotSpendThePhysicalReservation(String runType) {
        run.setRunType(runType);
        assertThrows(BusinessException.class, () -> service.allocate("run", request("1")));
        verifyNoInteractions(productionRunService, stockAllocationRepository, stockAllocationService);
    }

    private RunInputAllocationRequest request(String quantity) {
        return new RunInputAllocationRequest("item", new BigDecimal(quantity), "kg", null, null);
    }

    private LotView lot(String id, int expiresInDays) {
        return new LotView(id, null, null, id, "reserved", null, LocalDate.now().plusDays(expiresInDays), null);
    }

    private StockView stock(String id, String lotId) {
        return new StockView(id, null, null, lotId, null, new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, "available", null);
    }

    private StockAllocation allocation(String stockId, String quantity) {
        StockAllocation allocation = new StockAllocation();
        allocation.setWorkOrderId("order");
        allocation.setInventoryId(stockId);
        allocation.setQuantity(new BigDecimal(quantity));
        allocation.setConsumedQuantity(BigDecimal.ZERO);
        allocation.setReleasedQuantity(BigDecimal.ZERO);
        return allocation;
    }
}
