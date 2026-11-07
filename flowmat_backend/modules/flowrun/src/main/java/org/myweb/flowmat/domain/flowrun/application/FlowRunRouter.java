package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepAttempt;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepAttemptRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepSourceRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Component;

/**
 * Creates the steps a finished step's output routes to and passes gateways as the system. An Exclusive gateway
 * (docs/architecture/adr/ADR-006-operation-types.md decisions 2-4) completes at once with its input as its output and goes
 * on by priority, else by its default connection; finding neither fails its step with NO_ROUTE. A Join
 * (docs/architecture/adr/ADR-007-join-gateway.md decisions 2-7) gathers every branch that arrives in one waiting step and is
 * passed once no planned or running step can bring anything more. A Split (docs/architecture/adr/ADR-008-quantity-split.md)
 * divides what arrives between its connections and is passed at once. A connection's loss rate
 * (docs/architecture/adr/ADR-009-connection-loss-rate.md) takes its part on the way, and its delay
 * (docs/architecture/adr/ADR-010-connection-delay.md) schedules the next step; in a production-linked run, no earlier than the
 * next time the work order's equipment and crew work (docs/architecture/adr/ADR-015-shift-aware-delay.md,
 * docs/architecture/adr/ADR-016-crew-aware-delay.md). A transfer rule that refuses a gateway's output rejects
 * the report that led there, as for any step (GR-04); when no report did, such as a skip, the gateway fails and the caller
 * fails the run (ADR-007 decision 9).
 */
@Component
@RequiredArgsConstructor
class FlowRunRouter {
    static final String NO_ROUTE = "NO_ROUTE";
    static final String JOIN_TRANSFER_REJECTED = "JOIN_TRANSFER_REJECTED";
    static final String SPLIT_REJECTED = "SPLIT_REJECTED";

    /** Why routing ended the run: an Exclusive gateway without a connection, or a gateway whose output could not go on. */
    record Failure(String code, String message) {}

    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepAttemptRepository attemptRepository;
    private final FlowRunStepSourceRepository sourceRepository;
    private final FlowRunEventRecorder eventRecorder;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final FlowRunStepAvailability availability;

    /**
     * Routes a finished step's decisions: a step for each connection that goes on, an arrival for one into a Join, and the
     * Exclusive gateways on the way passed at once. Null when everything went on. {@code actorId} null is the system;
     * {@code rejectTransfer} as for {@link #settle}.
     */
    Failure follow(String runId, FlowRunGraph graph, String sourceStepId, List<FlowRunGraph.Decision> decisions,
        JsonNode output, String actorId, boolean rejectTransfer) {
        for (FlowRunGraph.Decision decision : decisions) {
            if (decision.loss() != null) {
                transferLoss(runId, sourceStepId, decision, output, actorId);
            }
            if (!decision.willRoute()) {
                eventRecorder.record(runId, sourceStepId, "connection_filtered", objectMapper.createObjectNode()
                    .put("connectionId", decision.connectionId())
                    .put("targetNodeId", decision.targetNodeId())
                    .put("reason", decision.reason() == null ? "condition_false" : decision.reason()), actorId);
                continue;
            }
            if (decision.reason() != null) {
                ObjectNode routed = objectMapper.createObjectNode()
                    .put("connectionId", decision.connectionId())
                    .put("targetNodeId", decision.targetNodeId())
                    .put("reason", decision.reason());
                if ("split".equals(decision.reason())) {
                    // The Split's share; what arrives after a loss is in transfer_loss (ADR-009 decision 6).
                    routed.put("quantity", decision.loss() != null ? decision.loss().sent() : decision.quantity());
                }
                eventRecorder.record(runId, sourceStepId, "gateway_routed", routed, actorId);
            }
            if (graph.join(decision.targetNodeId())) {
                arrive(runId, graph, sourceStepId, decision, actorId);
                continue;
            }
            // A Split's share goes on with its own quantity (ADR-008 decision 7).
            JsonNode input = decision.quantity() == null ? output : FlowRunGraph.withQuantity(output, decision.quantity());
            FlowRunStep step = createStep(runId, graph, sourceStepId, decision, input, actorId);
            if (graph.exclusive(step.getNodeId()) || graph.split(step.getNodeId())) {
                Failure failure = pass(runId, graph, step, rejectTransfer);
                if (failure != null) {
                    return failure;
                }
            }
        }
        return null;
    }

    /**
     * Passes every waiting Join that nothing more can reach (ADR-007 decisions 2-3): no planned or running step of the run,
     * another waiting Join included, can route along a connection into it. Called after each change of a graph run's steps,
     * under the run lock, so a Join is passed once however its branches end. {@code rejectTransfer}: a report led here, so
     * a transfer rule that refuses a gateway's output rejects it (409, nothing kept); otherwise that gateway fails and so
     * should the run (decision 9). Null when nothing failed.
     */
    Failure settle(String runId, FlowRunGraph graph, boolean rejectTransfer) {
        while (true) {
            List<FlowRunStep> steps = stepRepository.findAllByFlowRunIdOrderBySequenceNoAsc(runId);
            FlowRunStep ready = steps.stream()
                .filter(step -> graph.join(step.getNodeId()) && "planned".equals(step.getStatus()))
                .filter(step -> graph.stillComing(step.getNodeId(), liveNodes(steps, step)) == 0)
                .findFirst()
                .orElse(null);
            if (ready == null) {
                return null;
            }
            Failure failure = passJoin(runId, graph, ready, rejectTransfer);
            if (failure != null) {
                return failure;
            }
        }
    }

    /** The nodes of the run's planned and running steps but {@code except}: the steps that can still route. */
    static Set<String> liveNodes(List<FlowRunStep> steps, FlowRunStep except) {
        return steps.stream()
            .filter(step -> !step.getStepId().equals(except.getStepId()))
            .filter(step -> "planned".equals(step.getStatus()) || "running".equals(step.getStatus()))
            .map(FlowRunStep::getNodeId)
            .collect(Collectors.toSet());
    }

    /** One branch reaching a Join: the run's step for it is made on the first arrival and waits (ADR-007 decisions 5-6). */
    private void arrive(String runId, FlowRunGraph graph, String sourceStepId, FlowRunGraph.Decision decision, String actorId) {
        FlowRunStep join = stepRepository.findFirstByFlowRunIdAndNodeIdOrderBySequenceNoAsc(runId, decision.targetNodeId())
            .orElse(null);
        boolean first = join == null;
        if (first) {
            join = newStep(runId, sourceStepId, decision, objectMapper.createObjectNode());
        } else if (!"planned".equals(join.getStatus())) {
            // Decision 2: a Join is passed only once nothing that could still arrive is left.
            throw new IllegalStateException("Join step " + join.getStepId() + " has already been passed.");
        }
        int arrivalNo = (int) sourceRepository.countByStepId(join.getStepId()) + 1;
        FlowRunStepSource source = new FlowRunStepSource();
        source.setStepSourceId(idGenerator.generate());
        source.setFlowRunId(runId);
        source.setStepId(join.getStepId());
        source.setArrivalNo(arrivalNo);
        source.setSourceStepId(sourceStepId);
        source.setConnectionId(decision.connectionId());
        source.setArrivedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sourceRepository.saveAndFlush(source);
        JsonNode merged = merged(join.getStepId(), graph);
        join.setInputSnapshot(writeJson(merged));
        stepRepository.saveAndFlush(join);
        if (first) {
            eventRecorder.record(runId, join.getStepId(), "step_created", merged, actorId);
        }
        eventRecorder.record(runId, join.getStepId(), "join_arrived", objectMapper.createObjectNode()
            .put("connectionId", decision.connectionId())
            .put("sourceStepId", sourceStepId)
            .put("arrivalNo", arrivalNo), actorId);
    }

    private Failure passJoin(String runId, FlowRunGraph graph, FlowRunStep join, boolean rejectTransfer) {
        JsonNode input = merged(join.getStepId(), graph);
        join.setInputSnapshot(writeJson(input));
        List<FlowRunGraph.Decision> decisions;
        try {
            decisions = graph.preview(join.getNodeId(), input);
        } catch (BusinessException refused) {
            if (rejectTransfer || refused.getErrorCode() != ErrorCode.CONFLICT) {
                throw refused;
            }
            return failed(runId, join, open(runId, join), JOIN_TRANSFER_REJECTED,
                "Join " + join.getNodeId() + " could not pass its joined output on: " + refused.getMessage(), List.of());
        }
        // The Join received what came last; that time goes on (ADR-010 decision 4).
        OffsetDateTime receivedAt = availability.joinReadyAt(graph, join);
        if (receivedAt != null) {
            join.setScheduledAt(receivedAt);
            eventRecorder.record(runId, join.getStepId(), "step_scheduled", objectMapper.createObjectNode()
                .put("scheduledAt", receivedAt.toString())
                .put("reason", "connection_delay"), null);
        }
        completed(runId, join, open(runId, join), input);
        return follow(runId, graph, join.getStepId(), decisions, input, null, rejectTransfer);
    }

    private Failure pass(String runId, FlowRunGraph graph, FlowRunStep step, boolean rejectTransfer) {
        JsonNode input = readJson(step.getInputSnapshot());
        boolean split = graph.split(step.getNodeId());
        List<FlowRunGraph.Decision> decisions;
        try {
            decisions = graph.preview(step.getNodeId(), input);
        } catch (BusinessException refused) {
            // Only a Join's output reaches a gateway without a report to reject (ADR-007 decision 9, ADR-008 decision 6).
            if (rejectTransfer || refused.getErrorCode() != ErrorCode.CONFLICT) {
                throw refused;
            }
            return split
                ? failed(runId, step, open(runId, step), SPLIT_REJECTED,
                    "Split gateway " + step.getNodeId() + " could not divide the joined output: " + refused.getMessage(), List.of())
                : failed(runId, step, open(runId, step), JOIN_TRANSFER_REJECTED,
                    "Exclusive gateway " + step.getNodeId() + " could not pass the joined output on: " + refused.getMessage(),
                    List.of());
        }
        FlowRunStepAttempt attempt = open(runId, step);
        // A Split whose shares are all 0 sends nothing on (ADR-008 decision 5); only an Exclusive must find a connection,
        // and a chosen one that lost everything on the way was still found (ADR-009 decision 7).
        if (!split && decisions.stream().noneMatch(decision -> decision.willRoute() || "loss_empty".equals(decision.reason()))) {
            return failed(runId, step, attempt, NO_ROUTE, "Exclusive gateway " + step.getNodeId()
                + " found no connection whose condition holds and has no default connection.", decisions);
        }
        completed(runId, step, attempt, input);
        return follow(runId, graph, step.getStepId(), decisions, input, null, rejectTransfer);
    }

    /** A gateway step's one attempt, started and ended at once by the system (ADR-006 decision 4, ADR-007 decision 6). */
    private FlowRunStepAttempt open(String runId, FlowRunStep step) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        FlowRunStepAttempt attempt = new FlowRunStepAttempt();
        attempt.setAttemptId(idGenerator.generate());
        attempt.setStepId(step.getStepId());
        attempt.setAttemptNo(1);
        attempt.setStartedAt(now);
        attempt.setEndedAt(now);
        step.setStartedAt(now);
        step.setEndedAt(now);
        eventRecorder.record(runId, step.getStepId(), "step_started", objectMapper.createObjectNode().put("attemptNo", 1), null);
        return attempt;
    }

    private void completed(String runId, FlowRunStep step, FlowRunStepAttempt attempt, JsonNode output) {
        attempt.setStatus("completed");
        step.setStatus("completed");
        step.setOutputSnapshot(writeJson(output));
        attemptRepository.save(attempt);
        stepRepository.saveAndFlush(step);
        eventRecorder.record(runId, step.getStepId(), "step_completed", output, null);
    }

    private Failure failed(String runId, FlowRunStep step, FlowRunStepAttempt attempt, String code, String message,
        List<FlowRunGraph.Decision> filtered) {
        attempt.setStatus("failed");
        attempt.setErrorCode(code);
        attempt.setErrorMessage(message);
        step.setStatus("failed");
        step.setErrorCode(code);
        step.setErrorMessage(message);
        attemptRepository.save(attempt);
        stepRepository.saveAndFlush(step);
        for (FlowRunGraph.Decision decision : filtered) {
            eventRecorder.record(runId, step.getStepId(), "connection_filtered", objectMapper.createObjectNode()
                .put("connectionId", decision.connectionId())
                .put("targetNodeId", decision.targetNodeId())
                .put("reason", decision.reason()), null);
        }
        eventRecorder.record(runId, step.getStepId(), "step_failed", objectMapper.createObjectNode()
            .put("errorCode", code).put("errorMessage", message), null);
        return new Failure(code, message);
    }

    /**
     * A Join's input from its arrivals in order (ADR-007 decision 7): each arrival's quantity, unit, item and attrs as its
     * source step gave them; above them the summed quantity, with the unit, when every arrival has a quantity in the same
     * unit, and the item when every arrival names the same one. Attrs are not merged.
     */
    private JsonNode merged(String joinStepId, FlowRunGraph graph) {
        FlowRunJoinOutput merged = new FlowRunJoinOutput(objectMapper);
        for (FlowRunStepSource source : sourceRepository.findAllByStepIdOrderByArrivalNoAsc(joinStepId)) {
            FlowRunStep from = stepRepository.findById(source.getSourceStepId()).orElse(null);
            JsonNode output = from == null ? objectMapper.createObjectNode() : sent(graph, from, source.getConnectionId());
            merged.add(source.getConnectionId(), source.getSourceStepId(), output);
        }
        return merged.result();
    }

    /** The connection's loss on the way (docs/architecture/adr/ADR-009-connection-loss-rate.md decision 6). */
    private void transferLoss(String runId, String sourceStepId, FlowRunGraph.Decision decision, JsonNode output,
        String actorId) {
        FlowRunGraph.Loss loss = decision.loss();
        ObjectNode payload = objectMapper.createObjectNode()
            .put("connectionId", decision.connectionId())
            .put("targetNodeId", decision.targetNodeId())
            .put("sent", loss.sent())
            .put("lossRate", loss.rate())
            .put("lost", loss.lost())
            .put("arrived", loss.arrived());
        if (output.path("unit").isTextual()) {
            payload.put("unit", output.path("unit").asText());
        }
        eventRecorder.record(runId, sourceStepId, "transfer_loss", payload, actorId);
    }

    /**
     * What arrived along a connection: the step's output, or its Split share (ADR-008 decision 7), less the connection's
     * loss (ADR-009 decision 6).
     */
    private JsonNode sent(FlowRunGraph graph, FlowRunStep from, String connectionId) {
        JsonNode output = readJson(from.getOutputSnapshot());
        if (!graph.quantityChanges(from.getNodeId(), connectionId)) {
            return output;
        }
        return graph.preview(from.getNodeId(), output).stream()
            .filter(decision -> connectionId.equals(decision.connectionId()) && decision.quantity() != null)
            .findFirst()
            .map(decision -> FlowRunGraph.withQuantity(output, decision.quantity()))
            .orElse(output);
    }

    /**
     * When what came along the connection is ready (docs/architecture/adr/ADR-010-connection-delay.md decisions 2-4): the
     * source step's end, or for a gateway the time it received what it passed on, plus the connection's delay. A delay that
     * counts working time ends once the work order's equipment and crew have worked that long
     * (docs/architecture/adr/ADR-022-working-time-delay.md decision 2), or as an all-time delay without them. Null when no
     * delay applies on the way, so the step can start at once as before.
     */
    OffsetDateTime readyAt(FlowRunGraph graph, FlowRunStep source, String connectionId) {
        return availability.readyAt(graph, source, connectionId);
    }

    private FlowRunStep createStep(String runId, FlowRunGraph graph, String sourceStepId, FlowRunGraph.Decision decision,
        JsonNode input, String actorId) {
        FlowRunStep step = newStep(runId, sourceStepId, decision, input);
        eventRecorder.record(runId, step.getStepId(), "step_created", input, actorId);
        FlowRunStep source = stepRepository.findById(sourceStepId).orElse(null);
        OffsetDateTime readyAt = source == null ? null : readyAt(graph, source, decision.connectionId());
        if (readyAt != null) {
            // Not before its input is ready (ADR-010 decisions 1, 6); a gateway keeps it as the time it received it.
            LinkedRunWorkingTime.WorkingStart held = graph.gateway(step.getNodeId()) ? null : availability.workingStart(runId, readyAt);
            OffsetDateTime scheduledAt = held == null ? readyAt
                : held.at().withOffsetSameInstant(readyAt.getOffset()).truncatedTo(ChronoUnit.MICROS);
            step.setScheduledAt(scheduledAt);
            stepRepository.saveAndFlush(step);
            ObjectNode payload = objectMapper.createObjectNode()
                .put("scheduledAt", scheduledAt.toString())
                .put("reason", "connection_delay")
                .put("connectionId", decision.connectionId())
                .put("delaySeconds", graph.delaySeconds(decision.connectionId()));
            if (graph.workingDelay(decision.connectionId())) {
                payload.put("delayBasis", "working");
            }
            if (held != null) {
                // The delay ended outside the time the work order's equipment and crew work (ADR-015 decision 5, ADR-016 decision 5).
                payload.put("readyAt", readyAt.toString());
                if (held.equipmentId() != null) {
                    payload.put("equipmentId", held.equipmentId());
                }
                if (!held.crew().isEmpty()) {
                    payload.set("crew", objectMapper.valueToTree(held.crew()));
                }
            }
            eventRecorder.record(runId, step.getStepId(), "step_scheduled", payload, actorId);
        }
        return step;
    }

    /**
     * Whether a production-linked step held for the work order equipment's next working time may start now, before its
     * {@code scheduledAt} (docs/architecture/adr/ADR-015-shift-aware-delay.md decision 6, ADR-016 decision 6): its delay is
     * over and the equipment and crew work now, as when overtime is given as that date's own shifts.
     */
    boolean mayStartEarly(FlowRun run, FlowRunStep step, OffsetDateTime now) {
        return availability.mayStartEarly(run, step, now);
    }

    private FlowRunStep newStep(String runId, String sourceStepId, FlowRunGraph.Decision decision, JsonNode input) {
        long nextSequence = stepRepository.countByFlowRunId(runId) + 1;
        if (nextSequence > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.CONFLICT, "Flow run has too many steps.");
        }
        FlowRunStep step = new FlowRunStep();
        step.setStepId(idGenerator.generate());
        step.setFlowRunId(runId);
        step.setNodeId(decision.targetNodeId());
        step.setSourceConnectionId(decision.connectionId());
        step.setSourceStepId(sourceStepId);
        step.setStatus("planned");
        step.setSequenceNo((int) nextSequence);
        step.setInputSnapshot(writeJson(input));
        stepRepository.saveAndFlush(step);
        return step;
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Flow run step snapshot could not be serialized.", exception);
        }
    }

    private JsonNode readJson(String value) {
        try {
            // Decimals stay exact on the way through, as conditions and Join totals compare them.
            return value == null ? objectMapper.createObjectNode()
                : objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Flow run step snapshot could not be read.", exception);
        }
    }
}
