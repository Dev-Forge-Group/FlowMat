package org.myweb.flowmat.domain.workflow.domain.expression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.exception.BusinessException;

/**
 * The condition language's shared fixtures in {@code compute/condition-expression/fixtures}. The Rust implementation
 * there must answer every case the way this class records Java answering it: the same results, the same error message
 * and position, and the same declared-attribute check.
 *
 * <p>{@code cases.json} holds hand-picked sources and snapshots, and {@code cases.expected.json} what Java answers.
 * {@code generated.json} holds sources and snapshots made from a fixed seed, with Java's answers. After a deliberate
 * change to the language, rewrite the answers with {@code ./gradlew test --tests '*ConditionExpressionFixtureTest'
 * -Dcondition.fixtures.update=true} and read the diff.
 */
class ConditionExpressionFixtureTest {

    private static final Path FIXTURES = locate(Path.of("compute", "condition-expression", "fixtures"));
    private static final boolean UPDATE = Boolean.getBoolean("condition.fixtures.update");
    private static final long SEED = 20261120L;
    private static final int GENERATED_SOURCES = 1500;
    private static final int GENERATED_SNAPSHOTS = 24;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The reader the run graph uses for output snapshots (FlowRunHttpExecutionService): numbers stay exact decimals. */
    static final ObjectReader SNAPSHOTS = MAPPER.reader()
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    private static final Pattern MESSAGE = Pattern.compile("Invalid condition at position (\\d+): (.*)", Pattern.DOTALL);

    @Test
    void javaAnswersTheHandPickedCasesAsRecorded() throws IOException {
        JsonNode input = SNAPSHOTS.readTree(Files.readString(FIXTURES.resolve("cases.json")));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        input.get("snapshots").forEach(entry -> snapshots.add(values(entry.get("snapshot"))));
        List<JsonNode> outcomes = new ArrayList<>();
        for (JsonNode entry : input.get("cases")) {
            outcomes.add(outcome(entry.get("source").asText(), declared(entry), snapshots));
        }
        ObjectNode header = MAPPER.createObjectNode()
            .put("about", "What Java's ConditionExpression answers for each case in cases.json, in order: the error, or "
                + "the attributes it reads and one result per snapshot (1 true, 0 false), and the declared-attribute check "
                + "where the case lists declared names. Written by ConditionExpressionFixtureTest; do not edit by hand.")
            .put("jdk", Runtime.version().feature());
        header.set("characterClasses", characterClasses());
        settle("cases.expected.json", header, "outcomes", outcomes);
    }

    @Test
    void javaAnswersTheGeneratedCasesAsRecorded() throws IOException {
        Path file = FIXTURES.resolve("generated.json");
        List<JsonNode> snapshotNodes = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        if (UPDATE) {
            Corpus corpus = new Corpus(new Random(SEED));
            for (int index = 0; index < GENERATED_SNAPSHOTS; index++) snapshotNodes.add(corpus.snapshot());
            Set<String> unique = new LinkedHashSet<>();
            while (unique.size() < GENERATED_SOURCES) unique.add(corpus.source());
            sources.addAll(unique);
        } else {
            JsonNode recorded = SNAPSHOTS.readTree(Files.readString(file));
            recorded.get("snapshots").forEach(snapshotNodes::add);
            recorded.get("cases").forEach(entry -> sources.add(entry.get("source").asText()));
        }
        List<Map<String, Object>> snapshots = snapshotNodes.stream().map(ConditionExpressionFixtureTest::values).toList();
        List<JsonNode> outcomes = sources.stream().map(source -> (JsonNode) outcome(source, null, snapshots)).toList();
        ObjectNode header = MAPPER.createObjectNode()
            .put("about", "Sources and snapshots made from a fixed seed by ConditionExpressionFixtureTest.Corpus, with what "
                + "Java answers: the error, or the attributes read and one result per snapshot (1 true, 0 false). Written "
                + "by ConditionExpressionFixtureTest; do not edit by hand.")
            .put("seed", SEED);
        ArrayNode snapshotArray = header.putArray("snapshots");
        snapshotNodes.forEach(snapshotArray::add);
        settle("generated.json", header, "cases", outcomes);
        if (UPDATE) {
            long valid = outcomes.stream().filter(outcome -> outcome.has("results")).count();
            System.out.printf("generated.json: %d sources, %d compile%n", outcomes.size(), valid);
        }
    }

    /** The JDK's character classes the tokenizer uses; the Rust implementation keeps the same tables. */
    private static ObjectNode characterClasses() {
        ObjectNode classes = MAPPER.createObjectNode();
        ArrayNode zeros = classes.putArray("digitZeros");
        ArrayNode whitespace = classes.putArray("whitespace");
        for (int unit = 0; unit <= Character.MAX_VALUE; unit++) {
            char character = (char) unit;
            if (Character.isDigit(character)) {
                int digit = Character.digit(character, 10);
                assertThat(digit).as("U+%04X", unit).isBetween(0, 9);
                assertThat(Character.digit((char) (unit - digit), 10)).as("U+%04X starts a run of ten", unit - digit)
                    .isZero();
                if (digit == 0) zeros.add(unit);
            }
            if (Character.isWhitespace(character)) whitespace.add(unit);
        }
        return classes;
    }

    private static ObjectNode outcome(String source, Set<String> declared, List<Map<String, Object>> snapshots) {
        ObjectNode outcome = MAPPER.createObjectNode().put("source", source);
        ConditionExpression expression;
        try {
            expression = ConditionExpression.compile(source);
        } catch (BusinessException exception) {
            outcome.set("error", error(exception));
            return outcome;
        }
        ArrayNode attributes = outcome.putArray("attributes");
        new TreeSet<>(expression.attributeReferences()).forEach(attributes::add);
        StringBuilder results = new StringBuilder();
        for (Map<String, Object> values : snapshots) results.append(expression.evaluate(values) ? '1' : '0');
        outcome.put("results", results.toString());
        if (declared != null) {
            try {
                expression.requireDeclaredAttributes(declared);
                outcome.putNull("declaredError");
            } catch (BusinessException exception) {
                outcome.set("declaredError", error(exception));
            }
        }
        return outcome;
    }

    private static ObjectNode error(BusinessException exception) {
        Matcher matcher = MESSAGE.matcher(exception.getMessage());
        assertThat(matcher.matches()).as(exception.getMessage()).isTrue();
        return MAPPER.createObjectNode()
            .put("position", Integer.parseInt(matcher.group(1)))
            .put("reason", matcher.group(2));
    }

    private static Set<String> declared(JsonNode entry) {
        if (!entry.has("declared")) return null;
        Set<String> names = new LinkedHashSet<>();
        entry.get("declared").forEach(name -> names.add(name.asText()));
        return names;
    }

    /** The values FlowRunGraph.values takes from an output snapshot. */
    static Map<String, Object> values(JsonNode output) {
        Map<String, Object> values = new HashMap<>();
        for (String field : List.of("quantity", "unit", "item")) {
            JsonNode node = output.get(field);
            if (node != null) values.put(field, value(node));
        }
        JsonNode attrs = output.path("attrs");
        if (attrs.isObject()) {
            Map<String, Object> attributes = new HashMap<>();
            attrs.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), value(entry.getValue())));
            values.put("attrs", attributes);
        }
        return values;
    }

    private static Object value(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        return null;
    }

    /**
     * Writes the file in update mode; otherwise checks that what Java answers now is what the file records. One line per
     * entry keeps a change readable in a diff.
     */
    private static void settle(String name, ObjectNode header, String listName, List<JsonNode> entries) throws IOException {
        Path file = FIXTURES.resolve(name);
        if (UPDATE) {
            StringBuilder text = new StringBuilder("{\n");
            header.fields().forEachRemaining(field -> text.append("  ").append(json(MAPPER.getNodeFactory()
                .textNode(field.getKey()))).append(": ").append(json(field.getValue())).append(",\n"));
            text.append("  ").append(json(JsonNodeFactory.instance.textNode(listName))).append(": [\n");
            for (int index = 0; index < entries.size(); index++) {
                text.append("    ").append(json(entries.get(index))).append(index < entries.size() - 1 ? ",\n" : "\n");
            }
            text.append("  ]\n}\n");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return;
        }
        JsonNode recorded = SNAPSHOTS.readTree(Files.readString(file));
        // As text: the file's 20261120 reads back as an int node, the header's as a long one.
        header.fields().forEachRemaining(field -> assertThat(json(recorded.get(field.getKey())))
            .as("%s %s; rewrite with -Dcondition.fixtures.update=true after a deliberate change", name, field.getKey())
            .isEqualTo(json(field.getValue())));
        JsonNode list = recorded.get(listName);
        assertThat(list.size()).as("%s entries", name).isEqualTo(entries.size());
        List<String> differences = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (!list.get(index).equals(entries.get(index))) {
                differences.add("#" + index + " recorded " + json(list.get(index)) + "\n      now " + json(entries.get(index)));
            }
        }
        assertThat(differences)
            .as("%s: Java no longer answers as recorded; rewrite with -Dcondition.fixtures.update=true after a "
                + "deliberate change", name)
            .isEmpty();
    }

    private static String json(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path locate(Path relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve(relative))) return directory.resolve(relative);
        }
        throw new IllegalStateException(relative + " was not found above " + Path.of("").toAbsolutePath());
    }

    /**
     * Sources and snapshots from a seed. Sources are built from the grammar. Six in ten use valid pieces only, so that
     * evaluation is well covered; the rest also draw operands, operators and gaps that are not valid, and some of those
     * are then cut, or have a character put in or taken out, so that errors of every kind and position turn up.
     * {@link Random} is specified to the bit, so a seed gives the same corpus on every JDK.
     */
    static final class Corpus {
        private static final String EMOJI = "\uD83D\uDE00";
        private static final List<String> FIELDS = List.of("quantity", "quantity", "quantity", "unit", "unit", "item",
            "attrs.approved", "attrs.grade", "attrs.weight", "attrs.code", "attrs.missing", "attrs.a", "attrs.b");
        private static final List<String> LITERALS = List.of("0", "-0", "2", "2.0", "2.00", "-1.5", "1.5", "10", "007",
            "0.1", "12.5", "12.49", "1000", "12345678901234567890.123", "-12345678901234567890.123",
            "\u0661\u0662.\u0665", "\u0967\u0966", "\uFF11\uFF12", "'kg'", "'KG'", "'ea'", "''", "'kh'", "'it\\'s'",
            "'back\\\\slash'", "'" + EMOJI + "'", "'\uFF5E'", "'\uE000'", "'\uAC00'", "'\uFF4B\uFF47'", "'A'", "'B'",
            "'X-1'", "'true'", "'2'", "true", "false", "true", "false");
        private static final List<String> BAD_OPERANDS = List.of("3.", "1e3", ".5", "-", "- 1", "'bad\\escape'",
            "'unterminated", "weight", "attrs", "Quantity", "TRUE", "null", "attrs.", "attrs.1x", "attrs.\uAC00",
            "sum(quantity)", "\"kg\"", "#");
        private static final List<String> OPERATORS = List.of("=", "!=", "<", "<=", ">", ">=");
        private static final List<String> BAD_OPERATORS = List.of("==", "=>", "=<", "<>", "!", "!!", "\u2260");
        /** Whitespace to Java, including some that is not to Rust's char::is_whitespace. */
        private static final List<String> GAPS = List.of("  ", "\t", "\n", "\u3000", "\u001C", "\u2028");
        /** Not whitespace to Java, though some is to Unicode, and no gap at all. */
        private static final List<String> BAD_GAPS = List.of("\u00A0", "\u2007", "\u0085", "\u202F", "");
        private static final List<String> INSERTS = List.of("(", ")", "'", "\\", ".", "-", "=", "!", EMOJI, "#", "a",
            "1", " ");
        private static final List<JsonNode> SCALARS;

        static {
            JsonNodeFactory nodes = JsonNodeFactory.instance;
            List<JsonNode> scalars = new ArrayList<>();
            for (String number : List.of("2", "2.00", "1.5", "-1.5", "0", "12.5", "12.49", "1000", "1E+3",
                "12345678901234567890.123", "10", "0.1")) {
                scalars.add(nodes.numberNode(new BigDecimal(number)));
            }
            for (String text : List.of("kg", "KG", "ea", "", "kh", EMOJI, "\uFF5E", "\uE000", "\uAC00", "\uFF4B\uFF47",
                "it's", "back\\slash", "A", "B", "X-1", "true", "2", "allowed", "blocked")) {
                scalars.add(nodes.textNode(text));
            }
            scalars.add(nodes.booleanNode(true));
            scalars.add(nodes.booleanNode(false));
            scalars.add(nodes.nullNode());
            scalars.add(nodes.arrayNode().add(2));
            scalars.add(nodes.objectNode().put("kg", 1));
            SCALARS = List.copyOf(scalars);
        }

        private final Random random;
        private boolean noisy;

        Corpus(Random random) {
            this.random = random;
        }

        String source() {
            noisy = random.nextInt(10) < 4;
            String source = expression(0);
            return noisy && random.nextInt(10) < 3 ? mutate(source) : source;
        }

        ObjectNode snapshot() {
            ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
            for (String field : List.of("quantity", "unit", "item")) {
                if (random.nextInt(5) > 0) snapshot.set(field, pick(SCALARS));
            }
            int attrs = random.nextInt(6);
            if (attrs == 0) {
                snapshot.put("attrs", "approved");
            } else if (attrs > 1) {
                ObjectNode attributes = snapshot.putObject("attrs");
                for (String name : List.of("approved", "grade", "weight", "code", "a", "b")) {
                    if (random.nextInt(3) > 0) attributes.set(name, pick(SCALARS));
                }
            }
            return snapshot;
        }

        private String expression(int depth) {
            int choice = depth >= 3 ? 0 : random.nextInt(10);
            return switch (choice) {
                case 0, 1, 2, 3, 4 -> operand() + gap(false) + operator() + gap(false) + operand();
                case 5 -> "not" + gap(true) + expression(depth + 1);
                case 6 -> "(" + gap(false) + expression(depth + 1) + gap(false) + ")";
                case 7, 8 -> expression(depth + 1) + gap(true) + "and" + gap(true) + expression(depth + 1);
                default -> expression(depth + 1) + gap(true) + "or" + gap(true) + expression(depth + 1);
            };
        }

        private String operand() {
            int choice = random.nextInt(20);
            if (noisy && choice == 0) return pick(BAD_OPERANDS);
            return choice < 10 ? pick(FIELDS) : pick(LITERALS);
        }

        private String operator() {
            return noisy && random.nextInt(5) == 0 ? pick(BAD_OPERATORS) : pick(OPERATORS);
        }

        private String gap(boolean required) {
            int choice = random.nextInt(20);
            if (noisy && choice == 0) return pick(BAD_GAPS);
            if (choice < 15) return " ";
            if (choice < 17 && !required) return "";
            return pick(GAPS);
        }

        private String mutate(String source) {
            if (source.isEmpty()) return source;
            int at = random.nextInt(source.length());
            // Never between the two halves of one character: a lone surrogate is not text either side can hold.
            if (at > 0 && Character.isLowSurrogate(source.charAt(at)) && Character.isHighSurrogate(source.charAt(at - 1))) {
                at--;
            }
            int next = source.offsetByCodePoints(at, 1);
            return switch (random.nextInt(3)) {
                case 0 -> source.substring(0, at) + source.substring(next);
                case 1 -> source.substring(0, at) + pick(INSERTS) + source.substring(at);
                default -> source.substring(0, at);
            };
        }

        private <T> T pick(List<T> values) {
            return values.get(random.nextInt(values.size()));
        }
    }
}
