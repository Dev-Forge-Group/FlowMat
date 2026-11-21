package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRun;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunEventRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunStepSourceRepository;
import org.springframework.stereotype.Component;

/** Reads when a step's input and its production equipment and crew are ready. */
@Component
@RequiredArgsConstructor
class FlowRunStepAvailability {
    private final FlowRunStepRepository stepRepository;
    private final FlowRunStepSourceRepository sourceRepository;
    private final FlowRunRepository runRepository;
    private final FlowRunEventRepository eventRepository;
    private final LinkedRunWorkingTime workingTime;
    private final ObjectMapper objectMapper;

    /**
     * When what came along the connection is ready (docs/architecture/adr/ADR-010-connection-delay.md decisions 2-4): the
     * source step's end, or for a gateway the time it received what it passed on, plus the connection's delay. A delay that
     * counts working time ends once the work order's equipment and crew have worked that long
     * (docs/architecture/adr/ADR-022-working-time-delay.md decision 2), or as an all-time delay without them. Null when no
     * delay applies on the way, so the step can start at once as before.
     */
    OffsetDateTime readyAt(FlowRunGraph graph, FlowRunStep source, String connectionId) {
        OffsetDateTime carried = graph.gateway(source.getNodeId()) ? source.getScheduledAt() : null;
        return FlowRunTiming.readyAt(source.getEndedAt(), carried, graph.delay(connectionId), graph.workingDelay(connectionId),
            linkedTime(source.getFlowRunId()));
    }

    /**
     * The run's working time, from its production run's work order equipment and crew (ADR-015, ADR-016, ADR-022), asked only
     * when a delay or a wait needs it; all time for a run not linked to production.
     */
    FlowRunTiming.WorkingTime linkedTime(String runId) {
        return new FlowRunTiming.WorkingTime() {
            @Override
            public OffsetDateTime afterWorking(OffsetDateTime from, Duration working) {
                String productionRunId = productionRunId(runId);
                return productionRunId == null ? null : workingTime.afterWorking(productionRunId, from, working)
                    .map(end -> end.at().withOffsetSameInstant(from.getOffset()))
                    .orElse(null);
            }

            @Override
            public LinkedRunWorkingTime.WorkingStart workingStart(OffsetDateTime readyAt) {
                String productionRunId = productionRunId(runId);
                return productionRunId == null ? null : workingTime.workingStart(productionRunId, readyAt).orElse(null);
            }
        };
    }

    private String productionRunId(String runId) {
        return runRepository.findById(runId).map(FlowRun::getProductionRunId).orElse(null);
    }

    /** A Join's arrivals' latest ready time once a delay applies to any of them (ADR-010 decision 4); else null. */
    OffsetDateTime joinReadyAt(FlowRunGraph graph, FlowRunStep join) {
        List<FlowRunTiming.Arrival> arrivals = new ArrayList<>();
        for (FlowRunStepSource source : sourceRepository.findAllByStepIdOrderByArrivalNoAsc(join.getStepId())) {
            FlowRunStep from = stepRepository.findById(source.getSourceStepId()).orElse(null);
            if (from != null) {
                arrivals.add(new FlowRunTiming.Arrival(readyAt(graph, from, source.getConnectionId()), from.getEndedAt()));
            }
        }
        return FlowRunTiming.joinReadyAt(arrivals);
    }

    /** For a production-linked run, the next time its work order's equipment and crew work, when the delay ends outside it. */
    LinkedRunWorkingTime.WorkingStart workingStart(String runId, OffsetDateTime readyAt) {
        return linkedTime(runId).workingStart(readyAt);
    }

    /**
     * Whether a production-linked step held for the work order equipment's next working time may start now, before its
     * {@code scheduledAt} (docs/architecture/adr/ADR-015-shift-aware-delay.md decision 6, ADR-016 decision 6): its delay is
     * over and the equipment and crew work now, as when overtime is given as that date's own shifts.
     */
    boolean mayStartEarly(FlowRun run, FlowRunStep step, OffsetDateTime now) {
        if (run.getProductionRunId() == null) {
            return false;
        }
        OffsetDateTime readyAt = eventRepository.findFirstByStepIdAndEventTypeOrderByOccurredAtDescEventIdDesc(step.getStepId(),
                "step_scheduled")
            .map(event -> readJson(event.getPayloadJson()).path("readyAt").asText(null))
            .map(OffsetDateTime::parse)
            .orElse(null);
        return readyAt != null && !readyAt.isAfter(now) && workingTime.workingStart(run.getProductionRunId(), now).isEmpty();
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
