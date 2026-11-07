package org.myweb.flowmat.domain.quality.application;

import java.util.List;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.DefectResolveRequest;
import org.myweb.flowmat.domain.quality.api.dto.request.QualityInspectionCreateRequest;
import org.myweb.flowmat.domain.quality.api.dto.response.DefectResponse;
import org.myweb.flowmat.domain.quality.api.dto.response.QualityInspectionResponse;

/** Inspections and defects (docs/domain/quality-inspection.md). */
public interface QualityService {

    /** Newest first; narrowed to one run and/or one LOT when given. */
    List<QualityInspectionResponse> listInspections(String projectId, String productionRunId, String lotId, String itemId);

    /** Records an inspection and, for a failed one when asked, quarantines its LOT in the same transaction. */
    QualityInspectionResponse recordInspection(QualityInspectionCreateRequest request);

    /** Newest first; narrowed to one run and/or one LOT when given, and to unresolved ones when {@code openOnly}. */
    List<DefectResponse> listDefects(String projectId, String productionRunId, String lotId, String itemId, boolean openOnly);

    DefectResponse logDefect(DefectCreateRequest request);

    /** Closes the defect; when the request names stock to scrap, writes it off in the same transaction. */
    DefectResponse resolveDefect(String defectLogId, DefectResolveRequest request);
}
