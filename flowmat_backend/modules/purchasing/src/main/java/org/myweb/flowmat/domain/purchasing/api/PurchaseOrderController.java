package org.myweb.flowmat.domain.purchasing.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseCreditChangeRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseCreditRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderDatesRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderEndRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseReceiptRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseReturnRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.PurchaseOrderResponse;
import org.myweb.flowmat.domain.purchasing.application.PurchaseOrderService;
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

/** Purchase orders and their receipts (docs/architecture/adr/ADR-027-purchasing.md, docs/domain/purchasing.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    @GetMapping
    public ApiResponse<List<PurchaseOrderResponse>> list(@RequestParam(value = "projectId", required = false) String projectId,
                                                         @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(service.list(projectId, status));
    }

    @GetMapping("/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderResponse> get(@PathVariable("purchaseOrderId") String purchaseOrderId) {
        return ApiResponse.ok(service.get(purchaseOrderId));
    }

    @PostMapping
    public ApiResponse<PurchaseOrderResponse> create(@RequestBody(required = false) PurchaseOrderRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderResponse> update(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                     @RequestBody(required = false) PurchaseOrderRequest request) {
        return ApiResponse.ok(service.update(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/order")
    public ApiResponse<PurchaseOrderResponse> order(@PathVariable("purchaseOrderId") String purchaseOrderId) {
        return ApiResponse.ok(service.order(purchaseOrderId));
    }

    @PostMapping("/{purchaseOrderId}/approve")
    public ApiResponse<PurchaseOrderResponse> approve(@PathVariable("purchaseOrderId") String purchaseOrderId) {
        return ApiResponse.ok(service.approve(purchaseOrderId));
    }

    @PostMapping("/{purchaseOrderId}/cancel")
    public ApiResponse<PurchaseOrderResponse> cancel(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                     @RequestBody(required = false) PurchaseOrderEndRequest request) {
        return ApiResponse.ok(service.cancel(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/close")
    public ApiResponse<PurchaseOrderResponse> close(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                    @RequestBody(required = false) PurchaseOrderEndRequest request) {
        return ApiResponse.ok(service.close(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/receipts")
    public ApiResponse<PurchaseOrderResponse> receive(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                      @RequestBody(required = false) PurchaseReceiptRequest request) {
        return ApiResponse.ok(service.receive(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/expected-dates")
    public ApiResponse<PurchaseOrderResponse> changeDates(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                          @RequestBody(required = false) PurchaseOrderDatesRequest request) {
        return ApiResponse.ok(service.changeDates(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/returns")
    public ApiResponse<PurchaseOrderResponse> giveBack(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                       @RequestBody(required = false) PurchaseReturnRequest request) {
        return ApiResponse.ok(service.giveBack(purchaseOrderId, request));
    }

    @PostMapping("/{purchaseOrderId}/returns/{returnId}/credit")
    public ApiResponse<PurchaseOrderResponse> credit(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                     @PathVariable("returnId") String returnId,
                                                     @RequestBody(required = false) PurchaseCreditRequest request) {
        return ApiResponse.ok(service.credit(purchaseOrderId, returnId, request));
    }

    @PutMapping("/{purchaseOrderId}/returns/{returnId}/credit")
    public ApiResponse<PurchaseOrderResponse> changeCredit(@PathVariable("purchaseOrderId") String purchaseOrderId,
                                                           @PathVariable("returnId") String returnId,
                                                           @RequestBody(required = false) PurchaseCreditChangeRequest request) {
        return ApiResponse.ok(service.changeCredit(purchaseOrderId, returnId, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("Send the purchase order as JSON; quantities are numbers and requestId is a UUID."));
    }
}
