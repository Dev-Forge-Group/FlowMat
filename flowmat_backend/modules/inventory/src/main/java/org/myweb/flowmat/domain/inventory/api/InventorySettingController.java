package org.myweb.flowmat.domain.inventory.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventorySettingRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventorySettingResponse;
import org.myweb.flowmat.domain.inventory.application.InventorySettingService;
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

/** A project's inventory settings (docs/domain/storage-location.md SL5-SL7). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inventory-settings")
public class InventorySettingController {

    private final InventorySettingService service;

    @GetMapping
    public ApiResponse<InventorySettingResponse> get(@RequestParam(value = "projectId", required = false) String projectId) {
        return ApiResponse.ok(service.get(projectId));
    }

    @PutMapping
    public ApiResponse<InventorySettingResponse> set(@RequestBody(required = false) InventorySettingRequest request) {
        return ApiResponse.ok(service.set(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send the inventory settings as JSON; expectedVersion is a whole number."));
    }
}
