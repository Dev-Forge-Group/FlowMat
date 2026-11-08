package org.myweb.flowmat.domain.production.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderUpdateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceImplTest {

    @Mock private WorkOrderRepository workOrderRepository;
    @Mock private ProductionRunRepository productionRunRepository;
    @Mock private ProductionPlanningReferences references;
    @org.mockito.Spy private WorkOrderBomSelection bomSelection = new WorkOrderBomSelection(null, null, references);
    @Mock private ProjectAccessService projectAccessService;
    @Mock private IdGenerator idGenerator;
    @Mock private StockAllocationService stockAllocationService;
    @Mock private org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand notifications;
    @Mock private org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery projectMemberQuery;
    @Mock private WorkOrderOptionalItems optionalItems;
    @Mock private WorkOrderOptionalItemCheck optionalItemCheck;

    @InjectMocks
    private WorkOrderServiceImpl workOrderService;

    @Test
    void simulatedOutputDoesNotCompleteAWorkOrderTarget() {
        WorkOrder order = new WorkOrder();
        order.setWorkOrderId("wo-1");
        order.setTargetQuantity(BigDecimal.TEN);
        ProductionRun simulation = new ProductionRun();
        simulation.setRunType("simulation");
        simulation.setRunStatus("finished");
        simulation.setActualOutputQty(BigDecimal.TEN);

        WorkOrderResponse response = WorkOrderServiceImpl.toResponse(order, List.of(simulation), List.of());

        assertThat(response.producedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
        "DRAFT, APPROVED, true",
        "DRAFT, CANCELLED, true",
        "DRAFT, IN_PROGRESS, false",
        "APPROVED, IN_PROGRESS, true",
        "APPROVED, CANCELLED, true",
        "IN_PROGRESS, COMPLETED, true",
        "IN_PROGRESS, CANCELLED, false",
        "COMPLETED, CANCELLED, false",
        "CANCELLED, APPROVED, false",
    })
    void stateMachine(WorkOrderStatus from, WorkOrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void createStartsAsNormalPriorityDraft() {
        when(idGenerator.generate()).thenReturn("wo-1");
        when(projectAccessService.requireCurrentUserId()).thenReturn("editor-1");
        when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkOrderResponse created = workOrderService.createWorkOrder(createRequest(null, null, null));

        assertThat(created.workOrderStatus()).isEqualTo("draft");
        assertThat(created.priority()).isEqualTo("normal");
        assertThat(created.workOrderNumber()).startsWith("WO-");
        assertThat(created.runCount()).isZero();
    }

    @Test
    void createRejectsPlannedEndBeforeStart() {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T09:00:00+09:00");

        assertThatThrownBy(() -> workOrderService.createWorkOrder(createRequest(null, start, start.minusHours(1))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Planned end");
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownPriority() {
        assertThatThrownBy(() -> workOrderService.createWorkOrder(createRequest("asap", null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Priority");
    }

    @ParameterizedTest
    @CsvSource({"create, Korean", "update, Korean", "create, emoji", "update, emoji"})
    void aTitleLongerThanTheDatabaseLimitIsRejectedBeforeSaving(String operation, String characters) {
        String title = ("emoji".equals(characters) ? "🧱" : "작").repeat(101);
        lenient().when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        if ("update".equals(operation)) {
            givenOrder("draft");
        }
        assertThatThrownBy(() -> {
            if ("create".equals(operation)) {
                workOrderService.createWorkOrder(new WorkOrderCreateRequest("project-1", title, null, null, null,
                    BigDecimal.ONE, null, null, null, null, null, null));
            } else {
                workOrderService.updateWorkOrder("wo-1", new WorkOrderUpdateRequest(title, null, null, BigDecimal.ONE,
                    null, null, null, null, null, null, null));
            }
        }).isInstanceOf(BusinessException.class).hasMessageContaining("workOrderTitle").hasMessageContaining("100");
        verify(workOrderRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"작", "🧱"})
    void theDatabaseLimitCountsCharactersRatherThanUtf16CodeUnits(String character) {
        String title = character.repeat(100);
        when(idGenerator.generate()).thenReturn("wo-1");
        when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkOrderResponse response = workOrderService.createWorkOrder(new WorkOrderCreateRequest("project-1", title,
            null, null, null, BigDecimal.ONE, null, null, null, null, null, null));

        assertThat(response.workOrderTitle()).isEqualTo(title);
    }

    @ParameterizedTest
    @CsvSource({"create, 0.00001", "update, 0.00001", "create, 0.000049", "update, 0.000049",
        "create, 10000000000", "update, 10000000000", "create, 9999999999.99995", "update, 9999999999.99995"})
    void aTargetOutsideStoredPrecisionIsRejectedBeforeSaving(String operation, BigDecimal quantity) {
        lenient().when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        if ("update".equals(operation)) {
            givenOrder("draft");
        }

        assertThatThrownBy(() -> {
            if ("create".equals(operation)) {
                workOrderService.createWorkOrder(targetRequest(quantity));
            } else {
                workOrderService.updateWorkOrder("wo-1", new WorkOrderUpdateRequest("Batch 1", null, null, quantity,
                    null, null, null, null, null, null, null));
            }
        }).isInstanceOf(BusinessException.class).hasMessageContaining("targetQuantity");
        verify(workOrderRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"0.00005, 0.0001", "1.23454, 1.2345", "1.23455, 1.2346", "9999999999.9999, 9999999999.9999"})
    void aTargetUsesTheDatabaseQuantityPrecision(BigDecimal requested, BigDecimal stored) {
        when(idGenerator.generate()).thenReturn("wo-1");
        when(workOrderRepository.save(any(WorkOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WorkOrderResponse response = workOrderService.createWorkOrder(targetRequest(requested));

        assertThat(response.targetQuantity()).isEqualByComparingTo(stored);
    }

    private WorkOrderCreateRequest targetRequest(BigDecimal quantity) {
        return new WorkOrderCreateRequest("project-1", "Batch 1", null, null, null, quantity,
            null, null, null, null, null, null);
    }

    @Test
    void approvedOrdersAreFrozen() {
        givenOrder("approved");

        assertThatThrownBy(() -> workOrderService.updateWorkOrder("wo-1",
            new WorkOrderUpdateRequest("New title", null, null, null, null, null, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only draft");
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void approveRequiresProjectOwner() {
        givenOrder("draft");
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project owner access is required.")).when(projectAccessService).requireProjectOwnerAccess("project-1");

        assertThatThrownBy(() -> workOrderService.approveWorkOrder("wo-1")).isInstanceOf(BusinessException.class);
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void approveRecordsApprover() {
        WorkOrder order = givenOrder("draft");
        when(projectAccessService.requireCurrentUserId()).thenReturn("owner-1");
        when(workOrderRepository.save(order)).thenReturn(order);
        when(productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(anyCollection(), eq("N"))).thenReturn(List.of());

        WorkOrderResponse approved = workOrderService.approveWorkOrder("wo-1");

        assertThat(approved.workOrderStatus()).isEqualTo("approved");
        assertThat(approved.approvedBy()).isEqualTo("owner-1");
        assertThat(approved.approvedAt()).isNotNull();
    }

    @Test
    void cannotCancelCompletedOrder() {
        givenOrder("completed");

        assertThatThrownBy(() -> workOrderService.cancelWorkOrder("wo-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot move from completed to cancelled");
    }

    @Test
    void completeWaitsForOpenRuns() {
        givenOrder("in_progress");
        when(productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(anyCollection(), eq("N")))
            .thenReturn(List.of(run("finished", "10"), run("running", "0")));

        assertThatThrownBy(() -> workOrderService.completeWorkOrder("wo-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Finish every production run");
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void completeSumsProducedQuantity() {
        WorkOrder order = givenOrder("in_progress");
        when(productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(anyCollection(), eq("N")))
            .thenReturn(List.of(run("finished", "10"), run("finished", "2.5")));
        when(workOrderRepository.save(order)).thenReturn(order);

        WorkOrderResponse completed = workOrderService.completeWorkOrder("wo-1");

        assertThat(completed.workOrderStatus()).isEqualTo("completed");
        assertThat(completed.producedQuantity()).isEqualByComparingTo("12.5");
        assertThat(completed.runCount()).isEqualTo(2);
        assertThat(completed.actualEndAt()).isNotNull();
    }

    private WorkOrder givenOrder(String status) {
        WorkOrder order = new WorkOrder();
        order.setWorkOrderId("wo-1");
        order.setWorkOrderNumber("WO-1");
        order.setProjectId("project-1");
        order.setWorkOrderTitle("Batch 1");
        order.setWorkOrderStatus(status);
        order.setDeletedYn("N");
        when(workOrderRepository.findForUpdate("wo-1")).thenReturn(Optional.of(order));
        return order;
    }

    private static ProductionRun run(String status, String actualOutput) {
        ProductionRun run = new ProductionRun();
        run.setWorkOrderId("wo-1");
        run.setRunStatus(status);
        run.setActualOutputQty(new BigDecimal(actualOutput));
        return run;
    }

    private static WorkOrderCreateRequest createRequest(String priority, OffsetDateTime start, OffsetDateTime end) {
        return new WorkOrderCreateRequest("project-1", "Batch 1", null, null, null, new BigDecimal("100"),
            priority, start, end, null, null, null);
    }
}
