package org.myweb.flowmat.domain.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemDetails;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemImportRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemImportResponse;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.domain.entity.UnitMaster;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemStockCheck;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemStatusRule;

/**
 * Creates and updates items from a spreadsheet, matched by item code (docs/domain/item-import.md). Every row is checked
 * first; when any row is wrong, or it is a dry run, nothing is saved. Otherwise all rows go through the ordinary item
 * create and update in one transaction, so their rules hold here too.
 */
@Service
@RequiredArgsConstructor
public class ItemImportService {

    public static final int MAX_ROWS = 1000;
    private static final String NOT_DELETED = "N";
    /** Integer digits that fit numeric(14,4). */
    private static final int MAX_INTEGER_DIGITS = 10;
    private static final Set<String> YES = Set.of("y", "yes", "true", "1");
    private static final Set<String> NO = Set.of("n", "no", "false", "0");

    private final ItemRepository itemRepository;
    private final UnitMasterRepository unitMasterRepository;
    private final ItemStockCheck itemStockCheck;
    private final ItemService itemService;
    private final ProjectAccess projectAccessService;
    private final EntityManager entities;

    /** A checked row: a create, an update of {@code itemId}, or neither when nothing changes. */
    private record Plan(ItemCreateRequest create, String itemId, ItemUpdateRequest update) {

        static final Plan NOTHING = new Plan(null, null, null);
    }

    @Transactional
    public ItemImportResponse importItems(ItemImportRequest request) {
        String projectId = request.projectId().trim();
        projectAccessService.requireProjectWriteAccess(projectId);
        List<ItemImportRequest.Row> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The file has no items.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Import at most " + MAX_ROWS + " items at a time.");
        }

        List<Item> loaded = itemRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED);
        if (!request.dryRun()) {
            Set<String> requestedCodes = rows.stream().map(row -> trimToNull(row.itemCode())).collect(Collectors.toSet());
            // Refresh before filling blank cells, in a stable lock order for overlapping imports.
            loaded.stream()
                .filter(item -> requestedCodes.contains(item.getItemCode()))
                .sorted(Comparator.comparing(Item::getItemId))
                .forEach(item -> entities.refresh(item, LockModeType.PESSIMISTIC_WRITE));
        }
        Map<String, List<Item>> existing = loaded.stream()
            .filter(item -> NOT_DELETED.equals(item.getDeletedYn()))
            .collect(Collectors.groupingBy(Item::getItemCode));
        Map<String, UnitMaster> units = unitMasterRepository.findAllByActiveYnOrderByUnitTypeAscUnitCodeAsc("Y").stream()
            .collect(Collectors.toMap(unit -> unit.getUnitCode().toLowerCase(Locale.ROOT), Function.identity(), (first, second) -> first));

        Map<String, Item> barcodes = existing.values().stream()
            .flatMap(List::stream)
            .filter(item -> item.getBarcode() != null)
            .collect(Collectors.toMap(Item::getBarcode, Function.identity(), (first, second) -> first));

        List<ItemImportResponse.RowResult> results = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> fileBarcodes = new HashMap<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        for (int index = 0; index < rows.size(); index++) {
            ItemImportRequest.Row row = rows.get(index);
            String code = trimToNull(row.itemCode());
            List<String> problems = new ArrayList<>();
            List<String> changes = new ArrayList<>();
            Plan plan = check(projectId, row, code, existing, units, seen, barcodes, fileBarcodes, problems, changes);
            if (!problems.isEmpty()) {
                errors++;
                results.add(new ItemImportResponse.RowResult(index + 1, code, "error", String.join("; ", problems)));
            } else if (plan.create() != null) {
                created++;
                plans.add(plan);
                results.add(new ItemImportResponse.RowResult(index + 1, code, "create", null));
            } else if (plan.update() != null) {
                updated++;
                plans.add(plan);
                results.add(new ItemImportResponse.RowResult(index + 1, code, "update", String.join(", ", changes)));
            } else {
                unchanged++;
                results.add(new ItemImportResponse.RowResult(index + 1, code, "unchanged", null));
            }
        }

        boolean apply = errors == 0 && !request.dryRun();
        if (apply) {
            for (Plan plan : plans) {
                if (plan.create() != null) {
                    itemService.createItem(plan.create());
                } else {
                    itemService.updateItem(plan.itemId(), plan.update());
                }
            }
        }
        return new ItemImportResponse(request.dryRun(), apply, created, updated, unchanged, errors, results);
    }

    private Plan check(
        String projectId,
        ItemImportRequest.Row row,
        String code,
        Map<String, List<Item>> existing,
        Map<String, UnitMaster> units,
        Set<String> seen,
        Map<String, Item> barcodes,
        Map<String, String> fileBarcodes,
        List<String> problems,
        List<String> changes
    ) {
        if (code == null) {
            problems.add("Item code is missing");
            return Plan.NOTHING;
        }
        if (!seen.add(code)) {
            problems.add("Item code " + code + " appears more than once in the file");
        }
        length(code, 50, "Item code", problems);
        String name = text(row.itemName(), 100, "Name", problems);
        String type = lower(text(row.itemType(), 50, "Type", problems));
        String category = lower(text(row.resourceCategory(), 30, "Category", problems));
        String status = lower(text(row.itemStatus(), 20, "Status", problems));
        if (status != null && ItemStatusRule.normalize(status) == null) {
            problems.add("Status must be one of: " + String.join(", ", ItemStatusRule.STATUSES));
        }
        String unitId = null;
        String unitCode = trimToNull(row.unitCode());
        if (unitCode != null) {
            UnitMaster unit = units.get(unitCode.toLowerCase(Locale.ROOT));
            if (unit == null) {
                problems.add("Unit " + unitCode + " does not exist or is inactive");
            } else {
                unitId = unit.getUnitId();
            }
        }
        String lot = lot(row.lotTracked(), problems);
        BigDecimal safety = decimal(row.safetyStockQty(), "Safety stock", problems);
        Integer lead = whole(row.leadTimeDays(), "Lead time", problems);
        BigDecimal cost = decimal(row.unitCost(), "Unit cost", problems);
        String group = text(row.itemGroup(), 50, "Group", problems);
        String spec = text(row.spec(), 200, "Spec", problems);
        String barcode = text(row.barcode(), 100, "Barcode", problems);
        String sku = text(row.sku(), 100, "SKU", problems);
        String storage = text(row.storageCondition(), 100, "Storage", problems);
        String description = text(row.description(), 2000, "Description", problems);
        if (barcode != null) {
            String earlier = fileBarcodes.putIfAbsent(barcode, code);
            if (earlier != null && !earlier.equals(code)) {
                problems.add("Barcode " + barcode + " appears more than once in the file");
            }
            Item holder = barcodes.get(barcode);
            // Moving a barcode from one item to another takes two imports: clear it first, then give it.
            if (holder != null && !holder.getItemCode().equals(code)) {
                problems.add("Barcode " + barcode + " is already used by item " + holder.getItemCode());
            }
        }
        boolean detailCells = group != null || spec != null || barcode != null || sku != null || storage != null || description != null;
        String packUnit = text(row.purchaseUnit(), 20, "Purchase unit", problems);
        BigDecimal packQty = decimal(row.purchaseUnitQty(), "Purchase unit quantity", problems);
        if (packQty != null && packQty.signum() == 0) {
            problems.add("Purchase unit quantity must be more than 0");
            packQty = null;
        }
        if (packQty != null && packQty.stripTrailingZeros().scale() > 8) {
            problems.add("Purchase unit quantity takes at most 8 decimals");
            packQty = null;
        }

        List<Item> matches = existing.getOrDefault(code, List.of());
        if (matches.size() > 1) {
            problems.add(matches.size() + " items already have code " + code + "; change them on the Items tab");
        }
        if (!problems.isEmpty()) {
            return Plan.NOTHING;
        }
        if (packQty != null && packUnit == null && (matches.isEmpty() || matches.get(0).getPurchaseUnit() == null)) {
            problems.add("Give the purchase unit with its quantity");
            return Plan.NOTHING;
        }
        if (matches.isEmpty()) {
            if (name == null) {
                problems.add("A new item needs a name");
                return Plan.NOTHING;
            }
            return new Plan(new ItemCreateRequest(projectId, code, name, type, category, null, unitId, status,
                lot == null ? "N" : lot, safety, lead, cost,
                detailCells ? new ItemDetails(group, spec, barcode, sku, storage, description) : null, packUnit, packQty), null, null);
        }

        Item item = matches.get(0);
        if (name != null && !name.equals(item.getItemName())) {
            changes.add("name");
        }
        if (type != null && !type.equals(item.getItemType())) {
            changes.add("type");
        }
        if (category != null && !category.equals(item.getResourceCategory())) {
            changes.add("category");
        }
        if (unitId != null && !unitId.equals(item.getUnitId())) {
            changes.add("unit");
        }
        if (status != null && !status.equals(item.getItemStatus())) {
            changes.add("status");
        }
        if (lot != null && !lot.equals(item.getLotManageYn())) {
            // Checked here too so the whole file is refused before anything is saved.
            if (itemStockCheck.hasStockRecords(item.getItemId())) {
                problems.add("LOT tracking can only change while the item has no stock records");
                return Plan.NOTHING;
            }
            changes.add("LOT tracking");
        }
        if (safety != null && !sameNumber(safety, item.getSafetyStockQty())) {
            changes.add("safety stock");
        }
        if (lead != null && !lead.equals(item.getLeadTimeDays())) {
            changes.add("lead time");
        }
        if (cost != null && !sameNumber(cost, item.getUnitCost())) {
            changes.add("unit cost");
        }
        if (packUnit != null && !packUnit.equals(item.getPurchaseUnit())) {
            changes.add("purchase unit");
        }
        if (packQty != null && !sameNumber(packQty, item.getPurchaseUnit() == null && packUnit == null ? null : item.getConversionRate())) {
            changes.add("purchase unit quantity");
        }
        // Details are sent whole, so blank cells take the stored values.
        int before = changes.size();
        ItemDetails details = new ItemDetails(
            pick(group, item.getItemGroup(), "group", changes),
            pick(spec, item.getSpec(), "spec", changes),
            pick(barcode, item.getBarcode(), "barcode", changes),
            pick(sku, item.getSku(), "SKU", changes),
            pick(storage, item.getStorageCondition(), "storage", changes),
            pick(description, item.getItemDesc(), "description", changes));
        if (changes.isEmpty()) {
            return Plan.NOTHING;
        }
        return new Plan(null, item.getItemId(), new ItemUpdateRequest(name, type, category, null, unitId, status, lot, safety, lead,
            cost, null, changes.size() > before ? details : null, packUnit, packQty));
    }

    private static String text(String value, int max, String what, List<String> problems) {
        String trimmed = trimToNull(value);
        length(trimmed, max, what, problems);
        return trimmed;
    }

    private static void length(String value, int max, String what, List<String> problems) {
        if (value != null && value.length() > max) {
            problems.add(what + " is longer than " + max + " characters");
        }
    }

    private static String lot(String value, List<String> problems) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        if (YES.contains(key)) {
            return "Y";
        }
        if (NO.contains(key)) {
            return "N";
        }
        problems.add("LOT tracking must be Y or N, not " + trimmed);
        return null;
    }

    private static BigDecimal decimal(String value, String what, List<String> problems) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            BigDecimal number = CsvDecimalParser.parse(trimmed);
            if (number.signum() < 0) {
                problems.add(what + " cannot be negative");
                return null;
            }
            if (number.signum() == 0) {
                return number;
            }
            if (CsvDecimalParser.hasMoreThanIntegerDigits(number, MAX_INTEGER_DIGITS)
                || number.setScale(4, RoundingMode.HALF_UP).precision() - 4 > MAX_INTEGER_DIGITS) {
                problems.add(what + " is too large");
                return null;
            }
            return number;
        } catch (NumberFormatException e) {
            problems.add(what + " is not a number: " + trimmed);
            return null;
        }
    }

    private static Integer whole(String value, String what, List<String> problems) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            int number = Integer.parseInt(trimmed);
            if (number < 0) {
                problems.add(what + " cannot be negative");
                return null;
            }
            return number;
        } catch (NumberFormatException e) {
            problems.add(what + " must be a whole number of days: " + trimmed);
            return null;
        }
    }

    /** The cell when given, else the stored value; notes a change. */
    private static String pick(String cell, String stored, String what, List<String> changes) {
        if (cell == null) {
            return stored;
        }
        if (!cell.equals(stored)) {
            changes.add(what);
        }
        return cell;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return b != null && a.compareTo(b) == 0;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
