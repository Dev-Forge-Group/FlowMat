package org.myweb.flowmat.domain.notification.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Turns one topic on or off for the current person in a project (docs/domain/notification.md NS2).
 *
 * @param daysAhead work_order_due only: days before the planned end, 0 to 30 (3 when left out)
 */
public record NotificationSubscriptionRequest(
    String projectId,
    String topic,
    @JsonDeserialize(using = BooleanChoice.class) Boolean subscribed,
    @JsonDeserialize(using = WholeDays.class) Integer daysAhead
) {
    /** This endpoint accepts JSON true/false; numeric and textual coercion must not change a choice. */
    public static final class BooleanChoice extends StdDeserializer<Boolean> {
        public BooleanChoice() { super(Boolean.class); }

        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) return true;
            if (parser.hasToken(JsonToken.VALUE_FALSE)) return false;
            throw JsonMappingException.from(parser, "subscribed must be true or false.");
        }
    }

    /** Keep whole numeric values exact; range validation remains in the service. */
    public static final class WholeDays extends StdDeserializer<Integer> {
        public WholeDays() { super(Integer.class); }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.currentToken().isNumeric()) {
                throw JsonMappingException.from(parser, "daysAhead must be whole days.");
            }
            try {
                return parser.getDecimalValue().intValueExact();
            } catch (ArithmeticException invalid) {
                throw JsonMappingException.from(parser, "daysAhead must be whole days within integer range.", invalid);
            }
        }
    }
}
