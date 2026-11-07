package org.myweb.flowmat.domain.inventory.api;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockMovementSummaryResponse;
import org.myweb.flowmat.domain.inventory.application.StockMovementSummaryService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A period's stock movement per item (docs/domain/stock-ledger.md "기간 수불"). Read only. */
@RestController
@RequiredArgsConstructor
public class StockMovementSummaryController {

    private final StockMovementSummaryService stockMovementSummaryService;

    /** {@code from} and {@code to} are checked by the service, so leaving one out is a 400 with a message. */
    @GetMapping("/stock-movement-summary")
    public ApiResponse<StockMovementSummaryResponse> summary(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to
    ) {
        return ApiResponse.ok(stockMovementSummaryService.summary(projectId, from, to));
    }
}
