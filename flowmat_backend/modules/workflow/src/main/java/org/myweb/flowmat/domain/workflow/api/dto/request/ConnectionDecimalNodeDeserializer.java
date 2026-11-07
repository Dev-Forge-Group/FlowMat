package org.myweb.flowmat.domain.workflow.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Keeps decimal precision and distinguishes an omitted connection field from explicit null. */
public final class ConnectionDecimalNodeDeserializer extends JsonDeserializer<JsonNode> {

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken().isNumeric()) {
            return context.getNodeFactory().numberNode(parser.getDecimalValue());
        }
        // Preserve nonnumeric types so the service can return the field-specific validation message.
        return parser.readValueAsTree();
    }

    @Override
    public JsonNode getNullValue(DeserializationContext context) {
        return context.getNodeFactory().nullNode();
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
