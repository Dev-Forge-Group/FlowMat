package org.myweb.flowmat.domain.production.application;

import java.util.List;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunFinishRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunItemRecordRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStartRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunItemResponse;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunResponse;

public interface ProductionRunService {

    List<ProductionRunResponse> listRuns(String workflowId);

    ProductionRunResponse startRun(ProductionRunStartRequest request);

    ProductionRunResponse getRun(String productionRunId);

    List<ProductionRunItemResponse> listRunItems(String productionRunId);

    ProductionRunItemResponse recordRunItem(String productionRunId, ProductionRunItemRecordRequest request);

    ProductionRunResponse finishRun(String productionRunId, ProductionRunFinishRequest request);

    /**
     * Cancels a recorded item of an open run: reverses its stock movement (re-checking today's stock), marks it
     * cancelled, and rebuilds the run's LOT genealogy without it. BOM plan lines are not recordings and cannot be
     * cancelled. With {@code reallocate}, what a cancelled input gave back is allocated to the run's work order again in
     * the same transaction, or nothing changes (docs/domain/stock-allocation.md R4).
     */
    ProductionRunItemResponse cancelRunItem(String productionRunId, String productionRunItemId, String reason, boolean reallocate);
}
