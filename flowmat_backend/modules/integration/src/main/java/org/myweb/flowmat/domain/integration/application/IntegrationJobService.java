package org.myweb.flowmat.domain.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemImportCommand;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.integration.api.dto.request.IntegrationJobRequest;
import org.myweb.flowmat.domain.integration.api.dto.request.IntegrationScheduleRequest;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationJobResponse;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationRunResponse;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationJob;
import org.myweb.flowmat.domain.integration.repository.IntegrationJobRepository;
import org.myweb.flowmat.domain.integration.repository.IntegrationRunRepository;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockImportCommand;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Making and changing integration jobs (docs/architecture/adr/ADR-038-integration-jobs.md). Only the project's owner
 * does, as for connector profiles; every member reads them. A job's operation must be one the profile version approves,
 * its profile switched on and its target allowed, checked when the job is saved and again on every run.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IntegrationJobService {

    static final Set<String> KINDS = Set.of("pull_items", "pull_stock");
    /**
     * Kinds that may run on a schedule. A stock pull receives what it reads, so reading the same list again would
     * receive it again; it runs by hand until the import can tell a receipt it already took (ADR-038 decision 6).
     */
    static final Set<String> SCHEDULED_KINDS = Set.of("pull_items");
    private static final String NOT_DELETED = "N";

    private final IntegrationJobRepository jobs;
    private final IntegrationRunRepository runs;
    private final ProjectAccess access;
    private final ProjectCalendarQuery calendar;
    private final ConnectorProfileQuery profiles;
    private final ConnectorMappingQuery mappings;
    private final ItemImportCommand items;
    private final StockImportCommand stock;
    private final IdGenerator ids;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public List<IntegrationJobResponse> list(String projectId) {
        access.requireProjectReadAccess(projectId);
        return jobs.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<IntegrationRunResponse> runs(String projectId, String jobId) {
        access.requireProjectReadAccess(projectId);
        require(projectId, jobId);
        return runs.findTop20ByJobIdOrderByStartedAtDesc(jobId).stream().map(run -> IntegrationRunService.toResponse(run, json)).toList();
    }

    public IntegrationJobResponse create(String projectId, IntegrationJobRequest request) {
        access.requireProjectOwnerAccess(projectId);
        IntegrationJob job = new IntegrationJob();
        job.setJobId(ids.generate());
        job.setProjectId(projectId);
        apply(job, request);
        String user = access.requireCurrentUserId();
        job.setCreatedBy(user);
        job.setUpdatedBy(user);
        return toResponse(jobs.save(job));
    }

    public IntegrationJobResponse update(String projectId, String jobId, IntegrationJobRequest request) {
        access.requireProjectOwnerAccess(projectId);
        IntegrationJob job = jobs.lock(jobId, projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.version() == null) throw bad("Send the job's version with the change.");
        if (request.version() != job.getVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Someone changed this job since it was read. Reload it and make the change again.");
        }
        apply(job, request);
        job.setVersion(job.getVersion() + 1);
        job.setUpdatedBy(access.requireCurrentUserId());
        return toResponse(job);
    }

    public void delete(String projectId, String jobId) {
        access.requireProjectOwnerAccess(projectId);
        IntegrationJob job = jobs.lock(jobId, projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        job.setDeletedYn("Y");
        job.setScheduleEnabled(false);
        job.setNextRunAt(null);
        job.setVersion(job.getVersion() + 1);
        job.setUpdatedBy(access.requireCurrentUserId());
    }

    /**
     * Switches the schedule on, as the current user, or off. Switching on starts the count of failed runs again and
     * clears a pause; the first run is the schedule's next time from now.
     */
    public IntegrationJobResponse schedule(String projectId, String jobId, IntegrationScheduleRequest request) {
        access.requireProjectOwnerAccess(projectId);
        IntegrationJob job = jobs.lock(jobId, projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.version() == null) throw bad("Send the job's version with the change.");
        if (request.version() != job.getVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Someone changed this job since it was read. Reload it and make the change again.");
        }
        if (request.enabled() == null) throw bad("Say whether the schedule is on.");
        String user = access.requireCurrentUserId();
        String cron = request.cron() == null || request.cron().isBlank() ? null : request.cron().trim().replaceAll("\\s+", " ");
        if (request.enabled()) {
            if (!SCHEDULED_KINDS.contains(job.getKind())) {
                throw bad("A stock pull receives what it reads, so it runs by hand: reading the same list on a schedule would receive it again.");
            }
            if (cron == null) throw bad("Write the schedule to switch it on.");
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            ZoneId zone = calendar.zone(projectId);
            IntegrationSchedule schedule = IntegrationSchedule.parse(cron);
            schedule.requireUsable(zone, now);
            job.setScheduleCron(cron);
            job.setScheduleEnabled(true);
            job.setNextRunAt(schedule.next(zone, now));
            job.setRunAs(user);
        } else {
            if (cron != null) IntegrationSchedule.parse(cron);
            if (cron != null) job.setScheduleCron(cron);
            job.setScheduleEnabled(false);
            job.setNextRunAt(null);
        }
        job.setFailuresInRow(0);
        job.setPausedReason(null);
        job.setVersion(job.getVersion() + 1);
        job.setUpdatedBy(user);
        return toResponse(job);
    }

    IntegrationJob require(String projectId, String jobId) {
        return jobs.findByJobIdAndProjectIdAndDeletedYn(jobId, projectId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** Checks and sets what a request may change. */
    private void apply(IntegrationJob job, IntegrationJobRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 100) throw bad("Name the job in 1 to 100 characters.");
        String kind = request.kind() == null ? "" : request.kind().trim();
        if (!KINDS.contains(kind)) throw bad("A job's kind is pull_items or pull_stock.");
        if (job.isScheduleEnabled() && !SCHEDULED_KINDS.contains(kind)) {
            throw bad("Switch the schedule off before making this job a " + kind + ".");
        }
        if (request.profileVersionId() == null || request.profileVersionId().isBlank()
            || request.operationId() == null || request.operationId().isBlank()) {
            throw bad("Choose the connector profile version and the operation that reads.");
        }
        // The operation exists in that version, the profile is on and its target is allowed.
        ConnectorProfileOperation operation = profiles.requireOperation(job.getProjectId(), request.profileVersionId().trim(),
            request.operationId().trim());
        if (!"GET".equals(operation.method())) throw bad("A pull job reads with a GET operation; " + operation.operationId() + " is " + operation.method() + ".");
        JsonNode mapping = requireMapping(request.mapping(), "pull_items".equals(kind) ? items.fields() : stock.fields());
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 200) throw bad("A note takes at most 200 characters.");
        job.setName(name);
        job.setKind(kind);
        job.setProfileVersionId(operation.profileVersionId());
        job.setOperationId(operation.operationId());
        job.setVerifyOperationId(null);
        job.setMappingJson(write(mapping));
        job.setNote("pull_stock".equals(kind) ? note : null);
    }

    /** {"rowsAt": pointer, "row": {"object": {field: expression}}} with only the import's fields. */
    private JsonNode requireMapping(JsonNode mapping, List<String> fields) {
        if (mapping == null || !mapping.isObject() || !mapping.path("rowsAt").isTextual() || !mapping.has("row")) {
            throw bad("The mapping needs rowsAt, a JSON pointer to the list of records, and row, how one record becomes a row.");
        }
        Set<String> keys = new HashSet<>();
        mapping.fieldNames().forEachRemaining(keys::add);
        if (!Set.of("rowsAt", "row").containsAll(keys)) throw bad("The mapping takes only rowsAt and row.");
        String rowsAt = mapping.get("rowsAt").asText();
        if (!rowsAt.isEmpty() && !rowsAt.startsWith("/")) throw bad("rowsAt is a JSON pointer, such as /items, or empty for the whole response.");
        JsonNode row = mapping.get("row");
        if (!row.path("object").isObject() || row.size() != 1) throw bad("row maps one record to an object of the import's fields.");
        row.get("object").fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw bad("The import has no field " + field + "; it takes " + String.join(", ", fields) + ".");
        });
        mappings.validate(row);
        return mapping;
    }

    IntegrationJobResponse toResponse(IntegrationJob job) {
        IntegrationRunResponse last = runs.findTop20ByJobIdOrderByStartedAtDesc(job.getJobId()).stream().findFirst()
            .map(run -> IntegrationRunService.summary(run)).orElse(null);
        return new IntegrationJobResponse(job.getJobId(), job.getProjectId(), job.getName(), job.getKind(), job.getProfileVersionId(),
            job.getOperationId(), job.getVerifyOperationId(), read(job.getMappingJson()), job.getNote(), job.getScheduleCron(),
            job.isScheduleEnabled(), job.getNextRunAt(), job.getRunAs(), job.getPausedReason(), job.getFailuresInRow(), job.getVersion(), last);
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A stored mapping is not JSON", exception);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
