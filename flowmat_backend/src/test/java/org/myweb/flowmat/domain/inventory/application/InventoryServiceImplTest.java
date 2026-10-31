package org.myweb.flowmat.domain.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.rule.application.FlowRuleEngineService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private InventoryCatalogReferences catalogReferences;
    @Mock private InventoryCommandService inventoryCommandService;
    @Mock private FlowRuleEngineService flowRuleEngineService;
    @Mock private IdGenerator idGenerator;
    @Mock private LotService lotService;
    @Mock private org.myweb.flowmat.domain.inventory.repository.LotMasterRepository lotMasterRepository;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    @Test
    void listRequiresProjectReadAccess() {
        when(projectAccessService.requireProjectReadAccess("other-project"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project membership is required."));

        assertThrows(BusinessException.class, () -> inventoryService.listInventories("other-project"));

        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void createRequiresProjectWriteAccess() {
        when(projectAccessService.requireProjectWriteAccess("project-1"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project write access is required."));

        assertThrows(
            BusinessException.class,
            () -> inventoryService.createInventory(
                new InventoryAdjustRequest("project-1", "item-1", BigDecimal.TEN, null, null, null, null, null, null, null, null)
            )
        );

        verifyNoInteractions(catalogReferences, inventoryRepository, inventoryCommandService);
    }

    @Test
    void staleAdjustmentIsRejectedInsteadOfOverwritingNewerMovements() {
        givenInventory();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> inventoryService.updateInventory(
                "inventory-1",
                new InventoryAdjustRequest("project-1", "item-1", new BigDecimal("80"), null, null, null, null, null, null, 2L, null)
            )
        );

        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
        assertEquals("This stock record changed since you opened it (now 10 on hand). Reload and try again.", exception.getMessage());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void adjustmentWithoutVersionIsRejectedInsteadOfOverwritingNewerMovements() {
        givenInventory();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> inventoryService.updateInventory(
                "inventory-1",
                new InventoryAdjustRequest("project-1", "item-1", new BigDecimal("8"), null, null, null, null, null, null, null, null)
            )
        );

        assertEquals(ErrorCode.CONFLICT, exception.getErrorCode());
        assertEquals("This stock record changed since you opened it (now 10 on hand). Reload and try again.", exception.getMessage());
        verify(catalogReferences, never()).item(any());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateChecksAccessOnStoredProjectNotRequestBody() {
        givenInventory();
        when(projectAccessService.requireProjectWriteAccess("project-1"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project write access is required."));

        // A caller cannot bypass the check by naming a project they do control in the request body.
        assertThrows(
            BusinessException.class,
            () -> inventoryService.updateInventory(
                "inventory-1",
                new InventoryAdjustRequest("attacker-project", "item-1", BigDecimal.ONE, null, null, null, null, null, null, null, null)
            )
        );

        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteRequiresProjectOwnerAccess() {
        givenInventory();
        when(projectAccessService.requireProjectOwnerAccess("project-1"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "Project owner access is required."));

        assertThrows(BusinessException.class, () -> inventoryService.deleteInventory("inventory-1"));

        verify(inventoryRepository, never()).save(any());
        verifyNoInteractions(inventoryCommandService);
    }

    @Test
    void stockLevelFlagsLowAndOverstock() {
        Inventory inventory = new Inventory();
        inventory.setQuantity(new BigDecimal("50"));
        inventory.setAvailableQuantity(new BigDecimal("8"));
        inventory.setMinThreshold(new BigDecimal("10"));
        inventory.setMaxThreshold(new BigDecimal("40"));
        assertEquals("low", InventoryServiceImpl.stockLevel(inventory));

        inventory.setAvailableQuantity(new BigDecimal("45"));
        assertEquals("over", InventoryServiceImpl.stockLevel(inventory));

        inventory.setQuantity(new BigDecimal("30"));
        inventory.setAvailableQuantity(new BigDecimal("30"));
        assertEquals("ok", InventoryServiceImpl.stockLevel(inventory));

        inventory.setMinThreshold(BigDecimal.ZERO);
        inventory.setMaxThreshold(null);
        inventory.setAvailableQuantity(new BigDecimal("-5"));
        assertEquals("ok", InventoryServiceImpl.stockLevel(inventory), "a zero minimum never reports low");
    }

    @Test
    void rejectsMaximumBelowMinimum() {
        when(catalogReferences.item("item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.lockItemForStock("project-1", "item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.itemRuleFacts("item-1")).thenReturn(Optional.of(Map.of()));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> inventoryService.createInventory(new InventoryAdjustRequest(
                "project-1", "item-1", BigDecimal.TEN, null, null, null, null, new BigDecimal("20"), new BigDecimal("5"), null, null
            ))
        );

        assertEquals("Maximum stock must be at least the minimum.", exception.getMessage());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReservingMoreThanOnHand() {
        when(catalogReferences.item("item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.lockItemForStock("project-1", "item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.itemRuleFacts("item-1")).thenReturn(Optional.of(Map.of()));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> inventoryService.createInventory(new InventoryAdjustRequest(
                "project-1", "item-1", BigDecimal.TEN, new BigDecimal("11"), null, null, null, null, null, null, null
            ))
        );

        assertEquals("Reserved stock cannot exceed the quantity on hand.", exception.getMessage());
        verify(inventoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsNegativeQuantity() {
        when(catalogReferences.item("item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.lockItemForStock("project-1", "item-1")).thenReturn(Optional.of(item()));
        when(catalogReferences.itemRuleFacts("item-1")).thenReturn(Optional.of(Map.of()));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> inventoryService.createInventory(new InventoryAdjustRequest(
                "project-1", "item-1", new BigDecimal("-1"), null, null, null, null, null, null, null, null
            ))
        );

        assertEquals("Stock quantities cannot be negative.", exception.getMessage());
    }

    @Test
    void deletingARecordThatStillHoldsStockIsRefused() {
        givenInventory();

        BusinessException exception = assertThrows(BusinessException.class, () -> inventoryService.deleteInventory("inventory-1"));

        assertEquals("This record still holds 10. Issue or adjust it to zero before deleting.", exception.getMessage());
        verify(inventoryRepository, never()).save(any());
    }

    private static CatalogItemView item() {
        return new CatalogItemView("item-1", "project-1", null, null, null, null, null, null, null, null, null, null, null, null, "N");
    }

    private void givenInventory() {
        Inventory inventory = new Inventory();
        inventory.setInventoryId("inventory-1");
        inventory.setProjectId("project-1");
        inventory.setItemId("item-1");
        inventory.setQuantity(BigDecimal.TEN);
        inventory.setDeletedYn("N");
        when(inventoryRepository.findByInventoryIdAndDeletedYn("inventory-1", "N")).thenReturn(Optional.of(inventory));
    }
}
