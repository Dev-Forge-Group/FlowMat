package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.api.dto.response.ReorderLineResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockAlertResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.entity.StockAlert;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.inventory.repository.StockAlertRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock alerts (docs/domain/stock-alert.md): a row whose available stock is below its minimum, or whose stock on hand
 * is above its maximum, has one open alert of that type; the alert closes by itself once the row is back inside.
 *
 * <p>{@link #evaluate} runs inside the stock movement's transaction while the movement holds the row lock, so two
 * movements cannot open two alerts for the same row. The periodic sweep locks the row itself.
 */
@Service
@RequiredArgsConstructor
public class StockAlertService {

    static final String SYSTEM = "system";
    private static final String OPEN = "N";
    private static final String NOT_DELETED = "N";

    private final StockAlertRepository stockAlertRepository;
    private final InventoryRepository inventoryRepository;
    private final LotMasterRepository lotMasterRepository;
    private final ProjectAccessService projectAccessService;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final InventoryCatalogReferences catalogReferences;
    private final IdGenerator idGenerator;
    private final org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand notifications;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery projectMemberQuery;

    /** How many days ahead of a LOT's expiry date its stock raises an expiry alert. */
    @Value("${app.stock-alert.expiry-warning-days:7}")
    private int expiryWarningDays;

    /** Brings the row's open alerts in line with its current numbers; {@code inventory} holds the values now. */
    @Transactional
    public void evaluate(Inventory inventory) {
        boolean active = "N".equals(inventory.getDeletedYn());
        BigDecimal available = zeroIfNull(inventory.getAvailableQuantity());
        BigDecimal onHand = zeroIfNull(inventory.getQuantity());
        BigDecimal min = inventory.getMinThreshold();
        BigDecimal max = inventory.getMaxThreshold();
        boolean low = active && min != null && min.signum() > 0 && available.compareTo(min) < 0;
        boolean over = active && max != null && onHand.compareTo(max) > 0;
        sync(inventory, StockAlert.LOW, low, min, available,
            available.signum() == 0 ? "critical" : "warning",
            "Available " + plain(available) + " is below the minimum " + plain(min) + ".");
        sync(inventory, StockAlert.OVER, over, max, onHand, "info",
            "On hand " + plain(onHand) + " is above the maximum " + plain(max) + ".");

        // Stock of a LOT that expires soon, or already has: the date moves on by itself, so the sweep keeps this current.
        LotMaster lot = inventory.getLotId() == null ? null : lotMasterRepository.findById(inventory.getLotId()).orElse(null);
        LocalDate today = projectCalendar.today(inventory.getProjectId());
        long daysLeft = lot == null || lot.getExpiryDate() == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(today, lot.getExpiryDate());
        boolean expiring = active && onHand.signum() > 0 && daysLeft <= expiryWarningDays;
        sync(inventory, StockAlert.EXPIRY, expiring, BigDecimal.valueOf(expiryWarningDays), BigDecimal.valueOf(Math.min(daysLeft, 99_999)),
            daysLeft < 0 ? "critical" : "warning",
            lot == null ? null : "LOT " + lot.getLotNo() + (daysLeft < 0 ? " expired on " : " expires on ") + lot.getExpiryDate() + ".");
    }

    /** For the sweep: locks the row, then evaluates it. A row that no longer exists closes its alerts. */
    @Transactional
    public void evaluateLocked(String inventoryId) {
        Optional<Inventory> row = inventoryRepository.findForUpdate(inventoryId);
        if (row.isPresent()) {
            evaluate(row.get());
            return;
        }
        for (String type : List.of(StockAlert.LOW, StockAlert.OVER, StockAlert.EXPIRY)) {
            stockAlertRepository.findFirstByInventoryIdAndAlertTypeAndResolvedYn(inventoryId, type, OPEN).ifPresent(this::close);
        }
    }

    @Transactional(readOnly = true)
    public List<StockAlertResponse> listAlerts(String projectId, boolean openOnly) {
        projectAccessService.requireProjectReadAccess(projectId);
        List<StockAlert> alerts = openOnly
            ? stockAlertRepository.findAllByProjectIdAndResolvedYnOrderByTriggeredAtDesc(projectId, OPEN)
            : stockAlertRepository.findTop200ByProjectIdOrderByTriggeredAtDesc(projectId);
        Map<String, Inventory> rows = byId(inventoryRepository.findAllById(ids(alerts.stream().map(StockAlert::getInventoryId))),
            Inventory::getInventoryId);
        Map<String, CatalogItemView> items = byId(catalogReferences.items(ids(alerts.stream().map(StockAlert::getItemId))), CatalogItemView::itemId);
        Map<String, String> lotNos = StreamSupport.stream(
                lotMasterRepository.findAllById(ids(rows.values().stream().map(Inventory::getLotId))).spliterator(), false)
            .collect(Collectors.toMap(LotMaster::getLotId, LotMaster::getLotNo, (first, second) -> first));
        Map<String, String> units = catalogReferences.unitCodes(ids(items.values().stream().map(CatalogItemView::unitId)));
        return alerts.stream().map(alert -> {
            Inventory row = rows.get(alert.getInventoryId());
            CatalogItemView item = items.get(alert.getItemId());
            return new StockAlertResponse(
                alert.getStockAlertId(),
                alert.getProjectId(),
                alert.getInventoryId(),
                alert.getItemId(),
                item == null ? null : item.itemCode(),
                item == null ? null : item.itemName(),
                row == null ? null : row.getLocation(),
                row == null || row.getLotId() == null ? null : lotNos.get(row.getLotId()),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getThresholdValue(),
                alert.getActualValue(),
                item == null ? null : units.get(item.unitId()),
                alert.getAlertMessage(),
                alert.isResolved(),
                alert.getTriggeredAt(),
                alert.getResolvedAt()
            );
        }).toList();
    }

    /**
     * Items whose usable stock over all their records is below their safety stock, the biggest shortfall (as a share of
     * the safety stock) first. Usable is what could go into production: not quarantined, and not of a closed or expired
     * LOT. Worked out when asked, so nothing is stored.
     */
    @Transactional(readOnly = true)
    public List<ReorderLineResponse> reorderList(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        List<CatalogItemView> watched = catalogReferences.projectItems(projectId).stream()
            .filter(item -> item.safetyStockQty() != null && item.safetyStockQty().signum() > 0)
            // An inactive or discontinued item takes no new stock, so it is not suggested for ordering.
            .filter(item -> ItemStatusRule.isActive(item.itemStatus()))
            .toList();
        if (watched.isEmpty()) {
            return List.of();
        }
        List<Inventory> rows = inventoryRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED);
        LocalDate today = projectCalendar.today(projectId);
        Map<String, LotMaster> lots = byId(lotMasterRepository.findAllById(ids(rows.stream().map(Inventory::getLotId))), LotMaster::getLotId);
        Map<String, BigDecimal> usable = ReorderLines.usable(rows, lots, today, row -> true);
        Map<String, String> units = catalogReferences.unitCodes(ids(watched.stream().map(CatalogItemView::unitId)));
        return ReorderLines.shortLines(watched, watched.stream().collect(Collectors.toMap(CatalogItemView::itemId, CatalogItemView::safetyStockQty)),
            usable, units, null);
    }

    private void sync(Inventory inventory, String type, boolean breached, BigDecimal threshold, BigDecimal actual,
                      String severity, String message) {
        Optional<StockAlert> open = stockAlertRepository.findFirstByInventoryIdAndAlertTypeAndResolvedYn(
            inventory.getInventoryId(), type, OPEN);
        if (!breached) {
            open.ifPresent(this::close);
            return;
        }
        if (open.isPresent() && Objects.equals(open.get().getSeverity(), severity)
            && sameAmount(open.get().getActualValue(), actual) && sameAmount(open.get().getThresholdValue(), threshold)) {
            return;
        }
        boolean opening = open.isEmpty();
        StockAlert alert = open.orElseGet(() -> {
            OffsetDateTime now = OffsetDateTime.now();
            StockAlert created = new StockAlert();
            created.setStockAlertId(idGenerator.generate());
            created.setProjectId(inventory.getProjectId());
            created.setInventoryId(inventory.getInventoryId());
            created.setItemId(inventory.getItemId());
            created.setAlertType(type);
            created.setResolvedYn(OPEN);
            created.setTriggeredAt(now);
            created.setCreatedAt(now);
            return created;
        });
        alert.setSeverity(severity);
        alert.setThresholdValue(threshold);
        alert.setActualValue(actual);
        alert.setAlertMessage(message);
        stockAlertRepository.save(alert);
        if (opening) {
            announce(inventory, alert);
        }
    }

    /**
     * An alert that opens tells the project's owner (docs/domain/notification.md NT7), whoever moved the stock and also from
     * the sweep, and the members who chose stock alerts (NS4), the owner once; one that only changes severity, or stays
     * open, tells no one again.
     */
    private void announce(Inventory inventory, StockAlert alert) {
        String owner = projectMemberQuery.ownerOf(inventory.getProjectId());
        String what = switch (alert.getAlertType()) {
            case StockAlert.LOW -> "Low stock";
            case StockAlert.OVER -> "Over the maximum";
            default -> "Expiring stock";
        };
        String item = catalogReferences.item(inventory.getItemId()).map(CatalogItemView::itemCode).orElse(inventory.getItemId());
        String place = inventory.getLocation() == null || inventory.getLocation().isBlank() ? "" : " at " + inventory.getLocation();
        String title = what + ": " + item + place;
        if (owner != null) {
            notifications.tell(owner, inventory.getProjectId(), "stock_alert", title, alert.getAlertMessage(), "stock_alert",
                alert.getStockAlertId());
        }
        notifications.tellSubscribers(inventory.getProjectId(), "stock_alert", owner == null ? Set.of() : Set.of(owner),
            "stock_alert", title, alert.getAlertMessage(), "stock_alert", alert.getStockAlertId());
    }

    private void close(StockAlert alert) {
        alert.setResolvedYn("Y");
        alert.setResolvedAt(OffsetDateTime.now());
        alert.setResolvedBy(SYSTEM);
        stockAlertRepository.save(alert);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static <T> Map<String, T> byId(Iterable<T> found, Function<T, String> id) {
        return StreamSupport.stream(found.spliterator(), false).collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }

    private static Collection<String> ids(Stream<String> values) {
        return values.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
