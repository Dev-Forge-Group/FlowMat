package org.myweb.flowmat.domain.workflow.application;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class WorkflowRevisionDifferTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private JsonNode json(String text) throws Exception { return mapper.readTree(text); }

    @Test
    void collectionOrderObjectOrderAndEquivalentNumbersDoNotCountAsChanges() throws Exception {
        JsonNode before = json("{\"processes\":[{\"processId\":\"b\",\"posX\":1},{\"processId\":\"a\",\"posX\":2.0}],\"workflow\":{\"a\":1,\"b\":2}}");
        JsonNode after = json("{\"workflow\":{\"b\":2.0,\"a\":1.0},\"processes\":[{\"posX\":2,\"processId\":\"a\"},{\"processId\":\"b\",\"posX\":1.00}]}");
        JsonNode originalBefore = before.deepCopy(), originalAfter = after.deepCopy();
        var result = WorkflowRevisionDiffer.compare(before, after, 0, 100);
        assertEquals(0, result.totalChanges());
        assertTrue(result.changes().isEmpty());
        assertNull(result.nextOffset());
        assertEquals(originalBefore, before);
        assertEquals(originalAfter, after);
    }

    @ParameterizedTest
    @CsvSource({"processes,processId", "processIos,processIoId", "connections,connectionId", "annotations,annotationId", "nodePolicies,processId"})
    void matchesEveryKnownCollectionByIdAndShowsAddedRemovedAndModified(String section, String key) throws Exception {
        JsonNode before = json("{\"" + section + "\":[{\"" + key + "\":\"removed\",\"value\":1},{\"" + key + "\":\"shared\",\"value\":1}]}");
        JsonNode after = json("{\"" + section + "\":[{\"" + key + "\":\"shared\",\"value\":2},{\"" + key + "\":\"added\",\"value\":1}]}");
        var result = WorkflowRevisionDiffer.compare(before, after, 0, 100);
        assertEquals(3, result.totalChanges());
        assertEquals(List.of("added", "removed", "shared"), result.changes().stream().map(c -> c.entityId()).toList());
        assertTrue(result.changes().stream().allMatch(c -> c.section().equals(section)));
        var added = result.changes().get(0);
        assertEquals("added", added.kind());
        assertEquals("", added.path());
        assertTrue(added.before().isNull());
        assertEquals("added", added.after().path(key).asText());
        assertEquals("removed", result.changes().get(1).kind());
        assertTrue(result.changes().get(1).after().isNull());
        var changed = result.changes().get(2);
        assertEquals("modified", changed.kind());
        assertEquals("/value", changed.path());
        assertEquals(1, changed.before().intValue());
        assertEquals(2, changed.after().intValue());
    }

    @Test
    void distinguishesMissingAndNullAndEscapesRelativeJsonPointerPaths() throws Exception {
        JsonNode before = json("{\"configuration\":{\"a/b\":{\"~x\":null},\"removedNull\":null,\"\":{\"value\":1}}}");
        JsonNode after = json("{\"configuration\":{\"a/b\":{\"~x\":7},\"addedNull\":null,\"\":{\"value\":2}}}");
        var rows = WorkflowRevisionDiffer.compare(before, after, 0, 100).changes();
        assertEquals(List.of("//value", "/a~1b/~0x", "/addedNull", "/removedNull"), rows.stream().map(c -> c.path()).toList());
        assertTrue(rows.stream().allMatch(c -> c.entityId() == null));
        assertEquals("added", rows.get(2).kind());
        assertEquals("removed", rows.get(3).kind());
        assertTrue(rows.get(2).before().isNull());
        assertTrue(rows.get(2).after().isNull());
    }

    @Test
    void orderedNestedArraysAndFutureSectionsStayVisible() throws Exception {
        JsonNode before = json("{\"future\":{\"values\":[1,2,3]},\"simulationConfig\":{\"limit\":1}}");
        JsonNode after = json("{\"future\":{\"values\":[2,1]},\"simulationConfig\":{\"limit\":\"1\"},\"newSection\":null}");
        var rows = WorkflowRevisionDiffer.compare(before, after, 0, 100).changes();
        assertEquals(5, rows.size());
        assertEquals(List.of("/values/0", "/values/1", "/values/2", "", "/limit"), rows.stream().map(c -> c.path()).toList());
        assertEquals("removed", rows.get(2).kind());
        assertEquals("newSection", rows.get(3).section());
        assertEquals("added", rows.get(3).kind());
        assertEquals("modified", rows.get(4).kind());
    }

    @Test
    void keepsLargeNumbersPreciseAndTreatsNumericScaleAsEquivalent() throws Exception {
        JsonNode before = json("{\"simulationConfig\":{\"large\":9007199254740993.123456789,\"integer\":123456789012345678901234567890,\"same\":1.00}}");
        JsonNode after = json("{\"simulationConfig\":{\"large\":9007199254740993.123456790,\"integer\":123456789012345678901234567891,\"same\":1e0}}");
        var rows = WorkflowRevisionDiffer.compare(before, after, 0, 100).changes();
        assertEquals(2, rows.size());
        assertEquals("/integer", rows.get(0).path());
        assertEquals("123456789012345678901234567891", rows.get(0).after().asText());
        assertEquals(0, new BigDecimal("9007199254740993.123456789").compareTo(rows.get(1).before().decimalValue()));
        assertEquals(0, new BigDecimal("9007199254740993.123456790").compareTo(rows.get(1).after().decimalValue()));
    }

    @Test
    void oldMissingCollectionsAreEmptyAndSchemaVersionChangesAreShown() throws Exception {
        assertEquals(0, WorkflowRevisionDiffer.compare(json("{}"), json("{\"connections\":null,\"nodePolicies\":[]}"), 0, 100).totalChanges());
        var rows = WorkflowRevisionDiffer.compare(json("{\"schemaVersion\":1}"), json("{\"schemaVersion\":3,\"nodePolicies\":[]}"), 0, 100).changes();
        assertEquals(1, rows.size());
        assertEquals("schemaVersion", rows.get(0).section());
        assertEquals("", rows.get(0).path());
        assertEquals(1, rows.get(0).before().intValue());
        assertEquals(3, rows.get(0).after().intValue());
    }

    @Test
    void offsetPagesCanBeConcatenatedToTheWholeDeterministicResult() throws Exception {
        var before = mapper.createObjectNode();
        var after = mapper.createObjectNode();
        for (int i = 0; i < 7; i++) after.put("field" + i, i);
        var all = WorkflowRevisionDiffer.compare(before, after, 0, 100);
        List<Object> found = new ArrayList<>();
        int offset = 0;
        do {
            var page = WorkflowRevisionDiffer.compare(before, after, offset, 2);
            assertEquals(7, page.totalChanges());
            found.addAll(page.changes());
            if (page.nextOffset() == null) break;
            offset = page.nextOffset();
        } while (true);
        assertEquals(all.changes(), found);
        var exhausted = WorkflowRevisionDiffer.compare(before, after, Integer.MAX_VALUE, 1);
        assertEquals(7, exhausted.totalChanges());
        assertTrue(exhausted.changes().isEmpty());
        assertNull(exhausted.nextOffset());
    }

    @Test
    void outputValuesDoNotAliasTheSnapshotsAndThePageListIsImmutable() throws Exception {
        JsonNode before = json("{\"simulationConfig\":{\"value\":{\"old\":1}}}");
        JsonNode after = json("{\"simulationConfig\":{\"value\":[2]}}");
        var page = WorkflowRevisionDiffer.compare(before, after, 0, 100);
        ((ObjectNode) page.changes().get(0).before()).put("injected", true);
        assertFalse(before.path("simulationConfig").path("value").has("injected"));
        assertThrows(UnsupportedOperationException.class, () -> page.changes().clear());
    }

    static Stream<String> invalidSnapshots() {
        return Stream.of("null", "[]", "1", "{\"processes\":{}}", "{\"processes\":\"invalid\"}",
            "{\"processes\":[null]}", "{\"processes\":[{}]}", "{\"processes\":[{\"processId\":0}]}",
            "{\"processes\":[{\"processId\":\" \"}]}", "{\"processes\":[{\"processId\":\"x\"},{\"processId\":\"x\"}]}");
    }

    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    void ambiguousStoredShapesAreRejectedInEitherPosition(String bad) throws Exception {
        JsonNode malformed = json(bad), valid = json("{}");
        BusinessException left = assertThrows(BusinessException.class, () -> WorkflowRevisionDiffer.compare(malformed, valid, 0, 100));
        BusinessException right = assertThrows(BusinessException.class, () -> WorkflowRevisionDiffer.compare(valid, malformed, 0, 100));
        assertEquals(ErrorCode.CONFLICT, left.getErrorCode());
        assertEquals(ErrorCode.CONFLICT, right.getErrorCode());
        assertFalse(left.getMessage().contains(bad));
    }
}
