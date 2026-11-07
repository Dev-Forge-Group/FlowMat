package org.myweb.flowmat.domain.inventory.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * A project's inventory settings, sent whole (docs/domain/storage-location.md SL6).
 *
 * @param storageConditionPolicy warn or refuse
 * @param expectedVersion        the version the form was opened at; empty when the project had none
 */
public record InventorySettingRequest(String projectId, String storageConditionPolicy,
                                      @JsonDeserialize(using = VersionDeserializer.class) Long expectedVersion) {
    public static final class VersionDeserializer extends JsonDeserializer<Long> {
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
}
