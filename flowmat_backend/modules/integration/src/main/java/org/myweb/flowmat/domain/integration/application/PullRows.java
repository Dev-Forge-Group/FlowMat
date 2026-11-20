package org.myweb.flowmat.domain.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorMappingQuery;
import org.myweb.flowmat.global.exception.BusinessException;

/**
 * The records a pull job takes from a response: the array at {@code rowsAt}, each element turned by the {@code row}
 * mapping into the import's named fields (ADR-038 decision 3). A record the mapping cannot turn, or whose field is not one
 * value, is kept in its place with the reason, so the numbering stays the source's.
 */
final class PullRows {

    static final int MAX_ROWS = 500;

    record Result(List<Map<String, String>> rows, Map<Integer, String> problems, List<String> keys) {
    }

    private PullRows() {
    }

    /** Null and the reason when the response has no array where the job says. */
    static Result read(JsonNode response, JsonNode mapping, ConnectorMappingQuery mappings, String keyField) {
        JsonNode records = response == null ? null : response.at(mapping.path("rowsAt").asText());
        if (records == null || !records.isArray()) {
            throw new PullProblem("The response has no list of records at " + displayPointer(mapping.path("rowsAt").asText()) + ".");
        }
        if (records.size() > MAX_ROWS) {
            throw new PullProblem("The response has " + records.size() + " records; a run takes at most " + MAX_ROWS + ".");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        Map<Integer, String> problems = new TreeMap<>();
        List<String> keys = new ArrayList<>();
        int number = 0;
        for (JsonNode record : records) {
            number++;
            Map<String, String> row = new LinkedHashMap<>();
            try {
                JsonNode mapped = mappings.map(record, mapping.get("row"));
                var fields = mapped.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    JsonNode value = field.getValue();
                    if (value.isContainerNode()) throw new PullProblem(field.getKey() + " is not a single value");
                    row.put(field.getKey(), value.isNull() ? null : value.isNumber() ? value.decimalValue().toPlainString() : value.asText());
                }
            } catch (BusinessException | PullProblem exception) {
                problems.put(number, exception instanceof PullProblem problem ? problem.getMessage()
                    : "The mapping does not fit this record");
                row.clear();
            }
            rows.add(row);
            keys.add(row.get(keyField));
        }
        return new Result(rows, problems, keys);
    }

    private static String displayPointer(String pointer) {
        return pointer.isEmpty() ? "the top of the response" : pointer;
    }

    /** A problem with the response or one record, said to the person who runs the job. */
    static final class PullProblem extends RuntimeException {
        PullProblem(String message) {
            super(message, null, false, false);
        }
    }
}
