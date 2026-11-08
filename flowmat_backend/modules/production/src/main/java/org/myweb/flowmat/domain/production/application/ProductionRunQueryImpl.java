package org.myweb.flowmat.domain.production.application;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.application.publicapi.ProductionRunQuery;
import org.myweb.flowmat.domain.quality.application.publicapi.ProductionRunRecordingView;
import org.myweb.flowmat.domain.quality.application.publicapi.ProductionRunView;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunQueryImpl implements ProductionRunQuery {

    private static final String NOT_DELETED = "N";

    private final ProductionRunRepository productionRunRepository;
    private final ProductionRunItemRepository productionRunItemRepository;

    @Override
    public Optional<ProductionRunView> findProjectRun(String projectId, String productionRunId) {
        return productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, NOT_DELETED)
            .filter(run -> Objects.equals(projectId, run.getProjectId()))
            .map(ProductionRunQueryImpl::view);
    }

    @Override
    public Map<String, ProductionRunView> findRuns(Collection<String> productionRunIds) {
        if (productionRunIds.isEmpty()) {
            // Unlike Map.of(), an empty map answers a lookup of a null id with null, as the collected map does.
            return Collections.emptyMap();
        }
        return productionRunRepository.findAllById(productionRunIds).stream()
            .collect(Collectors.toMap(ProductionRun::getProductionRunId, ProductionRunQueryImpl::view));
    }

    @Override
    public Optional<ProductionRunView> findRun(String productionRunId) {
        return productionRunRepository.findByProductionRunIdAndDeletedYn(productionRunId, NOT_DELETED).map(ProductionRunQueryImpl::view);
    }

    @Override
    public List<ProductionRunRecordingView> findRecordings(String productionRunId) {
        return productionRunItemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(productionRunId).stream()
            .filter(recording -> !recording.isCancelled())
            .map(recording -> new ProductionRunRecordingView(recording.getItemId(), recording.getLotId(), recording.getDirection()))
            .toList();
    }

    private static ProductionRunView view(ProductionRun run) {
        return new ProductionRunView(run.getProductionRunId(), run.getProjectId(), run.getRunNumber(), run.getRunStatus(),
            run.getTargetItemId());
    }
}
