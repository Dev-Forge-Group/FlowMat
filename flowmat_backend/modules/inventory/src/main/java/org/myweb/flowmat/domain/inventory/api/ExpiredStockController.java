package org.myweb.flowmat.domain.inventory.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.ExpiredWriteOffRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.ExpiredWriteOffResponse;
import org.myweb.flowmat.domain.inventory.application.ExpiredStockService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Writes off the stock of expired LOTs (docs/domain/lot-expiry.md "만료 재고 폐기"). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lots")
public class ExpiredStockController {

    private final ExpiredStockService expiredStockService;

    @PostMapping("/expired/write-off")
    public ApiResponse<ExpiredWriteOffResponse> writeOff(@Valid @RequestBody ExpiredWriteOffRequest request) {
        return ApiResponse.ok(expiredStockService.writeOff(request));
    }
}
