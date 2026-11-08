package org.myweb.flowmat.domain.catalog.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.response.EquipmentDayHistoryResponse;
import org.myweb.flowmat.domain.catalog.application.EquipmentDayHistorySearchService;
import org.myweb.flowmat.global.history.HistoryPageResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An equipment's date and downtime history beyond the latest 100 entries: the version history search's filters plus kind day
 * or downtime. Keep the filters when following nextCursor.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/equipments/{equipmentId}/day-history/search")
public class EquipmentDayHistorySearchController {
    private final EquipmentDayHistorySearchService service;

    @GetMapping
    public ApiResponse<HistoryPageResponse<EquipmentDayHistoryResponse>> search(
        @PathVariable("equipmentId") String equipmentId,
        @RequestParam(value = "kind", required = false) String kind,
        @RequestParam(value = "action", required = false) String action,
        @RequestParam(value = "savedBy", required = false) String savedBy,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "limit", required = false) String limit,
        @RequestParam(value = "cursor", required = false) String cursor
    ) {
        return ApiResponse.ok(service.search(equipmentId, kind, action, savedBy, from, to, limit, cursor));
    }
}
