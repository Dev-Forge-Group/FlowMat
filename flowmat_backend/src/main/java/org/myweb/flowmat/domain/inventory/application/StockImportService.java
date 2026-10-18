package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.StockImportRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockImportResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives stock from a spreadsheet (docs/domain/stock-import.md), for opening balances or a large delivery. Each row
 * either receives into the record already at that item, place and LOT, or makes a new record; a LOT number the project
 * does not know yet is registered first. Every row is checked before anything is saved; when any row is wrong, or it is
 * a dry run, nothing is. Saving goes through the ordinary LOT registration, stock record creation and stock command, so
 * every receipt is in the ledger.
 */
@Service
@RequiredArgsConstructor
public class StockImportService {

    public static final int MAX_ROWS = 1000;
    public static final String REFERENCE_TYPE = "stock_import";
    private static final int MAX_INTEGER_DIGITS = 10;

    private final CatalogQuery catalogQuery;
    private final LotMasterRepository lotMasterRepository;
    private final InventoryRepository inventoryRepository;
    private final LotService lotService;
    private final InventoryService inventoryService;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccessService projectAccessService;
    private final StorageLocationService storageLocationService;

    /** A checked row. {@code lotNo} with a null {@code lotId} is a LOT the import registers. */
    private record Plan(CatalogItemView item, String location, String lotNo, String lotId, LocalDate expiry, BigDecimal quantity, String inventoryId) {
    }

    @Transactional
    public StockImportResponse importStock(StockImportRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        List<StockImportRequest.Row> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The file has no stock rows.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Import at most " + MAX_ROWS + " rows at a time.");
        }

        Map<String, List<CatalogItemView>> items = catalogQuery.findProjectItems(projectId).stream()
            .collect(Collectors.groupingBy(CatalogItemView::itemCode));
        // LOT numbers are unique in a project regardless of case.
        Map<String, LotMaster> lots = lotMasterRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId).stream()
            .collect(Collectors.toMap(lot -> key(lot.getLotNo()), Function.identity(), (first, second) -> first));
        // LOT numbers the file registers, with the plan of the first row that names each.
        Map<String, Plan> newLots = new HashMap<>();
        Set<String> places = new HashSet<>();
        StorageLocationService.LocationCheck locations = storageLocationService.locationCheck(projectId);

        List<StockImportResponse.RowResult> results = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        int created = 0;
        int received = 0;
        int errors = 0;
        for (int index = 0; index < rows.size(); index++) {
            StockImportRequest.Row row = rows.get(index);
            String code = trimToNull(row.itemCode());
            List<String> problems = new ArrayList<>();
            Plan plan = check(projectId, row, code, items, lots, newLots, places, locations, problems);
            if (!problems.isEmpty()) {
                errors++;
                results.add(new StockImportResponse.RowResult(index + 1, code, "error", String.join("; ", problems)));
                continue;
            }
            plans.add(plan);
            if (plan.inventoryId() != null) {
                received++;
                results.add(new StockImportResponse.RowResult(index + 1, code, "receive", "into the record" + where(plan.location())));
            } else {
                created++;
                boolean registers = plan.lotId() == null && plan.lotNo() != null && newLots.get(key(plan.lotNo())) == plan;
                results.add(new StockImportResponse.RowResult(index + 1, code, "create", registers ? "new LOT " + plan.lotNo() : null));
            }
        }

        boolean apply = errors == 0 && !request.dryRun();
        if (apply) {
            save(projectId, plans, trimToNull(request.note()));
        }
        return new StockImportResponse(request.dryRun(), apply, created, received, newLots.size(), errors, results);
    }

    private void save(String projectId, List<Plan> plans, String note) {
        String importId = UUID.randomUUID().toString();
        String actor = projectAccessService.requireCurrentUserId();
        Map<String, String> registered = new HashMap<>();
        for (Plan plan : plans) {
            String lotId = plan.lotId();
            if (lotId == null && plan.lotNo() != null) {
                lotId = registered.computeIfAbsent(key(plan.lotNo()), lotKey -> lotService.createLot(
                    new LotCreateRequest(projectId, plan.item().itemId(), plan.lotNo(), null, null, plan.expiry())).lotId());
            }
            if (plan.inventoryId() != null) {
                inventoryCommandService.apply(new InventoryMovement(plan.inventoryId(), InventoryTransactionType.RECEIPT,
                    plan.quantity(), BigDecimal.ZERO, REFERENCE_TYPE, importId, note == null ? "Imported stock" : note, null, actor));
            } else {
                inventoryService.createInventory(new InventoryAdjustRequest(projectId, plan.item().itemId(), plan.quantity(),
                    null, null, plan.location(), null, null, null, null, lotId));
            }
        }
    }

    private Plan check(
        String projectId,
        StockImportRequest.Row row,
        String code,
        Map<String, List<CatalogItemView>> items,
        Map<String, LotMaster> lots,
        Map<String, Plan> newLots,
        Set<String> places,
        StorageLocationService.LocationCheck locations,
        List<String> problems
    ) {
        if (code == null) {
            problems.add("Item code is missing");
            return null;
        }
        List<CatalogItemView> matches = items.getOrDefault(code, List.of());
        if (matches.isEmpty()) {
            problems.add("No item has code " + code);
        } else if (matches.size() > 1) {
            problems.add(matches.size() + " items have code " + code);
        }
        String packsText = trimToNull(row.packs());
        BigDecimal quantity = null;
        BigDecimal packs = null;
        if (packsText == null) {
            quantity = quantity(row.quantity(), "Quantity", problems);
        } else if (trimToNull(row.quantity()) != null) {
            problems.add("Give the quantity or the packs, not both");
        } else {
            packs = quantity(packsText, "Packs", problems);
        }
        String location = trimToNull(row.location());
        if (location != null && location.length() > 100) {
            problems.add("Location is longer than 100 characters");
        } else if (location != null) {
            // With a location list, the row's place must be listed and active; it is saved as spelled in the list.
            try {
                location = locations.resolve(location);
            } catch (BusinessException exception) {
                problems.add(exception.getMessage());
            }
        }
        String lotNo = trimToNull(row.lotNo());
        if (lotNo != null && lotNo.length() > 100) {
            problems.add("LOT number is longer than 100 characters");
        }
        LocalDate expiry = date(row.expiryDate(), problems);
        if (matches.size() != 1) {
            return null;
        }
        CatalogItemView item = matches.get(0);
        if (!ItemStatusRule.isActive(item.itemStatus())) {
            problems.add(ItemStatusRule.refusal(item.itemCode(), item.itemStatus(), "receive stock"));
        }
        boolean tracked = "Y".equals(item.lotManageYn());
        if (tracked && lotNo == null) {
            problems.add(code + " is LOT-tracked; give the LOT number");
        }
        if (!tracked && lotNo != null) {
            problems.add(code + " is not LOT-tracked; leave the LOT empty");
        }
        if (!tracked && expiry != null) {
            problems.add("Only a LOT has an expiry date");
        }
        if (packs != null) {
            if (item.purchaseUnit() == null || item.conversionRate() == null) {
                problems.add(code + " has no purchase unit; give the quantity");
            } else {
                quantity = storedQuantity(packs.multiply(item.conversionRate()), "Quantity", problems);
            }
        }

        String lotId = null;
        if (tracked && lotNo != null) {
            LotMaster known = lots.get(key(lotNo));
            if (known != null) {
                lotId = known.getLotId();
                if (!known.getItemId().equals(item.itemId())) {
                    problems.add("LOT " + known.getLotNo() + " is for a different item");
                }
                LotStatus status = LotStatus.fromCode(known.getLotStatus());
                if (status == LotStatus.CLOSED || status == LotStatus.QUARANTINED) {
                    problems.add("LOT " + known.getLotNo() + " is " + status.code());
                }
                if (expiry != null && !expiry.equals(known.getExpiryDate())) {
                    problems.add("LOT " + known.getLotNo() + " already exists with expiry "
                        + (known.getExpiryDate() == null ? "none" : known.getExpiryDate()));
                }
            } else {
                Plan first = newLots.get(key(lotNo));
                if (first != null && !first.item().itemId().equals(item.itemId())) {
                    problems.add("LOT " + lotNo + " is already given to " + first.item().itemCode() + " in this file");
                }
                if (first != null && expiry != null && !expiry.equals(first.expiry())) {
                    problems.add("LOT " + lotNo + " has a different expiry on an earlier line");
                }
            }
        }
        String place = item.itemId() + "|" + (location == null ? "" : location) + "|" + (lotNo == null ? "" : key(lotNo));
        if (!places.add(place)) {
            problems.add("The same item, location and LOT appear more than once; combine the lines");
        }
        if (!problems.isEmpty() || quantity == null) {
            return null;
        }

        String inventoryId = null;
        if (lotId != null) {
            inventoryId = inventoryRepository.findLotStockAt(projectId, item.itemId(), lotId, location)
                .map(Inventory::getInventoryId).orElse(null);
        } else if (!tracked) {
            inventoryId = inventoryRepository.findStockAt(projectId, item.itemId(), location).stream()
                .map(Inventory::getInventoryId).findFirst().orElse(null);
        }
        Plan plan = new Plan(item, location, lotNo, lotId, expiry, quantity, inventoryId);
        if (tracked && lotId == null) {
            newLots.putIfAbsent(key(lotNo), plan);
        }
        return plan;
    }

    private static BigDecimal quantity(String value, String what, List<String> problems) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            problems.add(what + " is missing");
            return null;
        }
        try {
            BigDecimal number = CsvDecimalParser.parse(trimmed);
            if (number.signum() <= 0) {
                problems.add(what + " must be greater than 0");
                return null;
            }
            return storedQuantity(number, what, problems);
        } catch (NumberFormatException e) {
            problems.add(what + " is not a number: " + trimmed);
            return null;
        }
    }

    static BigDecimal storedQuantity(BigDecimal number, String what, List<String> problems) {
        if (CsvDecimalParser.hasMoreThanIntegerDigits(number, MAX_INTEGER_DIGITS)) {
            problems.add(what + " is too large");
            return null;
        }
        BigDecimal rounded = number.setScale(4, RoundingMode.HALF_UP);
        if (rounded.precision() - 4 > MAX_INTEGER_DIGITS) {
            problems.add(what + " is too large");
            return null;
        }
        if (rounded.signum() == 0) {
            problems.add(what + " is too small to store at four decimal places");
            return null;
        }
        return rounded;
    }

    private static LocalDate date(String value, List<String> problems) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            problems.add("Expiry date must look like 2027-01-31, not " + trimmed);
            return null;
        }
    }

    private static String where(String location) {
        return location == null ? " without a location" : " at " + location;
    }

    private static String key(String lotNo) {
        return lotNo.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
