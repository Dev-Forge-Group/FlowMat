package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomLineView;
import org.myweb.flowmat.domain.bom.application.publicapi.BomView;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.BuildableQuantityResponse;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * How much of a BOM's product the usable stock could make right now (docs/domain/material-requirements.md "지금 만들 수
 * 있는 양"): each material allows usable ÷ what one batch needs × the batch size, and the product is limited by the
 * material that allows least. What a batch needs is the line quantity in the material's unit, 4 decimals, as the BOM
 * requirement calculation gives it for a whole batch (worked out per batch rather than per unit, so a small per-unit need
 * is not lost to rounding). Rounded down, to whole units when the product is counted, since a part of the last unit cannot
 * be made.
 */
@Service
@RequiredArgsConstructor
public class BuildableQuantityService {

    private static final int SCALE = 4;

    private final BomQuery bomQuery;
    private final CatalogQuery catalogQuery;
    private final UnitConverter unitConverter;
    private final UsableStock usableStock;

    public BuildableQuantityResponse buildable(String bomId) {
        BomView bom = bomQuery.getBom(bomId);
        Stock stock = stock(bom.projectId(), List.of(bom));
        return compute(bom, stock);
    }

    /**
     * Every approved BOM of the project against one read of the stock. A BOM that cannot be worked out (a material or
     * unit that has since gone) comes back with the reason instead of failing the rest.
     */
    public List<BuildableQuantityResponse> approved(String projectId) {
        List<BomView> boms = bomQuery.listBoms(projectId, null).stream()
            .filter(BomView::approved)
            .toList();
        Stock stock = stock(projectId.trim(), boms);
        List<BuildableQuantityResponse> result = new ArrayList<>();
        for (BomView bom : boms) {
            try {
                result.add(compute(bom, stock));
            } catch (BusinessException e) {
                result.add(new BuildableQuantityResponse(bom.bomId(), bom.targetItemId(), bom.baseUnit(), bom.baseQuantity(),
                    null, null, List.of(), e.getMessage()));
            }
        }
        return result;
    }

    /** The items and usable stock the BOMs need, each read once. */
    private record Stock(Map<String, CatalogItemView> items, Map<String, BigDecimal> usable) {
    }

    private Stock stock(String projectId, List<BomView> boms) {
        Set<String> materials = boms.stream()
            .flatMap(bom -> bom.lines().stream().filter(BomLineView::material).map(BomLineView::childItemId))
            .collect(Collectors.toSet());
        Set<String> itemIds = new HashSet<>(materials);
        boms.forEach(bom -> itemIds.add(bom.targetItemId()));
        Map<String, CatalogItemView> items = catalogQuery.findActiveItems(itemIds);
        return new Stock(items, materials.isEmpty() ? Map.of() : usableStock.byItem(projectId, materials));
    }

    private BuildableQuantityResponse compute(BomView bom, Stock stock) {
        CatalogItemView target = stock.items().get(bom.targetItemId());
        if (target == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The BOM's product no longer exists.");
        }
        BigDecimal batch = unitConverter.toItemUnit(bom.baseQuantity(), bom.baseUnit(), target.unitId()).quantity();
        if (batch == null || batch.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base quantity must be greater than 0.");
        }
        int buildableScale = "count".equals(target.unitType()) ? 0 : SCALE;

        Map<String, BigDecimal> needs = new LinkedHashMap<>();
        Map<String, String> itemUnits = new LinkedHashMap<>();
        if (bom.lines().stream().anyMatch(BomLineView::phantom)) {
            // A phantom is never stocked: count the materials it goes through instead (multi-level-bom.md P2, P4).
            for (var line : bomQuery.calculateRequirements(bom.bomId(), batch).lines()) {
                needs.merge(line.childItemId(), line.requiredItemQuantity().setScale(SCALE, RoundingMode.HALF_UP), BigDecimal::add);
                itemUnits.putIfAbsent(line.childItemId(), line.itemUnit());
            }
        }
        for (BomLineView line : needs.isEmpty() ? bom.lines() : List.<BomLineView>of()) {
            // The batch is planned on main lines without optional materials (ADR-032 decision 5, ADR-033 decision 7).
            if (!line.material() || line.substituteFor() != null || "Y".equals(line.optionalYn())) {
                continue;
            }
            CatalogItemView child = stock.items().get(line.childItemId());
            if (child == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Material " + line.childItemId() + " no longer exists.");
            }
            // With the line's planned scrap on top, as the requirement plans it (ADR-031 decision 1).
            BigDecimal planned = line.scrapRate() == null || line.scrapRate().signum() == 0 ? line.quantity()
                : line.quantity().multiply(BigDecimal.ONE.add(line.scrapRate()));
            UnitConverter.Conversion need = unitConverter.toItemUnit(planned, line.unit(), child.unitId());
            // The BOM rounds each converted line before adding it. Repeated materials share one stock balance.
            needs.merge(child.itemId(), need.quantity().setScale(SCALE, RoundingMode.HALF_UP), BigDecimal::add);
            itemUnits.putIfAbsent(child.itemId(), need.toUnitCode());
        }
        List<BuildableQuantityResponse.Line> lines = new ArrayList<>();
        BigDecimal least = null;
        String limiting = null;
        for (Map.Entry<String, BigDecimal> need : needs.entrySet()) {
            String itemId = need.getKey();
            BigDecimal perBatch = need.getValue();
            BigDecimal have = stock.usable().getOrDefault(itemId, BigDecimal.ZERO).max(BigDecimal.ZERO);
            // A need that rounds to nothing does not limit the product.
            BigDecimal allows = perBatch.signum() <= 0 ? null
                : have.multiply(batch).divide(perBatch, buildableScale, RoundingMode.DOWN);
            if (allows != null && (least == null || allows.compareTo(least) < 0)) {
                least = allows;
                limiting = itemId;
            }
            lines.add(new BuildableQuantityResponse.Line(itemId, itemUnits.get(itemId), perBatch,
                have.setScale(SCALE, RoundingMode.HALF_UP), allows));
        }
        return new BuildableQuantityResponse(bom.bomId(), bom.targetItemId(), target.unitCode() == null ? bom.baseUnit() : target.unitCode(),
            batch, least, limiting, lines, null);
    }
}
