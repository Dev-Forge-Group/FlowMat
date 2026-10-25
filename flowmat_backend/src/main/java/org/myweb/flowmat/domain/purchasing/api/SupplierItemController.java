package org.myweb.flowmat.domain.purchasing.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierItemRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.SupplierItemResponse;
import org.myweb.flowmat.domain.purchasing.application.SupplierItemService;
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

/** Items' usual suppliers (docs/architecture/adr/ADR-027-purchasing.md decision 13, docs/domain/purchasing.md PO29-PO31). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/supplier-items")
public class SupplierItemController {

    private final SupplierItemService service;

    @GetMapping
    public ApiResponse<List<SupplierItemResponse>> list(@RequestParam(value = "projectId", required = false) String projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PutMapping
    public ApiResponse<SupplierItemResponse> set(@RequestBody(required = false) SupplierItemRequest request) {
        return ApiResponse.ok(service.set(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Send the usual supplier as JSON; expectedVersion is a whole number."));
    }
}
