package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRequirement;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.AllocatedStockTransferRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.PickListRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.WarehouseTaskCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.PickListResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.WarehouseTaskResponse;
import org.myweb.flowmat.domain.inventory.application.publicapi.PickWorkOrders.PickWorkOrder;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.entity.WarehouseTask;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.inventory.repository.WarehouseTaskRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.myweb.flowmat.domain.inventory.application.publicapi.PickWorkOrders;
import org.myweb.flowmat.domain.inventory.application.publicapi.WorkOrderAllocations;

/**
 * Warehouse tasks (docs/domain/warehouse-task.md, benchmark FM-WMS-002): putaways and picks plan moving part of a stock
 * record to a place; doing one records an ordinary transfer ({@link InventoryTransferService}), so quantities still
 * change only through the stock commands. A pick list plans picks to a staging place for a work order's materials,
 * first-expiring LOTs first, leaving out stock already at the staging place or already planned.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseTaskService {

    private static final String OPEN = "open";
    private static final String NOT_DELETED = "N";
    private static final String AVAILABLE = "available";

    private final WarehouseTaskRepository taskRepository;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryRepository inventoryRepository;
    private final CatalogQuery catalogQuery;
    private final LotMasterRepository lotMasterRepository;
    private final PickWorkOrders workOrders;
    private final WorkOrderAllocations allocationTransfers;
    private final AllocatedStockTransferService allocatedStockTransferService;
    private final BomQuery bomQuery;
    private final StorageLocationService storageLocationService;
    private final InventoryTransferService inventoryTransferService;
    private final ProjectAccess projectAccessService;
    private final ProjectMemberQuery projectMemberQuery;
    private final IdGenerator idGenerator;
    private final org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand notifications;

    /** Newest first; one status, one work order and one assignee only when asked. */
    public List<WarehouseTaskResponse> list(String projectId, String status, String workOrderId, String assignedTo) {
        String project = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(project);
        String statusFilter = trimToNull(status);
        String orderFilter = trimToNull(workOrderId);
        String assigneeFilter = trimToNull(assignedTo);
        return responses(taskRepository.findAllByProjectIdOrderByCreatedAtDesc(project).stream()
            .filter(task -> statusFilter == null || statusFilter.equals(task.getStatus()))
            .filter(task -> orderFilter == null || orderFilter.equals(task.getWorkOrderId()))
            .filter(task -> assigneeFilter == null || assigneeFilter.equals(task.getAssignedTo()))
            .toList());
    }

    @Transactional
    public WarehouseTaskResponse create(WarehouseTaskCreateRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        String type = trimToNull(request.taskType()) == null ? "putaway" : request.taskType().trim().toLowerCase(Locale.ROOT);
        if (!type.equals("putaway") && !type.equals("pick")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskType must be putaway or pick.");
        }
        taskRepository.lockProject(projectId);
        Inventory row = inventoryRepository.findByInventoryIdAndDeletedYn(required(request.inventoryId(), "inventoryId"), NOT_DELETED)
            .filter(found -> projectId.equals(found.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The stock record was not found in this project."));
        String to = destination(projectId, request.toLocation());
        if (to.equalsIgnoreCase(Objects.toString(trimToNull(row.getLocation()), ""))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The stock is already at " + to + ".");
        }
        BigDecimal planned = plannedByRecord(projectId).getOrDefault(row.getInventoryId(), BigDecimal.ZERO);
        BigDecimal free = row.getAvailableQuantity().subtract(planned);
        if (request.quantity().compareTo(free) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only " + plain(free.max(BigDecimal.ZERO)) + " of this record is free to move ("
                + plain(row.getAvailableQuantity()) + " available, " + plain(planned) + " already in open tasks).");
        }
        WarehouseTask task = task(projectId, type, row, request.quantity(), to, null, trimToNull(request.note()));
        return responses(List.of(taskRepository.saveAndFlush(task))).get(0);
    }

    @Transactional
    public PickListResponse pickList(PickListRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        taskRepository.lockProject(projectId);
        String staging = destination(projectId, request.stagingLocation());
        PickWorkOrder order = null;
        Map<String, BigDecimal> needs = new LinkedHashMap<>();
        List<PickListRequest.Line> lines = request.lines() == null ? List.of() : request.lines();
        if (trimToNull(request.workOrderId()) != null) {
            if (!lines.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a work order or lines to pick, not both.");
            }
            order = workOrders.findProjectWorkOrder(projectId, request.workOrderId().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The work order was not found in this project."));
            if (!order.acceptsRuns()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Work order " + order.workOrderNumber() + " is "
                    + order.workOrderStatus() + "; pick for an approved or started order.");
            }
            if (order.bomId() == null || order.targetQuantity() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Work order " + order.workOrderNumber() + " has no BOM and quantity to pick for.");
            }
            BigDecimal quantity = request.quantity() != null ? request.quantity() : order.targetQuantity();
            // Phantoms expand through the revision of the order's planned day (docs/domain/multi-level-bom.md P3).
            LocalDate phantomDay = order.plannedStartAt() == null ? null : projectCalendar.date(projectId, order.plannedStartAt().toInstant());
            BomRequirement requirement = bomQuery.requirementsForRun(order.bomId(), projectId, order.targetItemId(), quantity,
                phantomDay, order.optionalItemIds());
            for (BomRequirement.Line line : requirement.lines()) {
                needs.merge(line.childItemId(), line.requiredItemQuantity(), BigDecimal::add);
            }
        } else {
            if (lines.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a work order or the lines to pick.");
            }
            for (PickListRequest.Line line : lines) {
                needs.merge(line.itemId().trim(), line.quantity(), BigDecimal::add);
            }
        }

        List<WorkOrderAllocations.OpenAllocation> ownAllocations = order == null ? List.of() : allocationTransfers.lockOpen(projectId, order.workOrderId());
        Map<String, BigDecimal> reservedByRecord = ownAllocations.stream().collect(Collectors.toMap(
            WorkOrderAllocations.OpenAllocation::inventoryId, WorkOrderAllocations.OpenAllocation::remaining, BigDecimal::add));
        List<WarehouseTask> openTasks = taskRepository.findAllByProjectIdAndStatus(projectId, OPEN);
        Map<String, BigDecimal> plannedByAllocation = openTasks.stream().filter(task -> task.getAllocationId() != null)
            .collect(Collectors.toMap(WarehouseTask::getAllocationId, WarehouseTask::getQuantity, BigDecimal::add));
        Map<String, BigDecimal> plannedByRecord = plannedByRecord(projectId);
        String requestedOrderId = order == null ? null : order.workOrderId();
        Map<String, BigDecimal> plannedToStaging = openTasks.stream()
            .filter(task -> task.getAllocationId() == null || Objects.equals(requestedOrderId, task.getWorkOrderId()))
            .filter(task -> "pick".equals(task.getTaskType()) && staging.equalsIgnoreCase(task.getToLocation()))
            .collect(Collectors.toMap(WarehouseTask::getInventoryId, WarehouseTask::getQuantity, BigDecimal::add));
        String note = trimToNull(request.note());
        List<WarehouseTask> created = new ArrayList<>();
        List<PickListResponse.Line> summary = new ArrayList<>();
        LocalDate today = projectCalendar.today(projectId);
        for (Map.Entry<String, BigDecimal> need : needs.entrySet()) {
            CatalogItemView item = catalogQuery.findProjectItem(projectId, need.getKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "An item to pick was not found in this project."));
            // Locked in id order, so a movement that would drain a record this plan counts on waits for it.
            List<Inventory> rows = inventoryRepository.findAllById(inventoryRepository.lockItemStock(projectId, item.itemId()));
            List<Inventory> usableRows = usableStock(rows, today);
            BigDecimal atStaging = usableRows.stream()
                .filter(row -> staging.equalsIgnoreCase(Objects.toString(trimToNull(row.getLocation()), "")))
                .map(row -> row.getAvailableQuantity().add(reservedByRecord.getOrDefault(row.getInventoryId(), BigDecimal.ZERO))).reduce(BigDecimal.ZERO, BigDecimal::add);
            // A queued task covers only what its source can still move, without counting staged stock twice.
            BigDecimal alreadyPlanned = usableRows.stream()
                .filter(row -> !staging.equalsIgnoreCase(Objects.toString(trimToNull(row.getLocation()), "")))
                .map(row -> plannedToStaging.getOrDefault(row.getInventoryId(), BigDecimal.ZERO).min(row.getAvailableQuantity().add(reservedByRecord.getOrDefault(row.getInventoryId(), BigDecimal.ZERO))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = need.getValue().subtract(atStaging).subtract(alreadyPlanned);
            BigDecimal plannedNow = BigDecimal.ZERO;
            // The order's reservations are selected before any free stock, in the same LOT/record expiry order.
            for (Inventory row : usableRows) {
                if (remaining.signum() <= 0) break;
                if (staging.equalsIgnoreCase(Objects.toString(trimToNull(row.getLocation()), ""))) continue;
                for (var allocation : ownAllocations) {
                    if (remaining.signum() <= 0) break;
                    if (!row.getInventoryId().equals(allocation.inventoryId())) continue;
                    BigDecimal free = allocation.remaining().subtract(plannedByAllocation.getOrDefault(allocation.allocationId(), BigDecimal.ZERO));
                    if (free.signum() <= 0) continue;
                    BigDecimal piece = free.min(remaining);
                    WarehouseTask reservedTask = task(projectId, "pick", row, piece, staging, requestedOrderId, note);
                    reservedTask.setAllocationId(allocation.allocationId());
                    created.add(taskRepository.saveAndFlush(reservedTask));
                    plannedByAllocation.merge(allocation.allocationId(), piece, BigDecimal::add);
                    plannedNow = plannedNow.add(piece); remaining = remaining.subtract(piece);
                }
            }
            for (Inventory row : usableRows) {
                if (remaining.signum() <= 0) {
                    break;
                }
                if (staging.equalsIgnoreCase(Objects.toString(trimToNull(row.getLocation()), ""))) {
                    continue;
                }
                BigDecimal free = row.getAvailableQuantity().subtract(plannedByRecord.getOrDefault(row.getInventoryId(), BigDecimal.ZERO));
                if (free.signum() <= 0) {
                    continue;
                }
                BigDecimal piece = free.min(remaining);
                created.add(taskRepository.saveAndFlush(task(projectId, "pick", row, piece, staging,
                    order == null ? null : order.workOrderId(), note)));
                plannedByRecord.merge(row.getInventoryId(), piece, BigDecimal::add);
                plannedNow = plannedNow.add(piece);
                remaining = remaining.subtract(piece);
            }
            summary.add(new PickListResponse.Line(item.itemId(), item.itemCode(), need.getValue(), atStaging, alreadyPlanned,
                plannedNow, remaining.max(BigDecimal.ZERO)));
        }
        return new PickListResponse(responses(created), summary);
    }

    /**
     * Does the task: an ordinary transfer of its quantity to its place, which it then points to. With a smaller
     * {@code quantity} only that much moves now (docs/domain/warehouse-task.md W6): the moved part becomes a task of its
     * own, done, noting which task it is part of, and this task stays open with the rest. The answer is the task that got
     * done.
     */
    @Transactional
    public WarehouseTaskResponse complete(String taskId, BigDecimal quantity, String expectedToLocation, BigDecimal expectedQuantity) {
        WarehouseTask task = lockOpen(taskId);
        // W16: a partial move leaves the original task open with less quantity. An unchanged retry must not move it again.
        if (expectedQuantity != null && expectedQuantity.compareTo(task.getQuantity()) != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "expectedQuantity no longer matches task " + task.getTaskNo()
                + ". Refresh the task list and check completed moves before reopening the completion form.");
        }
        // W12: verify the actual scanned code after the same project lock used by location renames.
        if (expectedToLocation != null && !required(expectedToLocation, "expectedToLocation").equalsIgnoreCase(task.getToLocation())) {
            throw new BusinessException(ErrorCode.CONFLICT, "expectedToLocation does not match task " + task.getTaskNo()
                + " destination " + task.getToLocation() + ". Scan its destination again.");
        }
        if (quantity != null) {
            if (quantity.signum() <= 0 || quantity.compareTo(task.getQuantity()) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Move more than 0 and at most " + plain(task.getQuantity())
                    + " for task " + task.getTaskNo() + ".");
            }
            // Round once before splitting: stock, reservation, allocation and remaining task must use the same piece.
            quantity = quantity.setScale(4, RoundingMode.HALF_UP);
            if (quantity.signum() == 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "quantity must round to at least 0.0001.");
        }
        if (quantity == null || quantity.compareTo(task.getQuantity()) == 0) {
            return responses(List.of(finish(task))).get(0);
        }
        // lockOpen already took the project's planning turn before the task row (W5).
        WarehouseTask part = new WarehouseTask();
        part.setTaskId(idGenerator.generate());
        part.setProjectId(task.getProjectId());
        part.setTaskNo(nextTaskNo(task.getProjectId()));
        part.setTaskType(task.getTaskType());
        part.setStatus(OPEN);
        part.setInventoryId(task.getInventoryId());
        part.setItemId(task.getItemId());
        part.setLotId(task.getLotId());
        part.setQuantity(quantity);
        part.setFromLocation(task.getFromLocation());
        part.setToLocation(task.getToLocation());
        part.setWorkOrderId(task.getWorkOrderId());
        part.setAllocationId(task.getAllocationId());
        part.setAssignedTo(task.getAssignedTo());
        String note = "Part of " + task.getTaskNo() + (task.getNote() == null ? "" : ": " + task.getNote());
        part.setNote(note.length() > 500 ? note.substring(0, 500) : note);
        part.setCreatedBy(projectAccessService.requireCurrentUserId());
        part.setCreatedAt(OffsetDateTime.now());
        WarehouseTask done = finish(part);
        task.setQuantity(task.getQuantity().subtract(quantity));
        taskRepository.saveAndFlush(task);
        return responses(List.of(done)).get(0);
    }

    /** An ordinary transfer of the task's quantity to its place; the task is then done and points to it. */
    private WarehouseTask finish(WarehouseTask task) {
        String transferId = task.getAllocationId() == null
            ? inventoryTransferService.transfer(new InventoryTransferRequest(task.getInventoryId(), task.getToLocation(), task.getQuantity(),
                "warehouse-task:" + task.getTaskId(), "Task " + task.getTaskNo())).transferId()
            : allocatedStockTransferService.transfer(new AllocatedStockTransferRequest(task.getProjectId(), task.getWorkOrderId(),
                task.getAllocationId(), task.getInventoryId(), task.getToLocation(), task.getQuantity(),
                UUID.nameUUIDFromBytes(("warehouse-task:" + task.getTaskId()).getBytes(StandardCharsets.UTF_8)))).transferId();
        task.setStatus("done");
        task.setTransferId(transferId);
        task.setFinishedBy(projectAccessService.requireCurrentUserId());
        task.setFinishedAt(OffsetDateTime.now());
        return taskRepository.saveAndFlush(task);
    }

    /** Who should do an open task (W7): the project owner or an active member; blank leaves it to no one. */
    @Transactional
    public WarehouseTaskResponse assign(String taskId, String assignedTo) {
        WarehouseTask task = lockOpen(taskId);
        String userId = trimToNull(assignedTo);
        if (userId != null) {
            projectAccessService.requireProjectReadAccess(task.getProjectId());
            if (!userId.equals(projectMemberQuery.ownerOf(task.getProjectId())) && !projectMemberQuery.isActiveMember(task.getProjectId(), userId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, userId + " is not a member of this project.");
            }
        }
        String before = task.getAssignedTo();
        task.setAssignedTo(userId);
        WarehouseTask saved = taskRepository.saveAndFlush(task);
        if (userId != null && !userId.equals(before)) {
            // The new assignee hears of it (docs/domain/notification.md NT2), unless they took it themselves.
            String kind = task.getTaskType() == null || task.getTaskType().isEmpty() ? "Warehouse"
                : task.getTaskType().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + task.getTaskType().substring(1);
            notifications.notifyAssignee(userId, projectAccessService.requireCurrentUserId(), task.getProjectId(), "warehouse_task",
                kind + " task " + task.getTaskNo() + " is yours",
                "From " + (task.getFromLocation() == null ? "-" : task.getFromLocation()) + " to "
                    + (task.getToLocation() == null ? "-" : task.getToLocation()) + ".",
                "warehouse_task", task.getTaskId());
        }
        return responses(List.of(saved)).get(0);
    }

    @Transactional
    public WarehouseTaskResponse cancel(String taskId, String reason) {
        WarehouseTask task = lockOpen(taskId);
        String text = trimToNull(reason);
        if (text == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Say why the task is cancelled.");
        }
        task.setStatus("cancelled");
        task.setCancelReason(text);
        task.setFinishedBy(projectAccessService.requireCurrentUserId());
        task.setFinishedAt(OffsetDateTime.now());
        return responses(List.of(taskRepository.saveAndFlush(task))).get(0);
    }

    /**
     * Usable material, whether staged or still to pick: available stock in open, unexpired LOTs.
     * Both readiness and source selection use this rule; LOTs expiring first, then the oldest record.
     */
    private List<Inventory> usableStock(List<Inventory> rows, LocalDate today) {
        Map<String, LotMaster> lots = lotMasterRepository.findAllById(rows.stream().map(Inventory::getLotId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(LotMaster::getLotId, Function.identity()));
        return rows.stream()
            .filter(row -> AVAILABLE.equals(row.getInventoryStatus()))
            .filter(row -> row.getAvailableQuantity() != null && row.getQuantity() != null && row.getQuantity().signum() > 0)
            .filter(row -> {
                LotMaster lot = row.getLotId() == null ? null : lots.get(row.getLotId());
                return row.getLotId() == null || (lot != null && LotStatus.fromCode(lot.getLotStatus()).usable() && !lot.isExpiredOn(today));
            })
            .sorted(Comparator
                .comparing((Inventory row) -> row.getLotId() == null ? null : lots.get(row.getLotId()).getExpiryDate(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Inventory::getCreatedAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(Inventory::getInventoryId))
            .toList();
    }

    private WarehouseTask task(String projectId, String type, Inventory row, BigDecimal quantity, String to, String workOrderId, String note) {
        WarehouseTask task = new WarehouseTask();
        task.setTaskId(idGenerator.generate());
        task.setProjectId(projectId);
        task.setTaskNo(nextTaskNo(projectId));
        task.setTaskType(type);
        task.setStatus(OPEN);
        task.setInventoryId(row.getInventoryId());
        task.setItemId(row.getItemId());
        task.setLotId(row.getLotId());
        task.setQuantity(quantity);
        task.setFromLocation(trimToNull(row.getLocation()));
        task.setToLocation(to);
        task.setWorkOrderId(workOrderId);
        task.setNote(note);
        task.setCreatedBy(projectAccessService.requireCurrentUserId());
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }

    /** Numbers are per project and never reused; callers hold the project's task lock. */
    private String nextTaskNo(String projectId) {
        return String.format(Locale.ROOT, "WT-%04d", taskRepository.countByProjectId(projectId) + 1);
    }

    /** A place is needed; with a location list it must be an active listed place, spelled as listed. */
    private String destination(String projectId, String location) {
        String to = storageLocationService.resolveForStock(projectId, location);
        if (to == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Say where the stock goes.");
        }
        return to;
    }

    private Map<String, BigDecimal> plannedByRecord(String projectId) {
        Map<String, BigDecimal> planned = new HashMap<>();
        for (WarehouseTask task : taskRepository.findAllByProjectIdAndStatus(projectId, OPEN)) {
            if (task.getAllocationId() == null) planned.merge(task.getInventoryId(), task.getQuantity(), BigDecimal::add);
        }
        return planned;
    }

    private WarehouseTask lockOpen(String taskId) {
        String id = required(taskId, "taskId");
        String projectId = taskRepository.findProjectIdByTaskId(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(projectId);
        // Same first lock as planning and location changes; never wait for it while holding the task row.
        taskRepository.lockProject(projectId);
        WarehouseTask task = taskRepository.findForUpdate(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(task.getProjectId());
        if (!OPEN.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task " + task.getTaskNo() + " is already " + task.getStatus() + ".");
        }
        return task;
    }

    private List<WarehouseTaskResponse> responses(List<WarehouseTask> tasks) {
        Map<String, CatalogItemView> items = catalogQuery.findItems(distinct(tasks.stream().map(WarehouseTask::getItemId)));
        Map<String, LotMaster> lots = byId(lotMasterRepository.findAllById(distinct(tasks.stream().map(WarehouseTask::getLotId))), LotMaster::getLotId);
        Map<String, PickWorkOrder> orders = workOrders.findWorkOrders(distinct(tasks.stream().map(WarehouseTask::getWorkOrderId)));
        return tasks.stream().map(task -> {
            CatalogItemView item = items.get(task.getItemId());
            LotMaster lot = lots.get(task.getLotId());
            // A putaway has no work order, and the map may be one that refuses a null key.
            PickWorkOrder order = task.getWorkOrderId() == null ? null : orders.get(task.getWorkOrderId());
            return new WarehouseTaskResponse(
                task.getTaskId(), task.getProjectId(), task.getTaskNo(), task.getTaskType(), task.getStatus(), task.getInventoryId(),
                task.getItemId(), item == null ? null : item.itemCode(), item == null ? null : item.itemName(),
                task.getLotId(), lot == null ? null : lot.getLotNo(), task.getQuantity(), task.getFromLocation(), task.getToLocation(),
                task.getWorkOrderId(), order == null ? null : order.workOrderNumber(), task.getNote(), task.getCreatedBy(),
                task.getCreatedAt(), task.getFinishedBy(), task.getFinishedAt(), task.getTransferId(), task.getCancelReason(),
                task.getAssignedTo(), task.getAllocationId());
        }).toList();
    }

    private static <T> Map<String, T> byId(Iterable<T> found, Function<T, String> id) {
        return StreamSupport.stream(found.spliterator(), false).collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static Collection<String> distinct(Stream<String> ids) {
        return ids.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String required(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is required.");
        }
        return text;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
