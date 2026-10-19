package org.myweb.flowmat.domain.workforce.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * A worker's weekly shifts, all at once (docs/domain/worker-calendar.md WC2); an empty list clears them. With {@code rota}
 * they repeat every few weeks and each shift says its week (WC44); without it they are one week, as before.
 * {@code effectiveFrom} is the date they apply from, today when left out (docs/architecture/adr/ADR-013-rota-history.md);
 * {@code reason} says why, at most 200 characters (docs/architecture/adr/ADR-017-version-reasons.md).
 */
public record WorkerShiftsRequest(List<Shift> shifts, Rota rota, String effectiveFrom, String reason) {

    /** The shifts without a rota, as callers sent them before V87. */
    public WorkerShiftsRequest(List<Shift> shifts) {
        this(shifts, null, null, null);
    }

    /** The shifts from today, as callers sent them before V93. */
    public WorkerShiftsRequest(List<Shift> shifts, Rota rota) {
        this(shifts, rota, null, null);
    }

    /** The shifts without a reason, as callers sent them before V95. */
    public WorkerShiftsRequest(List<Shift> shifts, Rota rota, String effectiveFrom) {
        this(shifts, rota, effectiveFrom, null);
    }

    /**
     * @param workDays ISO days of the week the shift starts on, 1 (Monday) to 7 (Sunday)
     * @param rotaWeek the week of the rota it runs in, 1 when left out (WC44)
     */
    public record Shift(String shiftStart, String shiftEnd,
                        @JsonDeserialize(contentUsing = WholeWeekday.class) List<Integer> workDays,
                        @JsonDeserialize(using = WholeNumber.class) Integer rotaWeek) {

        public Shift(String shiftStart, String shiftEnd, List<Integer> workDays) {
            this(shiftStart, shiftEnd, workDays, null);
        }
    }

    /** The shifts repeat every {@code weeks} weeks (2 to 8); week 1 is the week, Monday to Sunday, of {@code startsOn}. */
    public record Rota(@JsonDeserialize(using = WholeNumber.class) Integer weeks, String startsOn) {
    }

    /** Read a week number exactly; the service checks its range. */
    public static final class WholeNumber extends StdDeserializer<Integer> {
        private static final String MESSAGE = "rotaWeek and the rota's weeks are whole numbers.";

        public WholeNumber() {
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

    /** Read each weekday exactly; the service still checks the ISO range and missing values. */
    public static final class WholeWeekday extends StdDeserializer<Integer> {
        private static final String MESSAGE = "workDays are ISO days of the week, 1 (Monday) to 7 (Sunday).";

        public WholeWeekday() {
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
