package org.myweb.flowmat.domain.purchasing.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Keeps existing Long input formats while refusing fractional versions. */
public final class VersionDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
            try {
                return parser.getDecimalValue().longValueExact();
            } catch (ArithmeticException error) {
                return (Long) context.handleWeirdNumberValue(Long.class, parser.getDecimalValue(),
                    "expectedVersion must be a whole number.");
            }
        }
        return context.readValue(parser, Long.class);
    }
}
