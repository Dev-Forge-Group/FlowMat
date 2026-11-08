package org.myweb.flowmat.domain.workflow.api.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import org.myweb.flowmat.global.util.StrictBooleanDeserializer;

public record ProcessConnectionCreateRequest(
    @NotBlank String workflowId,
    @NotBlank String fromProcessId,
    @NotBlank String toProcessId,
    String fromIoId,
    String toIoId,
    String itemId,
    @Size(max = 50, message = "sourceHandle must be at most 50 characters.") String sourceHandle,
    @Size(max = 50, message = "targetHandle must be at most 50 characters.") String targetHandle,
    @Size(max = 30, message = "connectionType must be at most 30 characters.") String connectionType,
    @Size(max = 100, message = "connectionLabel must be at most 100 characters.") String connectionLabel,
    @Digits(integer = 10, fraction = 4, message = "flowRate must fit numeric(14,4).") BigDecimal flowRate,
    @Size(max = 20, message = "unit must be at most 20 characters.") String unit,
    @Digits(integer = 8, fraction = 2, message = "delayTimeSec must fit numeric(10,2).") BigDecimal delayTimeSec,
    @Digits(integer = 1, fraction = 4, message = "lossRate must fit numeric(5,4).") BigDecimal lossRate,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = PriorityDeserializer.class) Integer priority,
    @Size(max = 2000) String conditionExpr,
    @DecimalMin("0.0") BigDecimal capacity,
    String failurePolicy,
    /** An Exclusive gateway's default connection (ADR-006); false when left out. */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean defaultRoute,
    /** A Split gateway's share on this connection (ADR-008): share (a percent), fixed or rest; none when left out. */
    String splitKind,
    BigDecimal splitValue,
    /** elapsed (when left out) or working: what the delay counts in production runs (ADR-022). */
    String delayBasis
) {
    public static final class PriorityDeserializer extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                try {
                    return parser.getDecimalValue().intValueExact();
                } catch (ArithmeticException error) {
                    return (Integer) context.handleWeirdNumberValue(Integer.class, parser.getDecimalValue(),
                        "priority must be an integer.");
                }
            }
            return context.readValue(parser, Integer.class);
        }
    }
}
