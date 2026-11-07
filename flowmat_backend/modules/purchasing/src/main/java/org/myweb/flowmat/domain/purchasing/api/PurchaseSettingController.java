package org.myweb.flowmat.domain.purchasing.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseSettingRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.PurchaseSettingResponse;
import org.myweb.flowmat.domain.purchasing.application.PurchaseSettingService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A project's purchasing settings (docs/architecture/adr/ADR-027-purchasing.md decision 14, docs/domain/purchasing.md PO33). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/purchase-settings")
public class PurchaseSettingController {

    private final PurchaseSettingService service;

    @GetMapping
    public ApiResponse<PurchaseSettingResponse> get(@RequestParam(value = "projectId", required = false) String projectId) {
        return ApiResponse.ok(service.get(projectId));
    }

    @PutMapping
    public ApiResponse<PurchaseSettingResponse> set(@RequestBody(required = false) PurchaseSettingRequest request) {
        return ApiResponse.ok(service.set(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "Send the purchasing settings as JSON; approvalLimit is a number and expectedVersion is a whole number."));
    }
}
