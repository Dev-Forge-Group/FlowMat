package org.myweb.flowmat.domain.workforce.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * The shifts that start on one date in place of the worker's weekly ones (docs/domain/worker-calendar.md WC29-WC31); none or
 * an empty list means they do not work that day. Times are local to the planning time zone, such as "08:30"; a shift that
 * ends at or before its start runs past midnight. {@code throughDate} (YYYY-MM-DD) gives every date up to it the same shifts;
 * {@code weekDays} (ISO, 1 = Monday) keeps only the dates on those days of the week, all of them when empty.
 */
public record WorkerDayRequest(List<Shift> shifts, String reason, String throughDate,
                               @JsonDeserialize(contentUsing = WholeDay.class) List<Integer> weekDays) {

    public record Shift(String shiftStart, String shiftEnd) {
    }

    /** Read ISO weekday filters exactly; the service still checks their range. */
    public static final class WholeDay extends StdDeserializer<Integer> {
        private static final String MESSAGE = "weekDays are 1 (Monday) to 7 (Sunday).";

        public WholeDay() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.currentToken().isNumeric()) {
                throw JsonMappingException.from(parser, MESSAGE);
            }
            try {
                return parser.getDecimalValue().intValueExact();
            } catch (ArithmeticException invalid) {
                throw JsonMappingException.from(parser, MESSAGE, invalid);
            }
        }
    }
}
