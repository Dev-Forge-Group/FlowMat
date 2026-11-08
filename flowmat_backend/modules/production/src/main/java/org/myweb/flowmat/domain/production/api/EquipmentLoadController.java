package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.EquipmentLoadResponse;
import org.myweb.flowmat.domain.production.application.EquipmentLoadService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equipment load board (docs/domain/equipment-load.md). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/equipment-load")
public class EquipmentLoadController {

    private final EquipmentLoadService equipmentLoadService;

    /** {@code from} and {@code to} are ISO date-times with an offset; the window is at most 92 days. */
    @GetMapping
    public ApiResponse<EquipmentLoadResponse> load(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.ok(equipmentLoadService.load(projectId, from, to));
    }
}
