package org.myweb.flowmat.domain.workflow.api.dto.request;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.io.IOException;
import java.io.InputStream;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;

/** A draft binding. All optional fields null clear it without resetting its version. */
public record ProcessExecutionBindingRequest(
    @JsonDeserialize(using = Text.class) String profileVersionId,
    @JsonDeserialize(using = Text.class) String operationId,
    @JsonDeserialize(using = Text.class) String outputPortId,
    @JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode requestMapping,
    @JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode responseMapping,
    @JsonDeserialize(using = Version.class) Long expectedVersion
) {
    private static final int MAX_BODY_BYTES = 131072;
    private static final ObjectReader READER = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(96).maxNumberLength(1000)
            .maxStringLength(MAX_BODY_BYTES).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
        .readerFor(ProcessExecutionBindingRequest.class)
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Called only after authorization, outside a database transaction. */
    public static ProcessExecutionBindingRequest read(InputStream input) {
        if (input == null) throw invalid("body is required.");
        try {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw invalid("body must not exceed 131072 bytes.");
            ProcessExecutionBindingRequest body = READER.readValue(bytes);
            if (body == null) throw invalid("body must be a JSON object.");
            return body;
        } catch (IOException | IllegalArgumentException exception) {
            // Parser errors may contain caller-supplied values; do not return their messages.
            throw invalid("body must be valid JSON with known fields, no duplicate keys and an integer expectedVersion.");
        }
    }

    public static final class Text extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }

    public static final class Version extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            long value = parser.getLongValue();
            if (value < 0) return (Long) context.handleWeirdNumberValue(Long.class, value,
                "expectedVersion must be a nonnegative integer.");
            return value;
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
