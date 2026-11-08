package org.myweb.flowmat.global.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Reads a whole number exactly: 30 and 30.0 stay, 2.5 is refused instead of becoming 2, and an integer string such as "30"
 * is taken as Jackson took it before. Callers check the range; a subclass names the field in its message.
 */
public class WholeNumberDeserializer extends StdDeserializer<Integer> {
    public static final String MESSAGE = "Send a whole number.";

    private final String message;

    public WholeNumberDeserializer() {
        this(MESSAGE);
    }

    protected WholeNumberDeserializer(String message) {
        super(Integer.class);
        this.message = message;
    }

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            if (parser.currentToken().isNumeric()) {
                return parser.getDecimalValue().intValueExact();
            }
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String value = parser.getText().trim();
                return value.isEmpty() ? null : Integer.valueOf(value);
            }
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw JsonMappingException.from(parser, message, invalid);
        }
        throw JsonMappingException.from(parser, message);
    }
}
