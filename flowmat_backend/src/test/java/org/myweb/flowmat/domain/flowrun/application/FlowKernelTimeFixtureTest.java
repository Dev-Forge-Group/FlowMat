package org.myweb.flowmat.domain.flowrun.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.flowkernel.FlowKernelFixtures.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.flowrun.application.publicapi.WorkingStretch;
import org.myweb.flowmat.flowkernel.FlowKernelFixtures;

/**
 * Writes compute/flow-kernel/fixtures/time.json (docs/architecture/adr/ADR-041-try-it-time.md decision 4): when each step of
 * a tried flow is ready and scheduled, as the router's own rules ({@link FlowRunTiming}) put it. Which steps run, from where
 * and what reaches each Join does not depend on time, so it is the server's walk of the same graph without its delays
 * (walk.json). The delays are the graph's own, as routing.json holds it. Each case gets a start, the stretches its work
 * order's equipment and crew work together (seeded), and what held a step. A step ends as soon as it may start; a gateway
 * passes at once and keeps the time it received; a Join passes when its last arrival comes. Times are written as
 * {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} writes them.
 */
class FlowKernelTimeFixtureTest {

    private static final String FILE = "time.json";
    private static final long SEED = 20261221L;
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Test
    void javasTimesAreTheFixtures() {
        Map<String, JsonNode> graphs = new HashMap<>();
        FlowKernelFixtures.read("routing.json").get("cases").forEach(entry -> graphs.put(entry.get("id").asText(), entry.get("graph")));
        Map<String, JsonNode> written = new HashMap<>();
        if (!FlowKernelFixtures.UPDATE) {
            FlowKernelFixtures.read(FILE).get("cases").forEach(entry -> written.put(entry.get("id").asText(), entry));
        }
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, ObjectNode> entries = new LinkedHashMap<>();
        for (JsonNode walk : FlowKernelFixtures.read("walk.json").get("cases")) {
            String id = walk.get("id").asText();
            JsonNode graph = graphs.get(id);
            if (graph == null || !delayed(graph)) continue;
            ObjectNode entry = MAPPER.createObjectNode();
            entry.set("graph", graph);
            entry.set("input", walk.get("input"));
            JsonNode context = FlowKernelFixtures.UPDATE ? context(new Random(SEED ^ id.hashCode())) : written.get(id);
            for (String field : List.of("start", "stretches", "equipmentId", "crew")) entry.set(field, context.get(field));
            entry.set("expected", schedule(FlowRunGraph.from(FlowKernelRouteFixtureTest.snapshot(graph)), walk.get("expected"),
                entry, outcomes));
            entries.put(id, entry);
        }
        ObjectNode header = MAPPER.createObjectNode();
        header.put("seed", SEED);
        header.putObject("context").put("schemaVersion", 3);
        if (FlowKernelFixtures.UPDATE) System.out.printf("%s: %d tried flows, outcomes %s%n", FILE, entries.size(), outcomes);
        FlowKernelFixtures.writeOrCheck(FILE, header, entries);
        assertThat(outcomes).as("delays, working-time delays and their fallback, waits, Joins, gateways, no stretches, an offset change")
            .containsKeys("ready", "working_delay", "working_fallback", "held", "join_delayed", "carried", "no_stretches",
                "offset_change", "fraction");
    }

    /** The steps' times along the server's walk, in its order. */
    private static ObjectNode schedule(FlowRunGraph graph, JsonNode walk, JsonNode context, Map<String, Integer> outcomes) {
        OffsetDateTime start = OffsetDateTime.parse(context.get("start").asText());
        List<WorkingStretch> stretches = new ArrayList<>();
        context.get("stretches").forEach(pair -> stretches.add(new WorkingStretch(OffsetDateTime.parse(pair.get(0).asText()).toInstant(),
            OffsetDateTime.parse(pair.get(1).asText()).toInstant())));
        List<String> crew = new ArrayList<>();
        context.get("crew").forEach(person -> crew.add(person.asText()));
        FlowRunTiming.WorkingTime time = stretches.isEmpty() ? FlowRunTiming.ALL_TIME
            : FlowRunTiming.stretches(stretches, context.get("equipmentId").isNull() ? null : context.get("equipmentId").asText(), crew);
        if (stretches.isEmpty()) outcomes.merge("no_stretches", 1, Integer::sum);
        else if (offsets(context.get("stretches")) > 1) outcomes.merge("offset_change", 1, Integer::sum);
        if (start.getNano() != 0) outcomes.merge("fraction", 1, Integer::sum);

        JsonNode steps = walk.get("steps");
        int count = steps.size();
        OffsetDateTime[] created = new OffsetDateTime[count];
        OffsetDateTime[] ended = new OffsetDateTime[count];
        OffsetDateTime[] scheduled = new OffsetDateTime[count];
        ArrayNode answer = MAPPER.createArrayNode();
        for (int index = 0; index < count; index++) {
            JsonNode step = steps.get(index);
            String nodeId = step.get("nodeId").asText();
            ObjectNode out = answer.addObject();
            out.put("nodeId", nodeId);
            OffsetDateTime readyAt = null;
            LinkedRunWorkingTime.WorkingStart held = null;
            if (!step.has("source")) {
                created[index] = start;
            } else if (graph.join(nodeId)) {
                List<FlowRunTiming.Arrival> arrivals = new ArrayList<>();
                OffsetDateTime last = null;
                for (JsonNode arrival : step.path("input").path("arrivals")) {
                    int from = arrival.get("sourceStepId").asInt();
                    arrivals.add(new FlowRunTiming.Arrival(arrive(graph, from, arrival.get("connectionId").asText(), steps, ended,
                        scheduled, time, outcomes), ended[from]));
                    last = later(last, ended[from]);
                }
                created[index] = ended[step.get("source").asInt()];
                scheduled[index] = FlowRunTiming.joinReadyAt(arrivals);
                if (scheduled[index] != null) outcomes.merge("join_delayed", 1, Integer::sum);
                if ("completed".equals(step.get("status").asText())) ended[index] = last;
            } else {
                int from = step.get("source").asInt();
                created[index] = ended[from];
                readyAt = arrive(graph, from, step.get("connectionId").asText(), steps, ended, scheduled, time, outcomes);
                if (readyAt != null) {
                    FlowRunTiming.Scheduled at = FlowRunTiming.scheduled(readyAt, graph.gateway(nodeId), time);
                    scheduled[index] = at.at();
                    held = at.held();
                    if (held != null) outcomes.merge("held", 1, Integer::sum);
                }
            }
            if (!graph.join(nodeId) && "completed".equals(step.get("status").asText())) {
                // A gateway passes at once; a reported step ends as soon as it may start.
                ended[index] = graph.gateway(nodeId) ? created[index] : later(created[index], scheduled[index]);
            }
            if (readyAt != null) out.put("readyAt", TEXT.format(readyAt));
            if (scheduled[index] != null) out.put("scheduledAt", TEXT.format(scheduled[index]));
            if (ended[index] != null) out.put("endedAt", TEXT.format(ended[index]));
            if (held != null) {
                ObjectNode by = out.putObject("held");
                by.put("equipmentId", held.equipmentId());
                by.set("crew", MAPPER.valueToTree(held.crew()));
            }
        }
        ObjectNode expected = MAPPER.createObjectNode();
        expected.set("steps", answer);
        return expected;
    }

    /** When what step {@code from} sent along {@code connectionId} is ready, as the router reads it. */
    private static OffsetDateTime arrive(FlowRunGraph graph, int from, String connectionId, JsonNode steps, OffsetDateTime[] ended,
        OffsetDateTime[] scheduled, FlowRunTiming.WorkingTime time, Map<String, Integer> outcomes) {
        boolean gateway = graph.gateway(steps.get(from).get("nodeId").asText());
        OffsetDateTime carried = gateway ? scheduled[from] : null;
        Duration delay = graph.delay(connectionId);
        boolean working = graph.workingDelay(connectionId);
        OffsetDateTime readyAt = FlowRunTiming.readyAt(ended[from], carried, delay, working, time);
        if (readyAt != null) outcomes.merge("ready", 1, Integer::sum);
        if (carried != null) outcomes.merge("carried", 1, Integer::sum);
        if (readyAt != null && delay != null && working) {
            OffsetDateTime base = carried != null ? carried : ended[from];
            outcomes.merge(time.afterWorking(base, delay) == null ? "working_fallback" : "working_delay", 1, Integer::sum);
        }
        return readyAt;
    }

    private static OffsetDateTime later(OffsetDateTime one, OffsetDateTime other) {
        if (one == null) return other;
        if (other == null) return one;
        return other.isAfter(one) ? other : one;
    }

    private static boolean delayed(JsonNode graph) {
        for (JsonNode connection : graph.get("connections")) {
            if (connection.hasNonNull("delayTimeSec") && connection.get("delayTimeSec").decimalValue().signum() != 0) return true;
        }
        return false;
    }

    /** How many offsets the stretches are written in: more than one across a change such as spring forward. */
    private static long offsets(JsonNode stretches) {
        Set<ZoneOffset> seen = new HashSet<>();
        stretches.forEach(pair -> seen.add(OffsetDateTime.parse(pair.get(0).asText()).getOffset()));
        return seen.size();
    }

    /**
     * A seeded start and working time. One in ten has none (all time). Two in ten fall on a spring-forward weekend, the
     * stretches' offset going from -05:00 to -04:00. One in ten has a single ten-minute stretch five minutes after the start.
     * The rest work day shifts 08:00–12:00 and 13:00–17:00 on weekdays, or one in ten a night shift 22:00–06:00 every day,
     * over ten days. A third of the starts have a fraction of a second.
     */
    private static ObjectNode context(Random random) {
        ObjectNode context = MAPPER.createObjectNode();
        int kind = random.nextInt(10);
        boolean change = kind == 1 || kind == 2;
        LocalDate first = change ? LocalDate.of(2027, 3, 12) : LocalDate.of(2026, 12, 21);
        ZoneOffset fixed = List.of(ZoneOffset.ofHours(9), ZoneOffset.UTC, ZoneOffset.ofHours(-5)).get(random.nextInt(3));
        Function<LocalDate, ZoneOffset> offset = day -> !change ? fixed
            : day.isBefore(LocalDate.of(2027, 3, 14)) ? ZoneOffset.ofHours(-5) : ZoneOffset.ofHours(-4);
        LocalTime at = LocalTime.of(6, 0).plusMinutes(random.nextInt(12 * 60));
        if (random.nextInt(3) == 0) at = at.plusNanos(random.nextInt(1_000_000) * 1_000L);
        context.put("start", TEXT.format(first.atTime(at).atOffset(offset.apply(first))));
        ArrayNode stretches = context.putArray("stretches");
        if (kind == 0) {
            context.putNull("equipmentId");
            context.putArray("crew");
            return context;
        }
        if (kind == 4) {
            // A single short stretch soon after the start: longer working-time delays count all time instead.
            OffsetDateTime begin = first.atTime(at).atOffset(offset.apply(first)).plusMinutes(5);
            stretches.addArray().add(TEXT.format(begin)).add(TEXT.format(begin.plusMinutes(10)));
        }
        boolean night = kind == 3;
        for (LocalDate day = first; kind != 4 && day.isBefore(first.plusDays(10)); day = day.plusDays(1)) {
            if (night) {
                stretches.addArray().add(TEXT.format(day.atTime(22, 0).atOffset(offset.apply(day))))
                    .add(TEXT.format(day.plusDays(1).atTime(6, 0).atOffset(offset.apply(day.plusDays(1)))));
            } else if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                stretches.addArray().add(TEXT.format(day.atTime(8, 0).atOffset(offset.apply(day))))
                    .add(TEXT.format(day.atTime(12, 0).atOffset(offset.apply(day))));
                stretches.addArray().add(TEXT.format(day.atTime(13, 0).atOffset(offset.apply(day))))
                    .add(TEXT.format(day.atTime(17, 0).atOffset(offset.apply(day))));
            }
        }
        int team = random.nextInt(3);
        if (team == 0) context.putNull("equipmentId");
        else context.put("equipmentId", "press");
        ArrayNode crew = context.putArray("crew");
        if (team != 1) crew.add("kim");
        if (team == 2 && random.nextBoolean()) crew.add("lee");
        return context;
    }
}
