package org.myweb.flowmat.domain.inventory.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotReleaseHistoryResponse;
import org.myweb.flowmat.domain.inventory.application.LotReleaseHistoryService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A LOT's release history (docs/domain/lot-release.md R8). Project read. */
@RestController
@RequiredArgsConstructor
public class LotReleaseHistoryController {
    private final LotReleaseHistoryService service;

    @GetMapping("/lots/{lotId}/release-history")
    public ApiResponse<LotReleaseHistoryResponse> history(@PathVariable("lotId") String lotId) {
        return ApiResponse.ok(service.history(lotId));
    }
}
