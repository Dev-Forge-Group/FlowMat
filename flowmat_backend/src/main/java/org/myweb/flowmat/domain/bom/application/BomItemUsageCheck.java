package org.myweb.flowmat.domain.bom.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.ItemUsageCheck;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.springframework.stereotype.Component;

/** An item cannot be deleted while a BOM that is not retired makes it or uses it. */
@Component
@RequiredArgsConstructor
public class BomItemUsageCheck implements ItemUsageCheck {

    private static final String NOT_DELETED = "N";

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;

    @Override
    public Optional<String> whyInUse(CatalogItemView item) {
        Set<String> names = new LinkedHashSet<>();
        bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
                item.projectId(), item.itemId(), NOT_DELETED).stream()
            .filter(BomItemUsageCheck::live)
            .forEach(header -> names.add(describe(header)));
        List<String> usingBomIds = bomLineRepository.findAllByChildItemId(item.itemId()).stream()
            .map(BomLine::getBomId)
            .distinct()
            .toList();
        bomHeaderRepository.findAllById(usingBomIds).stream()
            .filter(header -> item.projectId().equals(header.getProjectId()) && NOT_DELETED.equals(header.getDeletedYn()))
            .filter(BomItemUsageCheck::live)
            .forEach(header -> names.add(describe(header)));
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("it is in BOM " + String.join(", ", names) + "; retire or delete those BOMs first");
    }

    private static boolean live(BomHeader header) {
        return BomStatus.fromCode(header.getBomStatus()) != BomStatus.RETIRED;
    }

    private static String describe(BomHeader header) {
        return header.getBomName() + " v" + header.getBomVersion() + " (" + header.getBomStatus() + ")";
    }
}
