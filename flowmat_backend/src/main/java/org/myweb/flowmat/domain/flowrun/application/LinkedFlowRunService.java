package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowRunCommand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flow runs linked to another domain's run, written in the caller's transaction. A run started now goes through its
 * revision's process steps (docs/architecture/adr/ADR-011-production-steps.md); one whose revision cannot run as a graph
 * keeps none, as before.
 */
@Service
@RequiredArgsConstructor
public class LinkedFlowRunService implements FlowRunCommand {

    private final FlowRunRepository flowRunRepository;
    private final FlowRunEventRecorder eventRecorder;
    private final EntityManager entityManager;
    private final IdGenerator idGenerator;
    private final FlowRunStepRepository stepRepository;
    private final FlowRunService runService;
    private final WorkflowProductionQuery workflowQuery;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void startLinkedRun(LinkedRun run) {
        create(run, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishLinkedRun(LinkedRun run, ObjectNode output, String finishedBy) {
        Optional<FlowRun> existing = flowRunRepository.findLockedByProductionRunId(run.productionRunId());
        if (existing.isEmpty() && !FlowRunService.RUN_TYPES.contains(run.runType())) {
            // Old production runs could use arbitrary types before the shared run contract existed.
            return;
        }
        FlowRun flowRun = existing.orElseGet(() -> create(run, false));
        if ("failed".equals(flowRun.getStatus())) {
            // A step that failed under a stop policy ended the process steps (ADR-011); the production run still finishes,
            // and its flow run keeps that failure.
            return;
        }
        if (!"running".equals(flowRun.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Linked flow run has already ended.");
        }
        closeSteps(flowRun, finishedBy);
        flowRun.setOutputPayload(output.toString());
        flowRun.setStatus("finished");
        flowRun.setEndedAt(OffsetDateTime.now(ZoneOffset.UTC));
        flowRunRepository.save(flowRun);
        eventRecorder.record(flowRun.getFlowRunId(), null, "run_finished", output, finishedBy);
    }

    /** {@code withSteps}: as the run starts, go through the revision's process steps when it can run as a graph (ADR-011). */
    private FlowRun create(LinkedRun run, boolean withSteps) {
        if (!FlowRunService.RUN_TYPES.contains(run.runType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unknown production run type.");
        }
        FlowRunGraph graph = null;
        String unavailable = null;
        if (withSteps) {
            try {
                graph = graph(run);
            } catch (BusinessException unrunnable) {
                unavailable = unrunnable.getMessage();
            }
        }
        // The linked run is persisted first; flush it before inserting the row with its foreign key.
        entityManager.flush();
        FlowRun flowRun = new FlowRun();
        flowRun.setFlowRunId(idGenerator.generate());
        flowRun.setProjectId(run.projectId());
        flowRun.setWorkflowId(run.workflowId());
        flowRun.setWorkflowRevisionId(run.workflowRevisionId());
        flowRun.setProductionRunId(run.productionRunId());
        flowRun.setRunType(run.runType());
        flowRun.setExecutionMode(graph != null ? "graph" : "manual");
        flowRun.setStatus("running");
        flowRun.setInputPayload(run.input().toString());
        flowRun.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        flowRun.setRequestedBy(run.requestedBy());
        flowRunRepository.saveAndFlush(flowRun);
        eventRecorder.record(flowRun.getFlowRunId(), null, "run_started", run.input(), run.requestedBy());
        if (graph != null) {
            runService.createRootSteps(flowRun, graph, run.input(), run.requestedBy());
        } else if (unavailable != null) {
            eventRecorder.record(flowRun.getFlowRunId(), null, "steps_unavailable",
                objectMapper.createObjectNode().put("reason", unavailable), run.requestedBy());
        }
        return flowRun;
    }

    /** The run's revision as a graph; null without a revision. 409 when it cannot run as one (no processes, cycles). */
    private FlowRunGraph graph(LinkedRun run) {
        if (run.workflowRevisionId() == null) {
            return null;
        }
        return workflowQuery.findRevision(run.workflowRevisionId(), run.workflowId())
            .map(revision -> FlowRunGraph.from(readJson(revision.snapshotJson())))
            .orElse(null);
    }

    /**
     * Before the production run finishes (docs/architecture/adr/ADR-011-production-steps.md decision 8): a running step
     * refuses it, and the steps that have not started are cancelled.
     */
    private void closeSteps(FlowRun flowRun, String finishedBy) {
        if (!"graph".equals(flowRun.getExecutionMode())) {
            return;
        }
        List<FlowRunStep> steps = stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(flowRun.getFlowRunId());
        for (FlowRunStep step : steps) {
            if ("running".equals(step.getStatus())) {
                String name = nodeName(flowRun, step.getNodeId());
                throw new BusinessException(ErrorCode.CONFLICT, "Step #" + step.getSequenceNo() + (name == null ? "" : " " + name)
                    + " is still running; complete or fail it before finishing the run.");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (FlowRunStep step : steps) {
            if (!"planned".equals(step.getStatus())) {
                continue;
            }
            step.setStatus("cancelled");
            step.setEndedAt(now);
            step.setErrorCode("RUN_FINISHED");
            step.setErrorMessage("The run finished before this step started.");
            stepRepository.save(step);
            eventRecorder.record(flowRun.getFlowRunId(), step.getStepId(), "step_cancelled",
                objectMapper.createObjectNode().put("reason", "run_finished"), finishedBy);
        }
    }

    private String nodeName(FlowRun flowRun, String nodeId) {
        JsonNode processes = workflowQuery.findRevision(flowRun.getWorkflowRevisionId(), flowRun.getWorkflowId())
            .map(revision -> readJson(revision.snapshotJson()).path("processes"))
            .orElse(null);
        if (processes == null) {
            return null;
        }
        for (JsonNode process : processes) {
            if (nodeId.equals(process.path("processId").asText())) {
                return process.path("processName").asText(null);
            }
        }
        return null;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow revision snapshot could not be read.", exception);
        }
    }
}
