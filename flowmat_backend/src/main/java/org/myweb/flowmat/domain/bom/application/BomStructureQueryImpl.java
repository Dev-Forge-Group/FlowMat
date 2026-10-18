package org.myweb.flowmat.domain.bom.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomStructureQueryImpl implements BomStructureQuery {
    private final BomHeaderRepository headers;
    private final BomLineRepository lines;

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
}
