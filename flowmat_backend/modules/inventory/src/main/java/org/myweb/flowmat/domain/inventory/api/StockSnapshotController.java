package org.myweb.flowmat.domain.inventory.api;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockSnapshotResponse;
import org.myweb.flowmat.domain.inventory.application.StockSnapshotService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock as it stood at a past moment (docs/domain/stock-ledger.md "과거 시점 재고"). Read only. */
@RestController
@RequiredArgsConstructor
public class StockSnapshotController {

    private final StockSnapshotService stockSnapshotService;

    /** {@code at} is checked by the service, so leaving it out is a 400 with a message rather than a bare error. */
    @GetMapping("/inventory-snapshots")
    public ApiResponse<StockSnapshotResponse> at(
        @RequestParam("projectId") String projectId,
        @RequestParam(value = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at
    ) {
        return ApiResponse.ok(stockSnapshotService.at(projectId, at));
    }
}
