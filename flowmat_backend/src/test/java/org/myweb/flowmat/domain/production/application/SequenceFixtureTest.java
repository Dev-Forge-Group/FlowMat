package org.myweb.flowmat.domain.production.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Baseline;

/**
 * The sequence optimizer's evaluation set (compute/sequence-optimizer, docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md
 * decision 3): what the plan and the current rule cost on each snapshot, as {@link SequenceCost} and the load board's rule
 * compute it. The Python side compares its solver with these numbers and checks its own mirror of the cost against them,
 * so the current rule is never written twice. After a deliberate change to the cost or the rule, rewrite the file with
 * {@code ./gradlew test --tests '*SequenceFixtureTest' -Dsequence.fixtures.update=true} and read the diff.
 */
class SequenceFixtureTest {

    private static final Path FIXTURES = locate(Path.of("compute", "sequence-optimizer", "fixtures"));
    private static final boolean UPDATE = Boolean.getBoolean("sequence.fixtures.update");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void theBaselinesAreWhatTheCostAndTheCurrentRuleGive() throws IOException {
        JsonNode cases = MAPPER.readTree(Files.readString(FIXTURES.resolve("snapshots.json"))).get("cases");
        ArrayNode baselines = MAPPER.createArrayNode();
        for (JsonNode entry : cases) {
            SequenceSnapshotResponse snapshot = SequenceSnapshotService.withBaselines(
                MAPPER.treeToValue(entry.get("snapshot"), SequenceSnapshotResponse.class));
            ObjectNode recorded = baselines.addObject();
            recorded.put("id", entry.get("id").asText());
            recorded.set("plan", baseline(snapshot.plan()));
            recorded.set("currentRule", baseline(snapshot.currentRule()));
        }
        String text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.createObjectNode().set("cases", baselines)) + "\n";
        Path file = FIXTURES.resolve("baselines.json");
        if (UPDATE) Files.writeString(file, text);
        assertThat(Files.readString(file)).isEqualTo(text);
    }

    /** The order and its totals, and each job's end and minutes late: what the Python mirror must reproduce. */
    private static JsonNode baseline(Baseline baseline) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("order", MAPPER.valueToTree(baseline.order()));
        node.put("changeoverMinutes", baseline.changeoverMinutes());
        node.put("lateMinutes", baseline.lateMinutes());
        node.put("weightedLateMinutes", baseline.weightedLateMinutes().stripTrailingZeros().toPlainString());
        node.put("objective", baseline.objective() == null ? null : baseline.objective().stripTrailingZeros().toPlainString());
        node.put("unplaced", baseline.unplaced());
        ArrayNode ends = node.putArray("ends");
        baseline.placements().forEach(placement -> ends.add(placement.end() == null ? null : placement.end().toInstant().toString()));
        ArrayNode late = node.putArray("late");
        baseline.placements().forEach(placement -> {
            if (placement.lateMinutes() == null) late.addNull(); else late.add(placement.lateMinutes());
        });
        return node;
    }

    private static Path locate(Path relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve(relative))) return directory.resolve(relative);
        }
        throw new IllegalStateException(relative + " was not found above " + Path.of("").toAbsolutePath());
    }
}
