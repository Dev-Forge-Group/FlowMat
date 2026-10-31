package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotTraceResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.entity.LotStatusEvent;
import org.myweb.flowmat.domain.inventory.domain.entity.LotTrace;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.inventory.repository.LotStatusEventRepository;
import org.myweb.flowmat.domain.inventory.repository.LotTraceRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LotServiceImpl implements LotService {

    private static final String NOT_DELETED = "N";
    /** Genealogy deeper than this is almost certainly a cycle in bad data; stop rather than loop. */
    private static final int MAX_TRACE_DEPTH = 50;

    private final LotMasterRepository lotMasterRepository;
    private final LotTraceRepository lotTraceRepository;
    private final InventoryRepository inventoryRepository;
    private final ProjectAccessService projectAccessService;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryCatalogReferences catalogReferences;
    private final InventoryCommandService inventoryCommandService;
    private final IdGenerator idGenerator;
    private final LotStatusEventRepository lotStatusEventRepository;

    @Override
    @Transactional
    public LotResponse createLot(LotCreateRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        CatalogItemView item = catalogReferences.item(request.itemId().trim())
            .filter(found -> projectId.equals(found.projectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Item does not exist in this project."));
        if (!"Y".equals(item.lotManageYn())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                item.itemCode() + " is not LOT-tracked. Turn on LOT tracking for the item first.");
        }
        ItemStatusRule.requireActive(item.itemCode(), item.itemStatus(), "register a LOT");
        String lotNo = request.lotNo().trim();
        if (lotMasterRepository.existsByProjectIdAndLotNoIgnoreCase(projectId, lotNo)) {
            throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lotNo + " already exists in this project.");
        }

        LotMaster lot = new LotMaster();
        lot.setLotId(idGenerator.generate());
        lot.setProjectId(projectId);
        lot.setItemId(item.itemId());
        lot.setLotNo(lotNo);
        lot.setSerialNo(request.serialNo() == null || request.serialNo().isBlank() ? null : request.serialNo().trim());
        lot.setReceivedAt(request.receivedAt());
        lot.setExpiryDate(request.expiryDate());
        // An item that needs its receipt checks first starts its LOTs waiting for them (docs/domain/lot-release.md R1).
        lot.setLotStatus(("Y".equals(item.lotReleaseRequiredYn()) ? LotStatus.INSPECTION_PENDING : LotStatus.AVAILABLE).code());
        return toResponse(lotMasterRepository.saveAndFlush(lot), List.of());
    }

    @Override
    public List<LotResponse> listLots(String projectId, String itemId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId.trim());
        List<LotMaster> lots = itemId == null || itemId.isBlank()
            ? lotMasterRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId.trim())
            : lotMasterRepository.findAllByProjectIdAndItemIdOrderByCreatedAtDesc(projectId.trim(), itemId.trim());
        if (lots.isEmpty()) {
            return List.of();
        }
        // One query for every LOT's stock records instead of one per LOT.
        Map<String, List<Inventory>> stockByLot = inventoryRepository
            .findAllByLotIdInAndDeletedYn(lots.stream().map(LotMaster::getLotId).toList(), NOT_DELETED)
            .stream()
            .collect(Collectors.groupingBy(Inventory::getLotId));
        return lots.stream().map(lot -> toResponse(lot, stockByLot.getOrDefault(lot.getLotId(), List.of()))).toList();
    }

    @Override
    public LotResponse getLot(String lotId) {
        LotMaster lot = findLot(lotId);
        projectAccessService.requireProjectReadAccess(lot.getProjectId());
        return toResponse(lot);
    }

    @Override
    @Transactional
    public LotResponse reopenLot(String lotId) {
        LotMaster lot = findLot(lotId);
        projectAccessService.requireProjectOwnerAccess(lot.getProjectId());
        inventoryCommandService.lockLot(lot);
        if (!LotStatus.CLOSED.code().equals(lot.getLotStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "LOT " + lot.getLotNo() + " is not closed.");
        }
        InventoryRepository.LotStockTotals stock = inventoryRepository.lotStockTotals(lotId);
        // The reopening comes before the holds it causes, so the history lists them in that order (R9).
        OffsetDateTime at = OffsetDateTime.now();
        String actor = projectAccessService.requireCurrentUserId();
        boolean needsChecks = catalogReferences.item(lot.getItemId())
            .map(item -> "Y".equals(item.lotReleaseRequiredYn())).orElse(false);
        if (needsChecks) {
            // Reopening counts as registering the LOT again, so it waits for its receipt checks and its records are held.
            // Checks that passed before still count, so Release LOT clears it at once (docs/domain/lot-release.md R6).
            inventoryCommandService.holdForReceiptChecks(lotId, lot.getLotNo(), actor);
            lot.setLotStatus(LotStatus.INSPECTION_PENDING.code());
            stock = inventoryRepository.lotStockTotals(lotId);
        } else if (stock.getQuarantinedRows() > 0) {
            // Its records are still held, so the LOT is too; unquarantine releases them.
            lot.setLotStatus(LotStatus.QUARANTINED.code());
        } else {
            // A closed LOT held nothing, so it comes back as its stock says: consumed, or available with no stock record.
            lot.setLotStatus(stock.getStockRows() == 0 ? LotStatus.AVAILABLE.code()
                : InventoryCommandService.lotStatusFor(stock.getQuantityOnHand(), stock.getQuantityReserved()));
        }
        LotMaster saved = lotMasterRepository.saveAndFlush(lot);
        recordStatusEvent(saved, LotStatusEvent.REOPENED, LotStatus.CLOSED.code(), at, actor);
        return toResponse(saved, stock.getQuantityOnHand(), stock.getQuantityReserved());
    }

    @Override
    @Transactional
    public LotResponse closeLot(String lotId) {
        LotMaster lot = findLot(lotId);
        projectAccessService.requireProjectOwnerAccess(lot.getProjectId());
        inventoryCommandService.lockLot(lot);
        InventoryRepository.LotStockTotals stock = inventoryRepository.lotStockTotals(lotId);
        BigDecimal onHand = stock.getQuantityOnHand();
        if (onHand.signum() != 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "LOT " + lot.getLotNo() + " still holds " + InventoryCommandService.plain(onHand) + ". Issue it before closing.");
        }
        String before = lot.getLotStatus();
        lot.setLotStatus(LotStatus.CLOSED.code());
        LotMaster saved = lotMasterRepository.save(lot);
        if (!LotStatus.CLOSED.code().equals(before)) {
            // Closing moves no stock, so the event is kept on its own; closing a closed LOT again adds none (R9).
            recordStatusEvent(saved, LotStatusEvent.CLOSED, before, OffsetDateTime.now(), projectAccessService.requireCurrentUserId());
        }
        return toResponse(saved, onHand, stock.getQuantityReserved());
    }

    private void recordStatusEvent(LotMaster lot, String action, String fromStatus, OffsetDateTime at, String actor) {
        LotStatusEvent event = new LotStatusEvent();
        event.setLotStatusEventId(idGenerator.generate());
        event.setLotId(lot.getLotId());
        event.setProjectId(lot.getProjectId());
        event.setAction(action);
        event.setFromStatus(fromStatus);
        event.setToStatus(lot.getLotStatus());
        event.setCreatedBy(actor);
        event.setCreatedAt(at);
        lotStatusEventRepository.save(event);
    }

    @Override
    public LotTraceResponse trace(String lotId, String direction) {
        LotMaster start = findLot(lotId);
        projectAccessService.requireProjectReadAccess(start.getProjectId());
        boolean backward = !"forward".equalsIgnoreCase(direction == null ? "" : direction.trim());

        List<LotTraceResponse.Node> nodes = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(start.getLotId()));
        List<String> frontier = List.of(start.getLotId());
        for (int depth = 1; !frontier.isEmpty() && depth <= MAX_TRACE_DEPTH; depth++) {
            List<LotTrace> edges = backward
                ? lotTraceRepository.findAllByChildLotIdIn(frontier)
                : lotTraceRepository.findAllByParentLotIdIn(frontier);
            List<String> next = new ArrayList<>();
            Map<String, LotMaster> reached = lotMasterRepository.findAllById(edges.stream()
                    .map(edge -> backward ? edge.getParentLotId() : edge.getChildLotId()).toList())
                .stream().collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));
            for (LotTrace edge : edges) {
                String otherId = backward ? edge.getParentLotId() : edge.getChildLotId();
                LotMaster other = reached.get(otherId);
                if (other == null || !visited.add(otherId)) {
                    continue;
                }
                nodes.add(new LotTraceResponse.Node(
                    toResponse(other),
                    depth,
                    backward ? edge.getChildLotId() : edge.getParentLotId(),
                    edge.getProductionRunId(),
                    edge.getConsumedQty(),
                    edge.getProducedQty(),
                    edge.getUnit()
                ));
                next.add(otherId);
            }
            frontier = next;
        }
        return new LotTraceResponse(toResponse(start), backward ? "backward" : "forward", nodes);
    }

    @Override
    public LotMaster requireLotForStock(String lotId, String projectId, String itemId) {
        LotMaster lot = lotMasterRepository.findById(lotId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "LOT does not exist."));
        if (!projectId.equals(lot.getProjectId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.getLotNo() + " belongs to another project.");
        }
        if (!itemId.equals(lot.getItemId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.getLotNo() + " is for a different item.");
        }
        inventoryCommandService.lockLotForStock(lot);
        LotStatus status = LotStatus.fromCode(lot.getLotStatus());
        if (status == LotStatus.CLOSED || status == LotStatus.QUARANTINED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "LOT " + lot.getLotNo() + " is " + status.code() + ".");
        }
        return lot;
    }

    @Override
    @Transactional
    public void recordTrace(
        String projectId,
        String productionRunId,
        String processId,
        String parentLotId,
        String childLotId,
        BigDecimal consumedQty,
        BigDecimal producedQty,
        String unit
    ) {
        if (parentLotId.equals(childLotId)
            || lotTraceRepository.existsByParentLotIdAndChildLotIdAndProductionRunId(parentLotId, childLotId, productionRunId)) {
            return;
        }
        LotTrace trace = new LotTrace();
        trace.setLotTraceId(idGenerator.generate());
        trace.setProjectId(projectId);
        trace.setParentLotId(parentLotId);
        trace.setChildLotId(childLotId);
        trace.setProductionRunId(productionRunId);
        trace.setProcessId(processId);
        trace.setConsumedQty(consumedQty);
        trace.setProducedQty(producedQty);
        trace.setUnit(unit);
        trace.setCreatedAt(OffsetDateTime.now());
        lotTraceRepository.save(trace);
    }

    @Override
    @Transactional
    public void markProducedBy(String lotId, String productionRunId) {
        lotMasterRepository.findById(lotId).ifPresent(lot -> {
            inventoryCommandService.lockLot(lot);
            if (lot.getProductionRunId() == null) {
                lot.setProductionRunId(productionRunId);
                lot.setProducedAt(OffsetDateTime.now());
                lotMasterRepository.save(lot);
            }
        });
    }

    @Override
    @Transactional
    public void clearProducedBy(String lotId, String productionRunId) {
        lotMasterRepository.findById(lotId).ifPresent(lot -> {
            inventoryCommandService.lockLot(lot);
            if (productionRunId.equals(lot.getProductionRunId())) {
                lot.setProductionRunId(null);
                lot.setProducedAt(null);
                lotMasterRepository.save(lot);
            }
        });
    }

    @Override
    @Transactional
    public void clearRunTrace(String productionRunId) {
        lotTraceRepository.deleteAllByProductionRunId(productionRunId);
    }

    private LotMaster findLot(String lotId) {
        return lotMasterRepository.findById(lotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private LotResponse toResponse(LotMaster lot) {
        return toResponse(lot, inventoryRepository.findAllByLotIdAndDeletedYn(lot.getLotId(), NOT_DELETED));
    }

    private LotResponse toResponse(LotMaster lot, List<Inventory> stock) {
        return toResponse(lot, sum(stock, Inventory::getQuantity), sum(stock, Inventory::getReservedQuantity));
    }

    private LotResponse toResponse(LotMaster lot, BigDecimal onHand, BigDecimal reserved) {
        return new LotResponse(
            lot.getLotId(),
            lot.getProjectId(),
            lot.getItemId(),
            lot.getLotNo(),
            lot.getSerialNo(),
            lot.getLotStatus(),
            lot.getReceivedAt(),
            lot.getProducedAt(),
            lot.getExpiryDate(),
            lot.getProductionRunId(),
            onHand,
            reserved,
            lot.isExpiredOn(projectCalendar.today(lot.getProjectId()))
        );
    }

    private static BigDecimal sum(List<Inventory> stock, Function<Inventory, BigDecimal> field) {
        return stock.stream()
            .map(field)
            .map(value -> value == null ? BigDecimal.ZERO : value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
