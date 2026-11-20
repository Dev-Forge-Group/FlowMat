package org.myweb.flowmat.domain.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemImportCommand;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileQuery;
import org.myweb.flowmat.domain.integration.api.dto.response.IntegrationRunResponse;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationJob;
import org.myweb.flowmat.domain.integration.domain.entity.IntegrationRun;
import org.myweb.flowmat.domain.integration.repository.IntegrationJobRepository;
import org.myweb.flowmat.domain.integration.repository.IntegrationRunRepository;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockImportCommand;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.importing.ImportOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Running a job (docs/architecture/adr/ADR-038-integration-jobs.md). A run is taken in its own transaction, the source
 * is read with no transaction open, the records go through the import's own rules (rows with problems left out, the rest
 * saved together), and what happened is written in a new transaction. A preview reads and checks the same way and saves
 * nothing, not even a run.
 */
@Service
public class IntegrationRunService {

    private static final TypeReference<List<IntegrationRunResponse.Item>> ITEMS = new TypeReference<>() { };

    private final IntegrationJobService jobService;
    private final IntegrationJobRepository jobs;
    private final IntegrationRunRepository runs;
    private final ProjectAccess access;
    private final ConnectorProfileQuery profiles;
    private final ConnectorHttpGateway gateway;
    private final ConnectorMappingQuery mappings;
    private final ItemImportCommand items;
    private final StockImportCommand stock;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public IntegrationRunService(IntegrationJobService jobService, IntegrationJobRepository jobs, IntegrationRunRepository runs,
                                 ProjectAccess access, ConnectorProfileQuery profiles, ConnectorHttpGateway gateway,
                                 ConnectorMappingQuery mappings, ItemImportCommand items, StockImportCommand stock, IdGenerator ids,
                                 ObjectMapper json, PlatformTransactionManager transactions) {
        this.jobService = jobService;
        this.jobs = jobs;
        this.runs = runs;
        this.access = access;
        this.profiles = profiles;
        this.gateway = gateway;
        this.mappings = mappings;
        this.items = items;
        this.stock = stock;
        this.ids = ids;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a run would do now: the source is read and its records checked; nothing is saved. */
    public IntegrationRunResponse preview(String projectId, String jobId) {
        access.requireProjectOwnerAccess(projectId);
        IntegrationJob job = jobService.require(projectId, jobId);
        Result result = pull(job, false);
        return new IntegrationRunResponse(null, jobId, "preview", null, result.status(), access.requireCurrentUserId(), null, null,
            result.total(), result.applied(), result.leftOut(), result.message(), result.items());
    }

    /** Runs the job now, as the current user, and keeps what happened. */
    public IntegrationRunResponse runNow(String projectId, String jobId) {
        access.requireProjectOwnerAccess(projectId);
        String user = access.requireCurrentUserId();
        String runId = transaction.execute(status -> claim(projectId, jobId, "manual", null, user));
        return finish(projectId, runId);
    }

    /** Takes a run of the job: one at a time per job. */
    String claim(String projectId, String jobId, String trigger, OffsetDateTime scheduledFor, String user) {
        IntegrationJob job = jobs.lock(jobId, projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (runs.existsByJobIdAndStatus(job.getJobId(), "running")) {
            throw new BusinessException(ErrorCode.CONFLICT, "This job is running; wait for it to finish.");
        }
        IntegrationRun run = new IntegrationRun();
        run.setRunId(ids.generate());
        run.setJobId(job.getJobId());
        run.setProjectId(projectId);
        run.setRunTrigger(trigger);
        run.setScheduledFor(scheduledFor);
        run.setStatus("running");
        run.setRunBy(user);
        run.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        runs.save(run);
        return run.getRunId();
    }

    /** Does the taken run's work, outside any transaction, and writes the result. */
    IntegrationRunResponse finish(String projectId, String runId) {
        IntegrationRun taken = runs.findById(runId).orElseThrow();
        Result result;
        try {
            IntegrationJob job = jobService.require(projectId, taken.getJobId());
            result = pull(job, true);
        } catch (RuntimeException exception) {
            result = Result.failed(exception instanceof BusinessException business ? business.getMessage()
                : "The run stopped on an unexpected error.");
        }
        Result outcome = result;
        return transaction.execute(status -> {
            IntegrationRun run = runs.findById(runId).orElseThrow();
            run.setStatus(outcome.status());
            run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            run.setRowsTotal(outcome.total());
            run.setRowsApplied(outcome.applied());
            run.setRowsLeftOut(outcome.leftOut());
            run.setMessage(clip(outcome.message()));
            run.setItemsJson(write(outcome.items()));
            return toResponse(run, json);
        });
    }

    /**
     * Reads the source and imports its records. With {@code apply} false it only checks (the import's dry run): a
     * preview. With it true, the rows with problems are left out and the rest saved together.
     */
    private Result pull(IntegrationJob job, boolean apply) {
        ConnectorProfileOperation operation = profiles.requireOperation(job.getProjectId(), job.getProfileVersionId(), job.getOperationId());
        ConnectorHttpOutcome answer = gateway.execute(operation, null);
        if (!"SUCCEEDED".equals(answer.status())) {
            return Result.failed(answer.httpStatus() != null
                ? "The source answered HTTP " + answer.httpStatus() + "."
                : "The source could not be read (" + answer.errorCode() + ").");
        }
        boolean isStock = "pull_stock".equals(job.getKind());
        PullRows.Result records;
        try {
            records = PullRows.read(answer.response(), readMapping(job), mappings, "itemCode");
        } catch (PullRows.PullProblem problem) {
            return Result.failed(problem.getMessage());
        }
        int total = records.rows().size();
        if (total == 0) return new Result("succeeded", 0, 0, 0, "The source sent no records.", List.of());

        // First the import's check, with the records the mapping could not turn left out.
        TreeSet<Integer> leftOut = new TreeSet<>(records.problems().keySet());
        List<IntegrationRunResponse.Item> found = new ArrayList<>();
        ImportOutcome checked = leftOut.size() == total ? null : importRows(job, isStock, records.rows(), true, List.copyOf(leftOut));
        java.util.Map<Integer, String> reasons = new java.util.HashMap<>(records.problems());
        if (checked != null) {
            for (ImportOutcome.Row row : checked.rows()) {
                if ("error".equals(row.action())) {
                    leftOut.add(row.row());
                    reasons.put(row.row(), row.message());
                }
            }
        }
        ImportOutcome done = null;
        if (leftOut.size() < total) {
            done = importRows(job, isStock, records.rows(), !apply, List.copyOf(leftOut));
        }
        for (int number = 1; number <= total; number++) {
            String key = records.keys().get(number - 1);
            if (leftOut.contains(number)) {
                found.add(new IntegrationRunResponse.Item(number, key, "left_out", reasons.get(number)));
            } else {
                ImportOutcome.Row row = done.rows().get(number - 1);
                found.add(new IntegrationRunResponse.Item(number, row.key() != null ? row.key() : key, row.action(), row.message()));
            }
        }
        int kept = total - leftOut.size();
        if (kept == 0) return new Result("failed", total, 0, total, "Every record has a problem; nothing was taken.", found);
        String status = leftOut.isEmpty() ? "succeeded" : "partial";
        String message = leftOut.isEmpty() ? null : leftOut.size() + " of " + total + " records were left out.";
        return new Result(status, total, kept, leftOut.size(), message, found);
    }

    private ImportOutcome importRows(IntegrationJob job, boolean isStock, List<Map<String, String>> rows, boolean dryRun, List<Integer> skip) {
        return isStock
            ? stock.importStock(job.getProjectId(), rows, job.getNote(), dryRun, skip)
            : items.importItems(job.getProjectId(), rows, dryRun, skip);
    }

    private JsonNode readMapping(IntegrationJob job) {
        try {
            return json.readTree(job.getMappingJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String write(List<IntegrationRunResponse.Item> found) {
        try {
            return json.writeValueAsString(found);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String clip(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    static IntegrationRunResponse toResponse(IntegrationRun run, ObjectMapper json) {
        List<IntegrationRunResponse.Item> found;
        try {
            found = json.readValue(run.getItemsJson(), ITEMS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        return new IntegrationRunResponse(run.getRunId(), run.getJobId(), run.getRunTrigger(), run.getScheduledFor(), run.getStatus(),
            run.getRunBy(), run.getStartedAt(), run.getFinishedAt(), run.getRowsTotal(), run.getRowsApplied(), run.getRowsLeftOut(),
            run.getMessage(), found);
    }

    /** A run without its items, for a job's list. */
    static IntegrationRunResponse summary(IntegrationRun run) {
        return new IntegrationRunResponse(run.getRunId(), run.getJobId(), run.getRunTrigger(), run.getScheduledFor(), run.getStatus(),
            run.getRunBy(), run.getStartedAt(), run.getFinishedAt(), run.getRowsTotal(), run.getRowsApplied(), run.getRowsLeftOut(),
            run.getMessage(), List.of());
    }

    record Result(String status, Integer total, Integer applied, Integer leftOut, String message, List<IntegrationRunResponse.Item> items) {
        static Result failed(String message) {
            return new Result("failed", null, null, null, message, List.of());
        }
    }
}
