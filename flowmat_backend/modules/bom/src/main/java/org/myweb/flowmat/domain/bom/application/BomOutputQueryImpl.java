package org.myweb.flowmat.domain.bom.application;

import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomOutputQuery;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomOutputQueryImpl implements BomOutputQuery {
    private final BomHeaderRepository headers;
    private final BomLineRepository lines;
    private final ProjectAccessService access;

    @Override
    public Set<String> findByProductItemIds(String projectId, String bomId) {
        return itemIdsOf(projectId, bomId, "by_product");
    }

    @Override
    public Set<String> findWasteItemIds(String projectId, String bomId) {
        return itemIdsOf(projectId, bomId, "waste");
    }

    private Set<String> itemIdsOf(String projectId, String bomId, String lineType) {
        access.requireProjectReadAccess(projectId);
        if (bomId == null) return Set.of();
        headers.findById(bomId).filter(header -> projectId.equals(header.getProjectId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return lines.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(bomId).stream()
            .filter(line -> lineType.equals(line.getLineType()))
            .map(line -> line.getChildItemId()).collect(Collectors.toSet());
    }
}
