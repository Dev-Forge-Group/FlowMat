package org.myweb.flowmat.domain.purchasing.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.SupplierResponse;
import org.myweb.flowmat.domain.purchasing.application.SupplierService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A project's supplier list (docs/architecture/adr/ADR-027-purchasing.md decision 10, docs/domain/purchasing.md PO18-PO21). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/suppliers")
public class SupplierController {

    private final SupplierService service;

    @GetMapping
    public ApiResponse<List<SupplierResponse>> list(@RequestParam(value = "projectId", required = false) String projectId) {
        return ApiResponse.ok(service.list(projectId));
    }

    @PostMapping
    public ApiResponse<SupplierResponse> create(@RequestBody(required = false) SupplierRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{supplierId}")
    public ApiResponse<SupplierResponse> update(@PathVariable("supplierId") String supplierId,
                                                @RequestBody(required = false) SupplierRequest request) {
        return ApiResponse.ok(service.update(supplierId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("Send the supplier as JSON; leadTimeDays is a number of days, active is true or false, "
                + "and expectedVersion is a whole number."));
    }
}
