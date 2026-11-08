package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineCreateRequest;
import org.myweb.flowmat.domain.bom.api.dto.request.BomLineImportRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomLineImportResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills a draft BOM's materials from a spreadsheet (docs/domain/item-import.md "BOM 자재"). Every row is checked first,
 * with the approval rules that concern a single line (docs/domain/inventory-bom-lot-contract.md §5): the material
 * exists, is not the product, is not listed twice, has no approved BOM of its own, its unit converts to the
 * material's unit, and any scrap is 0–100% on a material line (ADR-031). When any row is wrong, or it is a dry run, nothing is saved; otherwise the lines go through the
 * ordinary add and delete in one transaction.
 */
@Service
@RequiredArgsConstructor
public class BomLineImportService {
    public static final int MAX_ROWS = 500;
    private static final String NOT_DELETED = "N";
    private static final int MAX_INTEGER_DIGITS = 10;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final CatalogQuery catalogQuery;
    private final UnitConversion unitConverter;
    private final BomService bomService;
    private final BomRevisionLock revisionLock;
    private final ProjectAccess projectAccessService;

    @Transactional
    public BomLineImportResponse importLines(String bomId, BomLineImportRequest request) {
        BomHeader header = bomHeaderRepository.findByBomIdAndDeletedYn(bomId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(header.getProjectId());
        revisionLock.lockHeader(header);
        if (BomStatus.fromCode(header.getBomStatus()) != BomStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a draft BOM can take new materials.");
        }
        List<BomLineImportRequest.Row> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The file has no materials.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Import at most " + MAX_ROWS + " materials at a time.");
        }

        Map<String, List<CatalogItemView>> byCode = catalogQuery.findProjectItems(header.getProjectId()).stream()
            .collect(Collectors.groupingBy(CatalogItemView::itemCode));
        List<BomLine> current = bomLineRepository.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(header.getBomId());
        Set<String> taken = request.replace()
            ? new HashSet<>()
            : current.stream().map(BomLine::getChildItemId).collect(Collectors.toCollection(HashSet::new));
        // A material may have its own approved BOM, as long as that does not lead back to this BOM's item.
        Map<String, List<String>> tree = BomTree.approvedChildren(bomHeaderRepository, bomLineRepository, header.getProjectId(),
            header.getTargetItemId());

        List<BomLineImportResponse.RowResult> results = new ArrayList<>();
        List<BomLineCreateRequest> additions = new ArrayList<>();
        int errors = 0;
        for (int index = 0; index < rows.size(); index++) {
            BomLineImportRequest.Row row = rows.get(index);
            String code = trimToNull(row.itemCode());
            List<String> problems = new ArrayList<>();
            BomLineCreateRequest line = check(row, code, header, byCode, taken, tree, problems);
            if (problems.isEmpty()) {
                additions.add(line);
                results.add(new BomLineImportResponse.RowResult(index + 1, code, "add", null));
            } else {
                errors++;
                results.add(new BomLineImportResponse.RowResult(index + 1, code, "error", String.join("; ", problems)));
            }
        }

        int removed = request.replace() ? current.size() : 0;
        boolean apply = errors == 0 && !request.dryRun();
        if (apply) {
            if (request.replace()) {
                for (BomLine line : current) {
                    bomService.deleteLine(header.getBomId(), line.getBomLineId());
                }
            }
            for (BomLineCreateRequest line : additions) {
                bomService.addLine(header.getBomId(), line);
            }
        }
        return new BomLineImportResponse(request.dryRun(), apply, additions.size(), removed, errors, results);
    }

    private BomLineCreateRequest check(
        BomLineImportRequest.Row row,
        String code,
        BomHeader header,
        Map<String, List<CatalogItemView>> byCode,
        Set<String> taken,
        Map<String, List<String>> tree,
        List<String> problems
    ) {
        if (code == null) {
            problems.add("Item code is missing");
            return null;
        }
        List<CatalogItemView> matches = byCode.getOrDefault(code, List.of());
        if (matches.isEmpty()) {
            problems.add("No item has code " + code);
        } else if (matches.size() > 1) {
            problems.add(matches.size() + " items have code " + code);
        }
        BigDecimal quantity = null;
        String quantityText = trimToNull(row.quantity());
        if (quantityText == null) {
            problems.add("Quantity is missing");
        } else {
            try {
                quantity = CsvDecimalParser.parse(quantityText);
                if (quantity.signum() <= 0) {
                    problems.add("Quantity must be greater than 0");
                } else if (CsvDecimalParser.hasMoreThanIntegerDigits(quantity, MAX_INTEGER_DIGITS)) {
                    problems.add("Quantity is too large");
                } else {
                    BigDecimal rounded = quantity.setScale(4, RoundingMode.HALF_UP);
                    if (rounded.signum() == 0) {
                        problems.add("Quantity is too small to store at four decimal places");
                    } else if (rounded.precision() - 4 > MAX_INTEGER_DIGITS) {
                        problems.add("Quantity is too large");
                    }
                }
            } catch (NumberFormatException e) {
                problems.add("Quantity is not a number: " + quantityText);
            }
        }
        String unit = trimToNull(row.unit());
        if (unit == null) {
            problems.add("Unit is missing");
        }
        String lineType = null;
        try {
            lineType = BomServiceImpl.lineType(row.lineType());
        } catch (BusinessException e) {
            problems.add("Type must be material, by_product or waste, not " + row.lineType().trim());
        }
        Boolean phantom = phantom(row.phantom(), problems);
        if (Boolean.TRUE.equals(phantom) && lineType != null && !BomTree.MATERIAL.equals(lineType)) {
            problems.add("Only a material line can be a phantom");
        }
        BigDecimal scrapRate = scrapRate(row.scrapPercent(), lineType, problems);
        if (matches.size() != 1) {
            return null;
        }
        CatalogItemView material = matches.get(0);
        // A phantom is used through its own BOM, as approval checks (docs/domain/multi-level-bom.md P1).
        if (Boolean.TRUE.equals(phantom) && BomTree.MATERIAL.equals(lineType) && !tree.containsKey(material.itemId())) {
            problems.add(code + " is a phantom but has no approved BOM of its own");
        }
        if (!ItemStatusRule.isActive(material.itemStatus())) {
            problems.add(ItemStatusRule.refusal(material.itemCode(), material.itemStatus(), "use it in a BOM"));
        }
        if (material.itemId().equals(header.getTargetItemId())) {
            problems.add(code + " is the item this BOM produces");
        }
        if (!taken.add(material.itemId())) {
            problems.add(code + " is already a material of this BOM; combine the lines");
        }
        // Only what is consumed can lead back to this BOM's item; a by-product or waste line is given off (bom-by-products.md).
        if (BomTree.MATERIAL.equals(lineType) && BomTree.pathTo(material.itemId(), header.getTargetItemId(), tree) != null) {
            problems.add(code + " is made from this BOM's item through its own BOM; a BOM cannot contain itself");
        }
        if (unit != null) {
            try {
                unitConverter.toItemUnit(BigDecimal.ONE, unit, material.unitId());
            } catch (BusinessException e) {
                problems.add(e.getMessage());
            }
        }
        return problems.isEmpty()
            ? new BomLineCreateRequest(material.itemId(), quantity, unit, scrapRate, null, null, null, trimToNull(row.note()),
                lineType, phantom)
            : null;
    }

    /**
     * The scrap column is a percent, as on screen (ADR-031): 5 or 5% is a rate of 0.05. Blank or 0 is none; otherwise up to 100%
     * with at most 2 decimals, which is the rate's 4, and only on a material line.
     */
    private static BigDecimal scrapRate(String value, String lineType, List<String> problems) {
        String text = trimToNull(value);
        if (text == null) return null;
        String number = text.endsWith("%") ? text.substring(0, text.length() - 1).trim() : text;
        BigDecimal percent;
        try {
            percent = CsvDecimalParser.parse(number);
        } catch (NumberFormatException e) {
            problems.add("Scrap is not a number: " + text);
            return null;
        }
        if (percent.signum() < 0 || percent.compareTo(ONE_HUNDRED) > 0) {
            problems.add("Scrap must be from 0% to 100%");
            return null;
        }
        if (percent.stripTrailingZeros().scale() > 2) {
            problems.add("Scrap takes at most 2 decimals (0.01%)");
            return null;
        }
        if (percent.signum() == 0) return null;
        if (lineType != null && !BomTree.MATERIAL.equals(lineType)) {
            problems.add("Only a material line can have scrap");
            return null;
        }
        return percent.movePointLeft(2);
    }

    /** Y, yes, true or 1 is a phantom; blank, N, no, false or 0 is not; anything else is a row problem. */
    private static Boolean phantom(String value, List<String> problems) {
        String text = trimToNull(value);
        if (text == null) return null;
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "y", "yes", "true", "1" -> true;
            case "n", "no", "false", "0" -> false;
            default -> {
                problems.add("Phantom must be Y or N, not " + text);
                yield null;
            }
        };
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
