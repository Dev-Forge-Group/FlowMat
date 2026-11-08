package org.myweb.flowmat.domain.catalog.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentChangeoverRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.EquipmentChangeoverUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentChangeoverResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentChangeoverService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Changeover times on equipment (docs/domain/equipment-changeover.md); every change returns the whole list. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}/changeovers")
public class EquipmentChangeoverController {

    private final EquipmentChangeoverService equipmentChangeoverService;

    @GetMapping
    public ApiResponse<List<EquipmentChangeoverResponse>> list(@PathVariable("equipmentId") String equipmentId) {
        return ApiResponse.ok(equipmentChangeoverService.list(equipmentId));
    }

    @PostMapping
    public ApiResponse<List<EquipmentChangeoverResponse>> add(
        @PathVariable("equipmentId") String equipmentId,
        @RequestBody EquipmentChangeoverRequest request
    ) {
        return ApiResponse.ok(equipmentChangeoverService.add(equipmentId, request));
    }

    @PutMapping("/{changeoverId}")
    public ApiResponse<List<EquipmentChangeoverResponse>> update(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("changeoverId") String changeoverId,
        @RequestBody EquipmentChangeoverUpdateRequest request
    ) {
        return ApiResponse.ok(equipmentChangeoverService.update(equipmentId, changeoverId, request));
    }

    @DeleteMapping("/{changeoverId}")
    public ApiResponse<List<EquipmentChangeoverResponse>> remove(
        @PathVariable("equipmentId") String equipmentId,
        @PathVariable("changeoverId") String changeoverId
    ) {
        return ApiResponse.ok(equipmentChangeoverService.remove(equipmentId, changeoverId));
    }
}
