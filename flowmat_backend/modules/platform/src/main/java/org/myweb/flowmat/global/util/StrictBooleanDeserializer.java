package org.myweb.flowmat.global.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Only JSON true or false; 1, 0 or "yes" are refused instead of read as a choice. */
public final class StrictBooleanDeserializer extends StdDeserializer<Boolean> {

    public StrictBooleanDeserializer() {
        super(Boolean.class);
    }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_TRUE)) {
            return true;
        }
        if (parser.hasToken(JsonToken.VALUE_FALSE)) {
            return false;
        }
        throw JsonMappingException.from(parser, "Send true or false.");
    }
}
