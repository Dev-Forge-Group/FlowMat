package org.myweb.flowmat.domain.catalog.api;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import java.util.List;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentDetails;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentResponse;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentStatusChangeResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments")
public class EquipmentController {

    private final EquipmentService equipmentService;

    @GetMapping
    public ApiResponse<List<EquipmentResponse>> list(@RequestParam String projectId) {
        return ApiResponse.ok(equipmentService.listEquipment(projectId));
    }

    @GetMapping("/{equipmentId}")
    public ApiResponse<EquipmentResponse> get(@PathVariable String equipmentId) {
        return ApiResponse.ok(equipmentService.getEquipment(equipmentId));
    }

    /** Its status changes, newest first (docs/domain/equipment.md "상태 이력"). */
    @GetMapping("/{equipmentId}/status-history")
    public ApiResponse<List<EquipmentStatusChangeResponse>> statusHistory(@PathVariable String equipmentId) {
        return ApiResponse.ok(equipmentService.statusHistory(equipmentId));
    }

    @PostMapping
    public ApiResponse<EquipmentResponse> create(@Valid @RequestBody EquipmentCreateRequest request) {
        return ApiResponse.ok(equipmentService.createEquipment(request));
    }

    @PutMapping("/{equipmentId}")
    public ApiResponse<EquipmentResponse> update(@PathVariable String equipmentId, @Valid @RequestBody EquipmentUpdateRequest request) {
        return ApiResponse.ok(equipmentService.updateEquipment(equipmentId, request));
    }

    @DeleteMapping("/{equipmentId}")
    public ApiResponse<Void> delete(@PathVariable String equipmentId) {
        equipmentService.deleteEquipment(equipmentId);
        return ApiResponse.ok(null);
    }

    /**
     * A body that is not JSON, or a value of the wrong kind such as concurrentWorkOrders "yes" (E12); a concurrentLimit that
     * is not a whole number says so (E16).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonMappingException mapping
                && EquipmentDetails.WholeLimit.MESSAGE.equals(mapping.getOriginalMessage())) {
                return ResponseEntity.badRequest().body(ApiResponse.error(EquipmentDetails.WholeLimit.MESSAGE));
            }
        }
        return ResponseEntity.badRequest().body(ApiResponse.error(
            "Send the equipment as JSON, with numbers as numbers and concurrentWorkOrders as true or false."));
    }
}
