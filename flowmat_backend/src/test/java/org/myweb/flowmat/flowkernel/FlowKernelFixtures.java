package org.myweb.flowmat.flowkernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What the flow kernel's fixture tests share (docs/architecture/adr/ADR-040-flow-kernel.md decision 5): where the files
 * are, how numbers are read and written, the hand-picked graphs and how a file of Java's answers is written or checked.
 * A graph is {@code {processes, ports, connections}} in the server's response shape, the same JSON the editor holds.
 */
public final class FlowKernelFixtures {

    /** Rewrites the answers instead of checking them: {@code -Dflow.fixtures.update=true}. */
    public static final boolean UPDATE = Boolean.getBoolean("flow.fixtures.update");

    /** Numbers as written, with their scale, as the server reads published revisions and step outputs. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
        .build();

    private static final Path DIRECTORY = locate(Path.of("compute", "flow-kernel", "fixtures"));

    private FlowKernelFixtures() {}

    public static Path file(String name) {
        return DIRECTORY.resolve(name);
    }

    public static JsonNode read(String name) {
        try {
            return MAPPER.readTree(Files.readString(file(name)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The hand-picked graphs of {@code cases.json} by id, in file order. */
    public static Map<String, JsonNode> handPicked() {
        Map<String, JsonNode> graphs = new LinkedHashMap<>();
        read("cases.json").get("cases").forEach(entry -> graphs.put(entry.get("id").asText(), entry.get("graph")));
        return graphs;
    }

    /**
     * The file's answers: one entry per graph on its own line, after a header. On an update the file is written first;
     * either way it must equal what Java answers now.
     */
    public static void writeOrCheck(String name, ObjectNode header, Map<String, ObjectNode> entries) {
        StringBuilder text = new StringBuilder();
        String head = json(header);
        text.append(head, 0, head.length() - 1).append(head.length() > 2 ? "," : "").append("\n\"cases\":[\n");
        int index = 0;
        for (Map.Entry<String, ObjectNode> entry : entries.entrySet()) {
            ObjectNode line = MAPPER.createObjectNode();
            line.put("id", entry.getKey());
            line.setAll(entry.getValue());
            text.append(json(line)).append(++index < entries.size() ? ",\n" : "\n");
        }
        text.append("]}\n");
        try {
            if (UPDATE) {
                Files.createDirectories(DIRECTORY);
                Files.writeString(file(name), text.toString());
            }
            assertThat(Files.readString(file(name))).as(name + " holds Java's answers; rewrite with -Dflow.fixtures.update=true")
                .isEqualTo(text.toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The seeded graphs ({@code g0}, {@code g1}, ...) of a written file, or newly made ones on an update. */
    public static Map<String, JsonNode> seeded(String name, int count, Function<Integer, JsonNode> make,
        Function<JsonNode, JsonNode> field) {
        Map<String, JsonNode> graphs = new LinkedHashMap<>();
        if (UPDATE) {
            for (int index = 0; index < count; index++) graphs.put("g" + index, make.apply(index));
            return graphs;
        }
        read(name).get("cases").forEach(entry -> {
            String id = entry.get("id").asText();
            if (id.matches("g\\d+")) graphs.put(id, field.apply(entry));
        });
        return graphs;
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path locate(Path relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve(relative.getParent()))) return directory.resolve(relative);
        }
        throw new IllegalStateException(relative + " was not found above " + Path.of("").toAbsolutePath());
    }
}
