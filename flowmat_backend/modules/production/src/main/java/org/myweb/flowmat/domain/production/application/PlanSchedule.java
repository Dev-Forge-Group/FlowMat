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
import org.springframework.stereotype.Component;

/**
 * When each step of a plan runs (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 5): a step is ready
 * when every step before it has ended and the connection's delay has passed; one that takes equipment waits for the
 * equipment's working time (its shifts less holidays and downtime, as runs see it) and for the step before it on the same
 * equipment. Its minutes are working minutes. A step without equipment runs as soon as it is ready.
 */
@Component
@RequiredArgsConstructor
class PlanSchedule {

    /** How far ahead working time is looked for at first; then a year at most. */
    private static final Duration FIRST_WINDOW = Duration.ofDays(31);

    private final EquipmentWorkingTimeQuery equipmentTime;

    record Placed(Instant start, Instant end) {
    }

    /** Each node with minutes, or between steps, placed from {@code from}; nodes in topological order. */
    Map<String, Placed> place(FlowDemand.Graph graph, JsonNode snapshot, List<String> order, Map<String, BigDecimal> minutes,
        Instant from) {
        Map<String, BigDecimal> delays = delays(snapshot);
        Map<String, String> equipment = new HashMap<>();
        for (FlowDemand.Node node : graph.nodes()) {
            node.execution().stream().filter(one -> "equipment".equals(one.kind()) && one.ref() != null).findFirst()
                .ifPresent(one -> equipment.put(node.id(), one.ref()));
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
            if (machine == null) {
                placed.put(id, new Placed(ready, ready.plus(length)));
                continue;
            }
            Instant free = busyUntil.getOrDefault(machine, ready);
            Instant earliest = free.isAfter(ready) ? free : ready;
            Placed onMachine = onEquipment(machine, earliest, length);
            placed.put(id, onMachine);
            if (onMachine.end() != null) busyUntil.put(machine, onMachine.end());
        }
        return placed;
    }

    /** The step's start and end in the equipment's working time; no end when a year holds too little of it. */
    private Placed onEquipment(String equipmentId, Instant earliest, Duration length) {
        for (Duration window : List.of(FIRST_WINDOW, EquipmentWorkingTimeQuery.LONGEST_WINDOW)) {
            OffsetDateTime from = earliest.atOffset(ZoneOffset.UTC);
            Optional<List<EquipmentSlot>> slots = equipmentTime.workingStretches(equipmentId, from, from.plus(window));
            if (slots.isEmpty()) return new Placed(earliest, earliest.plus(length));
            List<WorkingStretch> stretches = new ArrayList<>();
            for (EquipmentSlot slot : slots.get()) stretches.add(new WorkingStretch(slot.start().toInstant(), slot.end().toInstant()));
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
