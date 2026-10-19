package org.myweb.flowmat.global.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads a snapshot's decimal numbers without first rounding them to binary floating point. A number whose exponent passes
 * Jackson's own scale limit, such as 1e100000000, is refused: kept exact, conditions and totals would compute with it for
 * minutes (error agent report, 2026-10-18).
 */
public final class ExactJsonNodeDeserializer extends StdDeserializer<JsonNode> {
    public ExactJsonNodeDeserializer() {
        super(JsonNode.class);
    }

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode root = ((ObjectMapper) parser.getCodec()).reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(parser);
        Deque<JsonNode> pending = new ArrayDeque<>();
        if (root != null) {
            pending.push(root);
        }
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isFloatingPointNumber()) {
                int scale = node.decimalValue().scale();
                // Integer.MIN_VALUE has no absolute value, so the limit check below cannot judge it.
                if (scale == Integer.MIN_VALUE) {
                    throw JsonMappingException.from(parser, "Snapshot number scale exceeds the safe JSON conversion limit.");
                }
                parser.streamReadConstraints().validateBigIntegerScale(scale);
            } else if (node.isContainerNode()) {
                node.elements().forEachRemaining(pending::push);
            }
        }
        return root;
    }
}
