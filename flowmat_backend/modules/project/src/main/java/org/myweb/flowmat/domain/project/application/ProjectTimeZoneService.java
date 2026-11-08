package org.myweb.flowmat.domain.project.application;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.ZoneId;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.api.dto.response.ProjectTimeZoneResponse;
import org.myweb.flowmat.domain.project.domain.entity.Project;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectTimeZoneService {
    private final ProjectAccessService access;
    private final EntityManager entities;
    public ProjectTimeZoneResponse get(String id) { return response(access.requireProjectReadAccess(id)); }
    @Transactional
    public ProjectTimeZoneResponse set(String id, JsonNode body) {
        Project project=access.requireProjectOwnerAccess(id);
        if(body==null || !body.isObject() || !body.path("timeZone").isTextual()) throw bad("timeZone requires an IANA zone ID.");
        String zone=body.path("timeZone").textValue();
        if(zone.length()>100 || !ZoneId.getAvailableZoneIds().contains(zone)) throw bad("timeZone requires an IANA zone ID, e.g. Asia/Seoul or UTC.");
        JsonNode version=body.path("expectedVersion");
        if(!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue()<0) throw bad("expectedVersion requires a nonnegative integer.");
        long expected=version.longValue();
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        access.requireProjectOwnerAccess(id);
        String actor=access.requireCurrentUserId();
        long current=project.getTimeZoneVersion();
        if(current!=expected) {
            if(expected<Long.MAX_VALUE && current==expected+1 && zone.equals(project.getTimeZone()) && Objects.equals(actor,project.getTimeZoneUpdatedBy())) return response(project);
            throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion is stale; reload the project time zone.");
        }
        if(current==Long.MAX_VALUE) throw new BusinessException(ErrorCode.CONFLICT,"expectedVersion is exhausted.");
        project.setTimeZone(zone); project.setTimeZoneVersion(current+1); project.setTimeZoneUpdatedBy(actor);
        return response(project);
    }
    private static ProjectTimeZoneResponse response(Project p) { return new ProjectTimeZoneResponse(p.getProjectId(),p.getTimeZone(),p.getTimeZoneVersion()); }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
}
