package org.myweb.flowmat.domain.production.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderWorkingTimeResponse;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A work order's working time for trying a flow in the editor (docs/architecture/adr/ADR-041-try-it-time.md decision 5):
 * the same stretches its production runs wait for and count working-time delays by ({@link ProductionRunWorkingTime}).
 * Read with project read access.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderWorkingTimeService {

    /** The window when only {@code from} is given. */
    static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final String NOT_DELETED = "N";
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final WorkOrderRepository workOrderRepository;
    private final ProjectAccess access;
    private final ProductionRunWorkingTime workingTime;
    private final CatalogQuery catalogQuery;

    public WorkOrderWorkingTimeResponse workingTime(String workOrderId, String from, String until) {
        WorkOrder order = workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(order.getProjectId());
        OffsetDateTime start = dateTime(from, "from");
        OffsetDateTime end = until == null || until.isBlank() ? start.plus(DEFAULT_WINDOW) : dateTime(until, "until");
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "until must be after from.");
        }
        if (Duration.between(start, end).compareTo(WorkOrderCrewService.LONGEST_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The window can be at most 366 days long.");
        }
        ZoneOffset offset = start.getOffset();
        Optional<ProductionRunWorkingTime.TeamTime> team = workingTime.workingTime(order, start, end);
        List<List<String>> stretches = team.map(time -> time.stretches().stream()
            .map(stretch -> List.of(TEXT.format(stretch.start().atOffset(offset)), TEXT.format(stretch.end().atOffset(offset))))
            .toList()).orElse(List.of());
        String equipmentId = team.map(ProductionRunWorkingTime.TeamTime::equipmentId).orElse(null);
        String equipmentCode = equipmentId == null ? null : catalogQuery.findProjectEquipment(order.getProjectId(), equipmentId)
            .map(CatalogEquipmentView::equipmentCode)
            .orElse(null);
        return new WorkOrderWorkingTimeResponse(order.getWorkOrderId(), TEXT.format(start), TEXT.format(end), team.isEmpty(),
            equipmentId, equipmentCode, team.map(ProductionRunWorkingTime.TeamTime::crew).orElse(List.of()), stretches);
    }

    private static OffsetDateTime dateTime(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                name + " must be a date and time with an offset, such as 2030-01-07T09:00:00+09:00.");
        }
    }
}
