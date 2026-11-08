package org.myweb.flowmat.domain.quality.application;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.QualityInspectionResponse;
import org.myweb.flowmat.domain.quality.application.publicapi.RunInspectionCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RunInspectionCommandImpl implements RunInspectionCommand {

    private final QualityService qualityService;

    @Override
    @Transactional
    public RecordedInspection recordForRun(String projectId, String productionRunId, String standardId, String inspectionType,
                                           BigDecimal measuredValue, String result, String note) {
        QualityInspectionResponse recorded = qualityService.recordInspection(new QualityInspectionCreateRequest(
            projectId, productionRunId, null, null, inspectionType, result, measuredValue, null, null, null, note, false, standardId));
        return new RecordedInspection(recorded.inspectionId(), recorded.resultStatus());
    }
}
