package org.myweb.flowmat.domain.quality.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.quality.api.dto.response.LotReleaseResponse;
import org.myweb.flowmat.domain.quality.application.LotReleaseService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Releasing a LOT after its receipt checks (docs/domain/lot-release.md R3). Project write access. */
@RestController
@RequiredArgsConstructor
public class LotReleaseController {

    private final LotReleaseService lotReleaseService;

    @PostMapping("/lots/{lotId}/release")
    public ApiResponse<LotReleaseResponse> release(@PathVariable("lotId") String lotId) {
        return ApiResponse.ok(lotReleaseService.release(lotId));
    }
}
