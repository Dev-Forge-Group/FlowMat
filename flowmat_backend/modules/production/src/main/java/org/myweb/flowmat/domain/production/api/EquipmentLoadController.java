package org.myweb.flowmat.domain.production.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.EquipmentLoadResponse;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse;
import org.myweb.flowmat.domain.production.application.EquipmentLoadService;
import org.myweb.flowmat.domain.production.application.SequenceSnapshotService;
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
    private final SequenceSnapshotService sequenceSnapshotService;

    /** {@code from} and {@code to} are ISO date-times with an offset; the window is at most 92 days. */
    @GetMapping
    public ApiResponse<EquipmentLoadResponse> load(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.ok(equipmentLoadService.load(projectId, from, to));
    }

    /**
     * One equipment's work orders in the week from {@code from}, as the input for comparing orders of them, with what the
     * plan and the current changeover rule cost (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md).
     */
    @GetMapping("/sequence-snapshot")
    public ApiResponse<SequenceSnapshotResponse> sequenceSnapshot(
        @RequestParam(value = "projectId", required = false) String projectId,
        @RequestParam(value = "equipmentId", required = false) String equipmentId,
        @RequestParam(value = "from", required = false) String from
    ) {
        return ApiResponse.ok(sequenceSnapshotService.snapshot(projectId, equipmentId, from));
    }
}
