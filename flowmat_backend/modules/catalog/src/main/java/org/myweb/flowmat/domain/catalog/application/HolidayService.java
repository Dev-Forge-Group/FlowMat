package org.myweb.flowmat.domain.catalog.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.HolidayRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.HolidayResponse;
import org.myweb.flowmat.domain.catalog.application.publicapi.ProjectHolidayQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.ProjectHoliday;
import org.myweb.flowmat.domain.catalog.repository.ProjectHolidayRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project's holidays (docs/domain/equipment-schedule.md "휴일"): dates on which no calendar shift starts. Every change
 * returns the whole list, oldest date first.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HolidayService implements ProjectHolidayQuery {

    private static final String NOT_DELETED = "N";
    private static final int NAME_LENGTH = 100;

    private final ProjectHolidayRepository holidayRepository;
    private final ProjectAccessService projectAccessService;
    private final IdGenerator idGenerator;

    @Override
    public Set<LocalDate> holidays(String projectId, LocalDate first, LocalDate last) {
        return holidayRepository.findAllByProjectIdAndHolidayDateBetweenAndDeletedYn(projectId, first, last, NOT_DELETED).stream()
            .map(ProjectHoliday::getHolidayDate)
            .collect(Collectors.toUnmodifiableSet());
    }

    public List<HolidayResponse> list(String projectId) {
        String project = requireProject(projectId);
        projectAccessService.requireProjectReadAccess(project);
        return responses(project);
    }

    @Transactional
    public List<HolidayResponse> add(HolidayRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId and date are required.");
        }
        String project = requireProject(request.projectId());
        projectAccessService.requireProjectWriteAccess(project);
        LocalDate date = date(request.date());
        String name = request.name() == null || request.name().isBlank() ? null : request.name().trim();
        if (name != null && name.length() > NAME_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "name takes at most " + NAME_LENGTH + " characters.");
        }
        if (holidayRepository.existsByProjectIdAndHolidayDateAndDeletedYn(project, date, NOT_DELETED)) {
            throw new BusinessException(ErrorCode.CONFLICT, date + " is already a holiday.");
        }
        ProjectHoliday holiday = new ProjectHoliday();
        holiday.setHolidayId(idGenerator.generate());
        holiday.setProjectId(project);
        holiday.setHolidayDate(date);
        holiday.setHolidayName(name);
        holiday.setCreatedBy(projectAccessService.requireCurrentUserId());
        holidayRepository.saveAndFlush(holiday);
        return responses(project);
    }

    @Transactional
    public List<HolidayResponse> remove(String holidayId) {
        ProjectHoliday holiday = holidayRepository.findByHolidayIdAndDeletedYn(holidayId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(holiday.getProjectId());
        holiday.setDeletedYn("Y");
        holiday.setUpdatedBy(projectAccessService.requireCurrentUserId());
        holidayRepository.save(holiday);
        return responses(holiday.getProjectId());
    }

    private List<HolidayResponse> responses(String projectId) {
        return holidayRepository.findAllByProjectIdAndDeletedYnOrderByHolidayDateAsc(projectId, NOT_DELETED).stream()
            .map(holiday -> new HolidayResponse(holiday.getHolidayId(), holiday.getHolidayDate(), holiday.getHolidayName(),
                holiday.getCreatedBy()))
            .toList();
    }

    private static String requireProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        return projectId.trim();
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "date is required.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "date must be a date like 2030-01-01.");
        }
    }
}
