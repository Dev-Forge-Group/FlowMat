package org.myweb.flowmat.domain.payment.api;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanPricingResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.PlanResponse;
import org.myweb.flowmat.domain.payment.application.PlanService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plans")
public class PlanController {

    private final PlanService planService;

    // 활성 요금제 목록
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlanResponse>>> getActivePlans() {
        return ResponseEntity.ok(ApiResponse.ok(planService.getActivePlans()));
    }

    // 요금제 단건 조회
    @GetMapping("/{planId}")
    public ResponseEntity<ApiResponse<PlanResponse>> getPlan(@PathVariable("planId") UUID planId) {
        return ResponseEntity.ok(ApiResponse.ok(planService.getPlan(planId)));
    }

    // 요금제별 개월수 가격 정책 조회
    @GetMapping("/{planId}/pricing")
    public ResponseEntity<ApiResponse<List<PlanPricingResponse>>> getPricing(
        @PathVariable("planId") UUID planId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(planService.getActivePricing(planId)));
    }
}
