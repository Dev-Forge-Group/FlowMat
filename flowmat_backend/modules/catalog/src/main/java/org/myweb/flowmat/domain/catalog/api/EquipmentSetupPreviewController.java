package org.myweb.flowmat.domain.catalog.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentSetupPreviewResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentSetupPreviewService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}/setup-preview")
public class EquipmentSetupPreviewController {
    private final EquipmentSetupPreviewService service;

    @GetMapping
    public ApiResponse<EquipmentSetupPreviewResponse> preview(@PathVariable String equipmentId,
        @RequestParam(required = false) String fromItemId, @RequestParam(required = false) String toItemId) {
        return ApiResponse.ok(service.preview(equipmentId, fromItemId, toItemId));
    }
}
