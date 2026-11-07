package org.myweb.flowmat.domain.project.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.domain.project.repository.ProjectRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectCalendarService implements ProjectCalendarQuery {
    private final ProjectRepository projects;
    private final Clock projectClock;
    public ZoneId zone(String projectId) {
        return ZoneId.of(projects.findByProjectIdAndDeletedYn(projectId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).getTimeZone());
    }
    public LocalDate today(String projectId) { return date(projectId, projectClock.instant()); }
    public LocalDate date(String projectId, Instant instant) { return instant.atZone(zone(projectId)).toLocalDate(); }
}
