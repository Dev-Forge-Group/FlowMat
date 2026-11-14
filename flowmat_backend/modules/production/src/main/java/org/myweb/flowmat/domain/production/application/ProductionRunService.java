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

    /**
     * Records one item. With a {@code requestId}, sending the same recording again returns the row recorded the first time
     * and moves nothing, and sending other contents under the key is refused (docs/domain/production-run-recording.md RR1-RR2).
     */
    ProductionRunItemResponse recordRunItem(String productionRunId, ProductionRunItemRecordRequest request);

    /**
     * Records row {@code requestPart} (from 1) of a split over LOTs under the split's request key, which the split has already
     * checked for a resend (RR3). Without a key in the request it records as {@link #recordRunItem} would.
     */
    ProductionRunItemResponse recordRunItemPart(String productionRunId, ProductionRunItemRecordRequest request, int requestPart);

    ProductionRunResponse finishRun(String productionRunId, ProductionRunFinishRequest request);

    /**
     * Cancels a recorded item of an open run: reverses its stock movement (re-checking today's stock), marks it
     * cancelled, and rebuilds the run's LOT genealogy without it. BOM plan lines are not recordings and cannot be
     * cancelled. With {@code reallocate}, what a cancelled input gave back is allocated to the run's work order again in
     * the same transaction, or nothing changes (docs/domain/stock-allocation.md R4).
     */
    ProductionRunItemResponse cancelRunItem(String productionRunId, String productionRunItemId, String reason, boolean reallocate);
}
