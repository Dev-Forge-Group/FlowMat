package org.myweb.flowmat.domain.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWorkingTimeQuery;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretches;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.springframework.stereotype.Component;

/**
 * When each step of a plan runs (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 5): a step is ready
 * when every step before it has ended and the connection's delay has passed; one that takes equipment or named people waits
 * for the time they all work (equipment: shifts less holidays and downtime; people: shifts less days off and holidays, as runs
 * see them) and for the step before it on the same equipment or person. Its minutes are working minutes. A step without
 * either runs as soon as it is ready; people without a calendar do not hold a step back.
 */
@Component
@RequiredArgsConstructor
class PlanSchedule {

    /** How far ahead working time is looked for at first; then a year at most. */
    private static final Duration FIRST_WINDOW = Duration.ofDays(31);

    private final EquipmentWorkingTimeQuery equipmentTime;
    private final WorkerCalendarQuery workerTime;

    record Placed(Instant start, Instant end) {
    }

    /** Each node with minutes, or between steps, placed from {@code from}; nodes in topological order. */
    Map<String, Placed> place(String projectId, FlowDemand.Graph graph, JsonNode snapshot, List<String> order,
        Map<String, BigDecimal> minutes, Instant from) {
        Map<String, BigDecimal> delays = delays(snapshot);
        Map<String, String> equipment = new HashMap<>();
        Map<String, List<String>> people = new HashMap<>();
        for (FlowDemand.Node node : graph.nodes()) {
            node.execution().stream().filter(one -> "equipment".equals(one.kind()) && one.ref() != null).findFirst()
                .ifPresent(one -> equipment.put(node.id(), one.ref()));
            List<String> crew = node.execution().stream().filter(one -> "crew".equals(one.kind()) && one.ref() != null)
                .map(FlowDemand.ExecutionRequirement::ref).distinct().toList();
            if (!crew.isEmpty()) people.put(node.id(), crew);
        }
        Map<String, Placed> placed = new HashMap<>();
        Map<String, Instant> busyUntil = new HashMap<>();
        for (String id : order) {
            Instant ready = from;
            for (FlowDemand.Connection connection : graph.connections()) {
                if (!connection.to().equals(id) || !placed.containsKey(connection.from())) continue;
                BigDecimal delay = delays.getOrDefault(connection.id(), BigDecimal.ZERO);
                Instant after = placed.get(connection.from()).end().plusNanos(delay.movePointRight(9).longValue());
                if (after.isAfter(ready)) ready = after;
            }
            BigDecimal working = minutes.get(id);
            if (working == null) {
                placed.put(id, new Placed(ready, ready));
                continue;
            }
            Duration length = Duration.ofNanos(working.multiply(BigDecimal.valueOf(60)).movePointRight(9).longValue());
            String machine = equipment.get(id);
            List<String> crew = people.getOrDefault(id, List.of());
            if (machine == null && crew.isEmpty()) {
                placed.put(id, new Placed(ready, ready.plus(length)));
                continue;
            }
            List<String> holders = new ArrayList<>();
            if (machine != null) holders.add("equipment:" + machine);
            crew.forEach(person -> holders.add("person:" + person));
            Instant earliest = ready;
            for (String holder : holders) {
                Instant free = busyUntil.get(holder);
                if (free != null && free.isAfter(earliest)) earliest = free;
            }
            Placed step = within(projectId, machine, crew, earliest, length);
            placed.put(id, step);
            if (step.end() != null) holders.forEach(holder -> busyUntil.put(holder, step.end()));
        }
        return placed;
    }

    /** The step in the time its equipment and named people all work; no end when a year holds too little of it. */
    private Placed within(String projectId, String machine, List<String> crew, Instant earliest, Duration length) {
        for (Duration window : List.of(FIRST_WINDOW, EquipmentWorkingTimeQuery.LONGEST_WINDOW)) {
            OffsetDateTime from = earliest.atOffset(ZoneOffset.UTC);
            OffsetDateTime to = from.plus(window);
            List<WorkingStretch> stretches = List.of(new WorkingStretch(from.toInstant(), to.toInstant()));
            if (machine != null) {
                Optional<List<EquipmentSlot>> slots = equipmentTime.workingStretches(machine, from, to);
                if (slots.isPresent()) {
                    List<WorkingStretch> working = new ArrayList<>();
                    for (EquipmentSlot slot : slots.get()) working.add(new WorkingStretch(slot.start().toInstant(), slot.end().toInstant()));
                    stretches = WorkingStretches.intersect(stretches, working);
                }
            }
            if (!crew.isEmpty()) {
                for (WorkerTime time : workerTime.workingTime(projectId, crew, from, to).values()) {
                    if (!time.calendarSet()) continue;
                    stretches = WorkingStretches.intersect(stretches,
                        time.spans().stream().map(span -> new WorkingStretch(span.start(), span.end())).toList());
                }
            }
            Instant start = WorkingStretches.firstStart(stretches, earliest);
            if (start == null) continue;
            Instant end = WorkingStretches.afterWorking(stretches, start, length);
            if (end != null) return new Placed(start, end);
        }
        return new Placed(null, null);
    }

    /** Each connection's delay in seconds, as runs read it (schema version 3 on). */
    private static Map<String, BigDecimal> delays(JsonNode snapshot) {
        Map<String, BigDecimal> delays = new HashMap<>();
        if (snapshot.path("schemaVersion").asInt(1) < 3) return delays;
        for (JsonNode connection : snapshot.path("connections")) {
            JsonNode delay = connection.path("delayTimeSec");
            if (delay.isNumber() && delay.decimalValue().signum() > 0) {
                delays.put(connection.path("connectionId").asText(), delay.decimalValue());
            }
        }
        return delays;
    }
}
