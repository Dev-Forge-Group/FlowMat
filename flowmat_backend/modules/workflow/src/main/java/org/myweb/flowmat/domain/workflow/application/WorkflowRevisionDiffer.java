package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse.Change;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Pure, deterministic comparison of frozen snapshots. Registered collections use IDs; nested arrays retain order. */
final class WorkflowRevisionDiffer {
    private static final Map<String, String> ID_FIELDS = Map.of(
        "processes", "processId", "processIos", "processIoId", "connections", "connectionId",
        "annotations", "annotationId", "nodePolicies", "processId", "nodeBindings", "processId");

    private WorkflowRevisionDiffer() {}

    record Result(int totalChanges, List<Change> changes, Integer nextOffset) {
        Result { changes = List.copyOf(changes); }
    }

    static Result compare(JsonNode before, JsonNode after, int offset, int limit) {
        if (before == null || after == null || !before.isObject() || !after.isObject()) {
            throw invalid("Stored revision snapshots must be JSON objects.");
        }
        Collector result = new Collector(offset, limit);
        for (String section : fields(before, after)) {
            String idField = ID_FIELDS.get(section);
            if (idField == null) visit(section, null, "", before.path(section), after.path(section), result);
            else {
                Map<String, JsonNode> left = index(before.path(section), section, idField);
                Map<String, JsonNode> right = index(after.path(section), section, idField);
                TreeSet<String> ids = new TreeSet<>(left.keySet());
                ids.addAll(right.keySet());
                for (String id : ids) visit(section, id, "", left.getOrDefault(id, MissingNode.getInstance()),
                    right.getOrDefault(id, MissingNode.getInstance()), result);
            }
        }
        int next = offset + result.rows.size();
        return new Result(result.total, result.rows, next < result.total ? next : null);
    }

    private static Map<String, JsonNode> index(JsonNode value, String section, String idField) {
        // Earlier snapshot schemas may omit a collection or store it as null.
        if (value.isMissingNode() || value.isNull()) return Map.of();
        if (!value.isArray()) throw invalid("Stored revision " + section + " must be a list.");
        Map<String, JsonNode> indexed = new TreeMap<>();
        for (JsonNode entry : value) {
            JsonNode id = entry.path(idField);
            if (!entry.isObject() || !id.isTextual() || id.textValue().isBlank()) {
                throw invalid("Stored revision " + section + " needs a nonblank " + idField + " for each entry.");
            }
            if (indexed.putIfAbsent(id.textValue(), entry) != null) {
                throw invalid("Stored revision " + section + " contains duplicate " + idField + " values.");
            }
        }
        return indexed;
    }

    private static void visit(String section, String id, String path, JsonNode before, JsonNode after, Collector result) {
        if (before.isNumber() && after.isNumber()) {
            if (before.decimalValue().compareTo(after.decimalValue()) != 0) result.add(section, id, path, before, after);
        } else if (before.isObject() && after.isObject()) {
            for (String field : fields(before, after)) {
                visit(section, id, path + "/" + field.replace("~", "~0").replace("/", "~1"), before.path(field), after.path(field), result);
            }
        } else if (before.isArray() && after.isArray()) {
            for (int index = 0; index < Math.max(before.size(), after.size()); index++) {
                visit(section, id, path + "/" + index, before.path(index), after.path(index), result);
            }
        } else if (!before.equals(after)) result.add(section, id, path, before, after);
    }

    private static TreeSet<String> fields(JsonNode before, JsonNode after) {
        TreeSet<String> fields = new TreeSet<>();
        before.fieldNames().forEachRemaining(fields::add);
        after.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }

    private static final class Collector {
        private final int offset;
        private final int limit;
        private final List<Change> rows = new ArrayList<>();
        private int total;

        private Collector(int offset, int limit) { this.offset = offset; this.limit = limit; }

        private void add(String section, String id, String path, JsonNode before, JsonNode after) {
            if (total >= offset && rows.size() < limit) {
                String kind = before.isMissingNode() ? "added" : after.isMissingNode() ? "removed" : "modified";
                rows.add(new Change(section, id, path, kind, copy(before), copy(after)));
            }
            total++;
        }

        private static JsonNode copy(JsonNode value) { return value.isMissingNode() ? NullNode.getInstance() : value.deepCopy(); }
    }
}
