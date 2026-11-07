package org.myweb.flowmat.domain.connector.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/** A bounded JSON transformation; selections are copied only after the entire output fits the preview budgets. */
@Component
final class JsonMappingEngine {
    private static final int MAX_TREE_DEPTH = 32;
    private static final int MAX_TREE_NODES = 5000;
    private static final int MAX_EXPRESSION_DEPTH = 16;
    private static final int MAX_EXPRESSIONS = 256;
    private static final int MAX_FIELD_LENGTH = 128;
    private static final int MAX_POINTER_LENGTH = 512;
    private static final int MAX_POINTER_SEGMENTS = 32;
    private static final int MAX_OUTPUT_BYTES = 131072;
    private static final long MAX_NUMBER_SCALE = 1000;

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    JsonMappingEngine(ObjectMapper mapper) {
        this.mapper = mapper;
        this.writer = mapper.writer();
    }

    void validate(JsonNode mapping) {
        requireDefinition(mapping, 1, new Counter());
    }

    JsonNode map(JsonNode source, JsonNode mapping) {
        requireTree(source, 1, new Counter());
        // Constructed objects contain references to selected trees here. Neither input is modified or copied yet.
        JsonNode result = evaluate(source, mapping, 1, new Counter());
        if (result.isMissingNode()) throw invalid();
        requireOutputNodes(result, new Counter());
        try {
            writer.writeValue(new BoundedOutputStream(), result);
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        }
        return result.deepCopy();
    }

    private JsonNode evaluate(JsonNode source, JsonNode expression, int depth, Counter expressions) {
        Operation operation = requireExpression(expression, depth, expressions);
        if (operation == Operation.FROM) {
            JsonNode selected = source.at(expression.get("from").textValue());
            if (selected.isMissingNode() && (!expression.has("required") || expression.get("required").booleanValue())) {
                throw invalid();
            }
            return selected;
        }
        if (operation == Operation.LITERAL) return expression.get("literal");

        ObjectNode result = mapper.createObjectNode();
        var entries = expression.get("object").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            requireField(entry.getKey());
            JsonNode value = evaluate(source, entry.getValue(), depth + 1, expressions);
            if (!value.isMissingNode()) result.set(entry.getKey(), value);
        }
        return result;
    }

    private static void requireDefinition(JsonNode expression, int depth, Counter expressions) {
        if (requireExpression(expression, depth, expressions) != Operation.OBJECT) return;
        var entries = expression.get("object").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            requireField(entry.getKey());
            requireDefinition(entry.getValue(), depth + 1, expressions);
        }
    }

    private static Operation requireExpression(JsonNode expression, int depth, Counter expressions) {
        if (depth > MAX_EXPRESSION_DEPTH || ++expressions.used > MAX_EXPRESSIONS
            || expression == null || !expression.isObject()) throw invalid();
        boolean from = expression.has("from");
        boolean literal = expression.has("literal");
        boolean object = expression.has("object");
        if ((from ? 1 : 0) + (literal ? 1 : 0) + (object ? 1 : 0) != 1) throw invalid();

        if (from) {
            boolean hasRequired = expression.has("required");
            if (expression.size() != (hasRequired ? 2 : 1) || !expression.get("from").isTextual()
                || hasRequired && !expression.get("required").isBoolean()) throw invalid();
            requirePointer(expression.get("from").textValue());
            return Operation.FROM;
        }
        if (expression.size() != 1) throw invalid();
        if (literal) {
            requireTree(expression.get("literal"), 1, new Counter());
            return Operation.LITERAL;
        }
        if (!expression.get("object").isObject()) throw invalid();
        return Operation.OBJECT;
    }

    private static void requireField(String field) {
        if (field.length() > MAX_FIELD_LENGTH) throw invalid();
    }

    private static void requirePointer(String pointer) {
        if (pointer.length() > MAX_POINTER_LENGTH || !pointer.isEmpty() && pointer.charAt(0) != '/') throw invalid();
        int segments = 0;
        for (int index = 0; index < pointer.length(); index++) {
            char character = pointer.charAt(index);
            if (character == '/' && ++segments > MAX_POINTER_SEGMENTS) throw invalid();
            if (character == '~') {
                if (++index == pointer.length() || pointer.charAt(index) != '0' && pointer.charAt(index) != '1') {
                    throw invalid();
                }
            }
        }
    }

    private static void requireTree(JsonNode node, int depth, Counter nodes) {
        if (node == null || depth > MAX_TREE_DEPTH || ++nodes.used > MAX_TREE_NODES) throw invalid();
        if (node.isContainerNode()) {
            for (JsonNode child : node) requireTree(child, depth + 1, nodes);
        } else if (node.isNumber()) {
            Number number = node.numberValue();
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || number instanceof Float floatValue && !Float.isFinite(floatValue)
                || Math.abs((long) node.decimalValue().scale()) > MAX_NUMBER_SCALE) throw invalid();
        } else if (!node.isNull() && !node.isTextual() && !node.isBoolean()) {
            throw invalid();
        }
    }

    private static void requireOutputNodes(JsonNode node, Counter nodes) {
        if (++nodes.used > MAX_TREE_NODES) throw invalid();
        // Source/literal trees and expression nesting already bound this walk's depth.
        if (node.isContainerNode()) {
            for (JsonNode child : node) requireOutputNodes(child, nodes);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "source or mapping is invalid or exceeds the preview limits.");
    }

    private enum Operation { FROM, LITERAL, OBJECT }

    private static final class Counter {
        private int used;
    }

    /** Counts the serializer's actual UTF-8 bytes without retaining its output. */
    private static final class BoundedOutputStream extends OutputStream {
        private int written;

        @Override
        public void write(int value) throws IOException {
            if (written == MAX_OUTPUT_BYTES) throw new IOException();
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > MAX_OUTPUT_BYTES - written) throw new IOException();
            written += length;
        }
    }
}
