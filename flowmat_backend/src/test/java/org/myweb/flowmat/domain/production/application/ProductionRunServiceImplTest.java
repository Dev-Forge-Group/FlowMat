package org.myweb.flowmat.domain.production.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.junit.jupiter.api.BeforeEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemFactsQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockFactsQuery;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.bom.application.BomService;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.inventory.application.InventoryCommandService;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunFinishRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStartRequest;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.rule.application.FlowRuleEngineService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class ProductionRunServiceImplTest {

    @Mock private ProductionRunRepository productionRunRepository;
    @Mock private ProductionRunItemRepository productionRunItemRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private WorkflowAccess workflowAccess;
    @Mock private InventoryCommandService inventoryCommandService;
    @Mock private FlowRuleEngineService flowRuleEngineService;
    @Mock private IdGenerator idGenerator;
    @Mock private WorkOrderRepository workOrderRepository;
    @Mock private UnitConverter unitConverter;
    @Mock private BomService bomService;
    @Mock private org.myweb.flowmat.domain.inventory.application.LotService lotService;
    @Mock private ProductionFlowRunAdapter productionFlowRunAdapter;
    @Mock private StockAllocationService stockAllocationService;
    @Mock private RunInstructionService runInstructionService;

    @Mock private WorkflowProductionQuery workflowReferences;
    @Mock private CatalogItemFactsQuery itemFacts;
    @Mock private StockFactsQuery stockFacts;
    @Spy private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setupReferences() {
        lenient().when(workflowReferences.lockWorkflow(any(String.class)))
            .thenAnswer(call -> workflowReferences.findWorkflow(call.getArgument(0)).orElseThrow());
    }
    private String facts(Object row) {
        try { return new ObjectMapper().findAndRegisterModules().writeValueAsString(row); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @InjectMocks
    private ProductionRunServiceImpl productionRunService;

    @Test
    void stockMovesByTheQuantityConvertedToTheItemsUnit() {
        givenRun("running");
        givenItem();
        Inventory inventory = givenInventory("item-1", "10");
        when(unitConverter.toItemUnit(new BigDecimal("500"), "g", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(new BigDecimal("0.5"), "g", "kg", true));
        when(idGenerator.generate()).thenReturn("run-item-1");
        when(productionRunItemRepository.save(any(ProductionRunItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(projectAccessService.requireCurrentUserId()).thenReturn("user-1");

        productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, null, "inv-1", "item-1", "input", new BigDecimal("500"), null, "g"));

        // Stock moves only through the inventory command (conditional atomic UPDATE + history), never a direct save.
        verify(stockFacts).findActiveStock("inv-1");
        verify(inventoryCommandService).apply(argThat(movement ->
            movement.inventoryId().equals(inventory.getInventoryId())
                && movement.type() == InventoryTransactionType.PRODUCTION_INPUT
                && movement.quantityDelta().compareTo(new BigDecimal("-0.5")) == 0
                && movement.note().contains("500 g = 0.5 kg")
                && movement.actorUserId().equals("user-1")));
    }

    @Test
    void simulationRecordingKeepsStockUnchanged() {
        ProductionRun run = givenRun("running");
        run.setRunType("simulation");
        givenItem();
        givenInventory("item-1", "10");
        when(unitConverter.toItemUnit(BigDecimal.ONE, "kg", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(BigDecimal.ONE, "kg", "kg", false));
        when(idGenerator.generate()).thenReturn("simulation-item-1");
        when(productionRunItemRepository.save(any(ProductionRunItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, null, "inv-1", "item-1", "input", BigDecimal.ONE, null, "kg"));

        verifyNoInteractions(inventoryCommandService, stockAllocationService, lotService);
    }

    @Test
    void simulationOutputDoesNotMarkARealLotAsProduced() {
        ProductionRun run = givenRun("running");
        run.setRunType("simulation");
        Item item = givenItem();
        item.setLotManageYn("Y");
        Inventory inventory = givenInventory("item-1", "0");
        inventory.setLotId("lot-1");
        when(unitConverter.toItemUnit(BigDecimal.ONE, "kg", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(BigDecimal.ONE, "kg", "kg", false));
        when(idGenerator.generate()).thenReturn("simulation-output-1");
        when(productionRunItemRepository.save(any(ProductionRunItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, null, "inv-1", "item-1", "output", BigDecimal.ONE, null, "kg"));

        verifyNoInteractions(lotService, inventoryCommandService);
    }

    @Test
    void simulationStartDoesNotStartItsWorkOrder() {
        givenWorkflow();
        WorkOrder order = givenWorkOrder("approved", null);
        when(idGenerator.generate()).thenReturn("simulation-run-1");
        when(productionRunRepository.save(any(ProductionRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productionRunService.startRun(new ProductionRunStartRequest("project-1", "workflow-1", null,
            BigDecimal.TEN, "simulation", null, "wo-1", null, null));

        assertEquals("approved", order.getWorkOrderStatus());
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void simulationCorrectionDoesNotMoveInventory() {
        ProductionRun run = new ProductionRun();
        run.setProductionRunId("simulation-1");
        run.setProjectId("project-1");
        run.setRunType("simulation");
        givenItem();
        givenInventory("item-1", "10");
        when(unitConverter.toItemUnit(BigDecimal.ONE, "kg", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(BigDecimal.ONE, "kg", "kg", false));
        when(idGenerator.generate()).thenReturn("correction-item-1");
        when(productionRunItemRepository.save(any(ProductionRunItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productionRunService.recordCorrectionItem(run, "correction-1", "input", "item-1", "inv-1",
            BigDecimal.ONE, "kg");

        verifyNoInteractions(inventoryCommandService, stockAllocationService, lotService);
    }

    @Test
    void unknownRunTypeIsRejectedEvenWithoutAPublishedWorkflowRevision() {
        Workflow workflow = new Workflow();
        workflow.setWorkflowId("workflow-1");
        workflow.setProjectId("project-1");
        lenient().when(workflowReferences.findWorkflow("workflow-1"))
            .thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(workflow))));
        lenient().when(idGenerator.generate()).thenReturn("virtual-run-1");
        lenient().when(productionRunRepository.save(any(ProductionRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.startRun(
            new ProductionRunStartRequest("project-1", "workflow-1", null, BigDecimal.TEN,
                "virtual", null, null, null, null)));

        assertEquals("runType must be actual, simulation, test or dry_run.", exception.getMessage());
        verify(productionRunRepository, never()).save(any());
    }

    @Test
    void cannotRecordAgainstAnotherItemsStock() {
        givenRun("running");
        givenItem();
        givenInventory("item-2", "10");

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, null, "inv-1", "item-1", "input", BigDecimal.ONE, null, "kg")));

        assertEquals("The selected stock record holds a different item.", exception.getMessage());
        verify(productionRunItemRepository, never()).save(any());
    }

    @Test
    void legacyRunRejectsAnIoFromAnotherWorkflowWhenProcessIdIsOmitted() {
        givenRun("running");
        givenItem();
        ProcessIo io = new ProcessIo();
        io.setProcessIoId("foreign-io");
        io.setProcessId("foreign-process");
        Process process = new Process();
        process.setProcessId("foreign-process");
        process.setWorkflowId("foreign-workflow");
        when(workflowReferences.findProcessIo("foreign-io")).thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(io))));
        when(workflowReferences.findProcess("foreign-process")).thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(process))));

        assertThrows(BusinessException.class, () -> productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, "foreign-io", null, "item-1", "input", BigDecimal.ONE, null, "kg")));
        verify(productionRunItemRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
        "actual, item-2, input, itemId",
        "actual, item-1, output, direction",
        "simulation, item-2, input, itemId",
        "simulation, item-1, output, direction"
    })
    void legacyRunRejectsRecordingThatDoesNotMatchItsPort(String runType, String portItemId,
        String portDirection, String field) {
        givenRun("running").setRunType(runType);
        givenItem();
        Process process = new Process();
        process.setProcessId("process-1");
        process.setWorkflowId("workflow-1");
        ProcessIo io = new ProcessIo();
        io.setProcessIoId("io-1");
        io.setProcessId("process-1");
        io.setItemId(portItemId);
        io.setDirection(portDirection);
        when(workflowReferences.findProcessIo("io-1")).thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(io))));
        when(workflowReferences.findProcess("process-1")).thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(process))));
        lenient().when(unitConverter.toItemUnit(BigDecimal.ONE, "kg", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(BigDecimal.ONE, "kg", "kg", false));
        lenient().when(idGenerator.generate()).thenReturn("mismatched-item");
        lenient().when(productionRunItemRepository.save(any(ProductionRunItem.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, "io-1", null, "item-1", "input", BigDecimal.ONE, null, "kg")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(field + " does not match the selected processIoId.", exception.getMessage());
        verify(productionRunItemRepository, never()).save(any());
        verifyNoInteractions(inventoryCommandService, stockAllocationService, lotService);
    }

    private Item givenItem() {
        Item item = new Item();
        item.setItemId("item-1");
        item.setProjectId("project-1");
        item.setUnitId("unit_kg");
        when(itemFacts.findActiveItem("item-1")).thenReturn(Optional.of(new CatalogItemFactsQuery.Facts(facts(item))));
        return item;
    }

    private Inventory givenInventory(String itemId, String quantity) {
        Inventory inventory = new Inventory();
        inventory.setInventoryId("inv-1");
        inventory.setProjectId("project-1");
        inventory.setItemId(itemId);
        inventory.setQuantity(new BigDecimal(quantity));
        inventory.setAvailableQuantity(new BigDecimal(quantity));
        when(stockFacts.findActiveStock("inv-1")).thenReturn(Optional.of(new StockFactsQuery.Facts(facts(inventory))));
        return inventory;
    }

    @Test
    void firstRunMovesApprovedWorkOrderToInProgress() {
        givenWorkflow();
        WorkOrder order = givenWorkOrder("approved", null);
        when(idGenerator.generate()).thenReturn("run-9");
        when(productionRunRepository.save(any(ProductionRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductionRunResponse run = productionRunService.startRun(startRequest("wo-1"));

        assertEquals("wo-1", run.workOrderId());
        assertEquals("in_progress", order.getWorkOrderStatus());
        verify(workflowReferences).lockWorkflow("workflow-1");
        verify(workOrderRepository).save(order);
    }

    @Test
    void runCannotStartOnDraftWorkOrder() {
        givenWorkflow();
        givenWorkOrder("draft", null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> productionRunService.startRun(startRequest("wo-1")));

        assertEquals("Work order WO-1 is draft; runs can only start on approved or in-progress orders.", exception.getMessage());
        verify(productionRunRepository, never()).save(any());
    }

    @Test
    void runCannotUseWorkOrderOfAnotherWorkflow() {
        givenWorkflow();
        givenWorkOrder("approved", "workflow-other");

        assertThrows(BusinessException.class, () -> productionRunService.startRun(startRequest("wo-1")));
        verify(productionRunRepository, never()).save(any());
        verify(workOrderRepository, never()).save(any());
    }

    private void givenWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setWorkflowId("workflow-1");
        workflow.setProjectId("project-1");
        when(workflowReferences.findWorkflow("workflow-1")).thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(workflow))));
    }

    private WorkOrder givenWorkOrder(String status, String workflowId) {
        WorkOrder order = new WorkOrder();
        order.setWorkOrderId("wo-1");
        order.setWorkOrderNumber("WO-1");
        order.setProjectId("project-1");
        order.setWorkflowId(workflowId);
        order.setWorkOrderStatus(status);
        when(workOrderRepository.findForUpdate("wo-1")).thenReturn(Optional.of(order));
        return order;
    }

    private static ProductionRunStartRequest startRequest(String workOrderId) {
        return new ProductionRunStartRequest("project-1", "workflow-1", null, BigDecimal.TEN, null, null, workOrderId, null, null);
    }

    @Test
    void finishRejectsAlreadyFinishedRun() {
        givenRun("finished");

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> productionRunService.finishRun("run-1", new ProductionRunFinishRequest(BigDecimal.TEN, null))
        );

        assertEquals("Run RUN-1 is already finished.", exception.getMessage());
        verify(productionRunRepository, never()).save(any());
        verifyNoInteractions(flowRuleEngineService);
    }

    @Test
    void finishClosesRunningRun() {
        givenRun("running");
        when(productionRunRepository.save(any(ProductionRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductionRunResponse response =
            productionRunService.finishRun("run-1", new ProductionRunFinishRequest(new BigDecimal("42"), "tester"));

        assertEquals("finished", response.runStatus());
        assertEquals(new BigDecimal("42"), response.actualOutputQty());
    }

    @Test
    void finishRecordsAuthenticatedUserInsteadOfRequestValue() {
        givenRun("running");
        when(projectAccessService.requireCurrentUserId()).thenReturn("real-user");
        when(productionRunRepository.save(any(ProductionRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productionRunService.finishRun("run-1", new ProductionRunFinishRequest(BigDecimal.ONE, "spoofed-user"));

        ArgumentCaptor<ProductionRun> saved = ArgumentCaptor.forClass(ProductionRun.class);
        verify(productionRunRepository).save(saved.capture());
        assertEquals("real-user", saved.getValue().getFinishedBy());
    }

    @Test
    void finishRequiresProjectWriteAccess() {
        givenRun("running");
        when(projectAccessService.requireProjectWriteAccess("project-1"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project write access is required."));

        assertThrows(
            BusinessException.class,
            () -> productionRunService.finishRun("run-1", new ProductionRunFinishRequest(BigDecimal.TEN, null))
        );

        verify(productionRunRepository, never()).save(any());
        verifyNoInteractions(flowRuleEngineService);
    }

    @Test
    void finishRejectsNegativeOutputWithoutClosingTheRun() {
        ProductionRun run = givenRun("running");
        lenient().when(productionRunRepository.save(any(ProductionRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.finishRun("run-1",
            new ProductionRunFinishRequest(new BigDecimal("-1"), null)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("actualOutputQty must be 0 or more.", exception.getMessage());
        assertEquals("running", run.getRunStatus());
        verify(productionRunRepository, never()).save(any());
        verifyNoInteractions(flowRuleEngineService, productionFlowRunAdapter);
    }

    @Test
    void startRejectsNegativePlannedOutputBeforeStartingAnOrder() {
        lenient().when(idGenerator.generate()).thenReturn("negative-run");
        lenient().when(productionRunRepository.save(any(ProductionRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        Workflow workflow = new Workflow();
        workflow.setWorkflowId("workflow-1");
        workflow.setProjectId("project-1");
        lenient().when(workflowReferences.findWorkflow("workflow-1"))
            .thenReturn(Optional.of(new WorkflowProductionQuery.Facts(facts(workflow))));

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.startRun(
            new ProductionRunStartRequest("project-1", "workflow-1", null, new BigDecimal("-1"), null, null, null, null, null)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("plannedOutputQty must be 0 or more.", exception.getMessage());
        verify(productionRunRepository, never()).save(any());
        verifyNoInteractions(workOrderRepository, bomService, productionFlowRunAdapter, flowRuleEngineService);
    }

    @Test
    void positiveActualQuantityCannotHideANegativePlannedQuantity() {
        givenRun("running");
        Item item = new Item();
        item.setItemId("item-1");
        item.setProjectId("project-1");
        item.setUnitId("unit_kg");
        lenient().when(itemFacts.findActiveItem("item-1")).thenReturn(Optional.of(new CatalogItemFactsQuery.Facts(facts(item))));
        lenient().when(unitConverter.toItemUnit(BigDecimal.ONE, "kg", "unit_kg"))
            .thenReturn(new UnitConverter.Conversion(BigDecimal.ONE, "kg", "kg", false));
        lenient().when(productionRunItemRepository.save(any(ProductionRunItem.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(BusinessException.class, () -> productionRunService.recordRunItem("run-1",
            new ProductionRunItemRecordRequest(null, null, null, "item-1", "input", new BigDecimal("-1"), BigDecimal.ONE, "kg")));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("plannedQty must be 0 or more.", exception.getMessage());
        verify(productionRunItemRepository, never()).save(any());
        verifyNoInteractions(inventoryCommandService, stockAllocationService, flowRuleEngineService);
    }

    @Test
    void getRunRequiresProjectReadAccess() {
        givenRun("running");
        when(projectAccessService.requireProjectReadAccess("project-1"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project membership is required."));

        assertThrows(BusinessException.class, () -> productionRunService.getRun("run-1"));
        assertThrows(BusinessException.class, () -> productionRunService.listRunItems("run-1"));
        verifyNoInteractions(productionRunItemRepository);
    }

    @Test
    void recordItemRejectsFinishedRun() {
        givenRun("finished");

        assertThrows(
            BusinessException.class,
            () -> productionRunService.recordRunItem("run-1", recordRequest("input"))
        );

        verifyNoInteractions(itemFacts, stockFacts, productionRunItemRepository);
    }

    @Test
    void recordItemRejectsUnknownDirection() {
        givenRun("running");

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> productionRunService.recordRunItem("run-1", recordRequest("sideways"))
        );

        assertEquals("direction must be 'input' or 'output'.", exception.getMessage());
        verifyNoInteractions(itemFacts, productionRunItemRepository);
    }

    private ProductionRun givenRun(String status) {
        ProductionRun run = new ProductionRun();
        run.setProductionRunId("run-1");
        run.setProjectId("project-1");
        run.setWorkflowId("workflow-1");
        run.setRunNumber("RUN-1");
        run.setRunStatus(status);
        run.setRunType("actual");
        run.setPlannedOutputQty(BigDecimal.TEN);
        run.setActualOutputQty(BigDecimal.ZERO);
        run.setDeletedYn("N");
        lenient().when(productionRunRepository.findByProductionRunIdAndDeletedYn("run-1", "N")).thenReturn(Optional.of(run));
        lenient().when(productionRunRepository.findForUpdate("run-1")).thenReturn(Optional.of(run));
        return run;
    }

    private static ProductionRunItemRecordRequest recordRequest(String direction) {
        return new ProductionRunItemRecordRequest(null, null, null, "item-1", direction, BigDecimal.ONE, null, "ea");
    }
}
