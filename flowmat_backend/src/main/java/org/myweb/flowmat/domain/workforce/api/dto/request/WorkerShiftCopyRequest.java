package org.myweb.flowmat.domain.workforce.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * Gives other workers one worker's weekly shifts and rota (docs/domain/worker-calendar.md WC51). {@code offsetWeeks}
 * puts a worker that many weeks further on in the rota: while the one copied from works week 1, they work week
 * 1 + offsetWeeks. 0 when left out. {@code effectiveFrom} is the date the copies apply from, today when left out
 * (docs/architecture/adr/ADR-013-rota-history.md); {@code reason} goes on every copy (docs/architecture/adr/ADR-017-version-reasons.md).
 */
public record WorkerShiftCopyRequest(List<Target> targets, String effectiveFrom, String reason) {

    /** The copies from today, as callers sent them before V93. */
    public WorkerShiftCopyRequest(List<Target> targets) {
        this(targets, null, null);
    }

    /** The copies without a reason, as callers sent them before V95. */
    public WorkerShiftCopyRequest(List<Target> targets, String effectiveFrom) {
        this(targets, effectiveFrom, null);
    }

    public record Target(String userId, @JsonDeserialize(using = WholeWeeks.class) Integer offsetWeeks) {
    }

    /** Read the offset exactly; the service checks its range. */
    public static final class WholeWeeks extends StdDeserializer<Integer> {
        private static final String MESSAGE = "offsetWeeks is a whole number of weeks.";

        public WholeWeeks() {
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
