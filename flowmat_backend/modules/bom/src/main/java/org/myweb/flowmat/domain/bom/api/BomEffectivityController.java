package org.myweb.flowmat.domain.bom.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomEffectivityRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomEffectivityResponse;
import org.myweb.flowmat.domain.bom.application.BomEffectivityService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/boms/{bomId}/effectivity")
public class BomEffectivityController {
    private final BomEffectivityService service;
    @GetMapping
    public ApiResponse<BomEffectivityResponse> get(@PathVariable("bomId") String bomId) {
        return ApiResponse.ok(service.get(bomId));
    }
    @PostMapping
    public ApiResponse<BomEffectivityResponse> change(@PathVariable("bomId") String bomId,@Valid @RequestBody BomEffectivityRequest request) {
        return ApiResponse.ok(service.change(bomId,request));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("effectiveFrom/effectiveTo must be valid ISO dates, expectedPeriodVersion a whole number, and requestId a UUID."));
    }
}
