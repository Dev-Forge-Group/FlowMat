package org.myweb.flowmat.domain.production.application;

import java.util.List;
import org.myweb.flowmat.domain.production.api.dto.request.RunCorrectionCreateRequest;
import org.myweb.flowmat.domain.production.api.dto.response.RunCorrectionResponse;

/** Corrections of finished production runs (docs/domain/production-run-correction.md). */
public interface ProductionRunCorrectionService {

    List<RunCorrectionResponse> listCorrections(String productionRunId);

    RunCorrectionResponse requestCorrection(String productionRunId, RunCorrectionCreateRequest request);

    /** Approving applies the correction: stock, recordings, output quantity and LOT genealogy change together. */
    RunCorrectionResponse approveCorrection(String productionRunId, String correctionId);

    RunCorrectionResponse rejectCorrection(String productionRunId, String correctionId, String note);
}
