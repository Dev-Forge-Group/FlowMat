package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomStructureQueryImpl implements BomStructureQuery {
    private final BomHeaderRepository headers;
    private final BomLineRepository lines;
    private final CatalogQuery catalogQuery;
    private final UnitConverter unitConverter;

    @Override
    public Map<String, String> approvedBomIdByItem(String projectId, LocalDate on) {
        Map<String, String> ids = new HashMap<>();
        BomTree.approvedByItem(headers, projectId, on).forEach((itemId, header) -> ids.put(itemId, header.getBomId()));
        return ids;
    }

    @Override
    public Map<String, List<String>> approvedChildren(String projectId) {
        return BomTree.approvedChildren(headers, lines, projectId, null);
    }

    @Override
    public List<String> lineItemIds(String bomId) {
        return lines.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(bomId).stream().map(BomLine::getChildItemId).toList();
    }

    @Override
    public Map<String, SubstituteShare> substituteShares(String bomId) {
        List<BomLine> bomLines = lines.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(bomId);
        Map<String, BomLine> mainOf = BomTree.mainLines(bomLines);
        if (mainOf.isEmpty()) {
            return Map.of();
        }
        Map<String, CatalogItemView> items = catalogQuery.findItems(bomLines.stream().map(BomLine::getChildItemId).collect(Collectors.toSet()));
        Map<String, SubstituteShare> shares = new HashMap<>();
        for (BomLine line : bomLines) {
            BomLine main = mainOf.get(line.getBomLineId());
            if (main == null) {
                continue;
            }
            try {
                BigDecimal quantity = planned(line, items);
                if (quantity != null && quantity.signum() > 0) {
                    shares.put(line.getChildItemId(), new SubstituteShare(main.getChildItemId(),
                        planned(main, items).divide(quantity, 12, RoundingMode.HALF_UP)));
                }
            } catch (BusinessException unconvertible) {
                // A quantity that cannot be converted cannot be set against the main material.
            }
        }
        return shares;
    }

    @Override
    public java.util.Set<String> optionalItemIds(String bomId, LocalDate on) {
        java.util.Set<String> optional = new java.util.HashSet<>();
        headers.findById(bomId).ifPresent(header -> collectOptional(bomId,
            on == null ? Map.of() : BomTree.approvedByItem(headers, header.getProjectId(), on), optional, new java.util.HashSet<>()));
        return optional;
    }

    private void collectOptional(String bomId, Map<String, BomHeader> approved, java.util.Set<String> optional,
                                 java.util.Set<String> seen) {
        // Approval keeps phantoms free of loops and at most MAX_LEVELS deep; this only guards against bad data.
        if (!seen.add(bomId) || seen.size() > BomTree.MAX_LEVELS) {
            return;
        }
        for (BomLine line : lines.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(bomId)) {
            if ("Y".equals(line.getOptionalYn())) {
                optional.add(line.getChildItemId());
            }
            BomHeader phantom = "Y".equals(line.getPhantomYn()) ? approved.get(line.getChildItemId()) : null;
            if (phantom != null) {
                collectOptional(phantom.getBomId(), approved, optional, seen);
            }
        }
    }

    /** A line's quantity with its planned scrap, in its item's unit (ADR-031 decision 1). */
    private BigDecimal planned(BomLine line, Map<String, CatalogItemView> items) {
        CatalogItemView item = items.get(line.getChildItemId());
        return unitConverter.toItemUnit(line.getQuantity().multiply(BomTree.scrapFactor(line)), line.getUnit(),
            item == null ? null : item.unitId()).quantity();
    }
}
