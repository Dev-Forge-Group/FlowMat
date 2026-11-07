package org.myweb.flowmat.domain.production.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * The people a work order needs, all at once (docs/domain/worker-calendar.md WC12, WC36): {@code userIds} names them for all
 * of their time in the window, {@code members} gives each one's share of it. Send one or the other; an empty list names
 * nobody.
 */
public record WorkOrderCrewRequest(List<String> userIds, List<Member> members) {

    /** @param sharePercent the part of their working time in the window the order needs, 1 to 100 (100 when left out) */
    public record Member(String userId, @JsonDeserialize(using = WholePercent.class) Integer sharePercent) {
    }

    /** A share is a whole JSON number; 50.5 or "50" is refused instead of rounded or read as text. */
    public static final class WholePercent extends StdDeserializer<Integer> {
        public WholePercent() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.currentToken().isNumeric()) {
                throw JsonMappingException.from(parser, "sharePercent must be a whole number.");
            }
            try {
                return parser.getDecimalValue().intValueExact();
            } catch (ArithmeticException invalid) {
                throw JsonMappingException.from(parser, "sharePercent must be a whole number.", invalid);
            }
        }
    }
}
