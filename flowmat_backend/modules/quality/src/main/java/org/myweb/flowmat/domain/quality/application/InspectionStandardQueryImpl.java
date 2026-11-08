package org.myweb.flowmat.domain.quality.application;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.application.publicapi.InspectionStandardQuery;
import org.myweb.flowmat.domain.quality.application.publicapi.InspectionStandardView;
import org.myweb.flowmat.domain.quality.domain.entity.InspectionStandard;
import org.myweb.flowmat.domain.quality.repository.InspectionStandardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionStandardQueryImpl implements InspectionStandardQuery {

    private final InspectionStandardRepository inspectionStandardRepository;

    @Override
    public Map<String, InspectionStandardView> findStandards(Collection<String> standardIds) {
        if (standardIds.isEmpty()) {
            // Unlike Map.of(), an empty map answers a lookup of a null id with null, as the collected map does.
            return Collections.emptyMap();
        }
        return inspectionStandardRepository.findAllById(standardIds).stream()
            .collect(Collectors.toMap(InspectionStandard::getStandardId, InspectionStandardQueryImpl::view));
    }

    private static InspectionStandardView view(InspectionStandard standard) {
        return new InspectionStandardView(standard.getStandardId(), standard.getProjectId(), standard.getItemId(),
            standard.getInspectionType(), standard.getStage(), standard.getStandardMin(), standard.getStandardMax(),
            standard.getUnit(), "Y".equals(standard.getActiveYn()) && !"Y".equals(standard.getDeletedYn()));
    }
}
