package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;

/** Reads exact output numbers while retaining the finish API's explicit-null and omitted-field distinction. */
public final class FlowRunOutputPayloadDeserializer extends JsonDeserializer<JsonNode> {
    private final ExactJsonNodeDeserializer exact = new ExactJsonNodeDeserializer();

    @Override
    public JsonNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return exact.deserialize(parser, context);
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
