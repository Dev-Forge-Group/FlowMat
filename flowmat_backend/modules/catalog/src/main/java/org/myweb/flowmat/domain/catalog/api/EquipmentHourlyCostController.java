package org.myweb.flowmat.domain.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentHourlyCostRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentHourlyCostChangeResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentHourlyCostResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentHourlyCostService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}/hourly-cost")
public class EquipmentHourlyCostController {
    private final EquipmentHourlyCostService service;
    @GetMapping public ApiResponse<EquipmentHourlyCostResponse> get(@PathVariable String equipmentId) {
        return ApiResponse.ok(service.get(equipmentId));
    }
    /** Rate changes, newest first (docs/domain/equipment-setup-cost.md AS9). */
    @GetMapping("/history") public ApiResponse<java.util.List<EquipmentHourlyCostChangeResponse>> history(@PathVariable String equipmentId) {
        return ApiResponse.ok(service.history(equipmentId));
    }
    @PutMapping public ApiResponse<EquipmentHourlyCostResponse> set(@PathVariable String equipmentId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.has("hourlyCost")
            || !(body.get("hourlyCost").isNull() || body.get("hourlyCost").isNumber()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "hourlyCost is required as a number or explicit null.");
        JsonNode version = body.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expectedVersion is required as a nonnegative integer.");
        return ApiResponse.ok(service.set(equipmentId, new EquipmentHourlyCostRequest(
            body.get("hourlyCost").isNull() ? null : body.get("hourlyCost").decimalValue(), version.longValue())));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.error("hourlyCost and expectedVersion require valid JSON numbers (hourlyCost may be null)."));
    }
}
