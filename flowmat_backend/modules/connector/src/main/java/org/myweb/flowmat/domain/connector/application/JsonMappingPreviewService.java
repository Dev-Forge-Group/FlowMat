package org.myweb.flowmat.domain.connector.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.connector.api.dto.response.JsonMappingPreviewResponse;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** Previews only caller-supplied JSON. It reads no profile, resolves no credential and performs no external operation. */
@Service
@RequiredArgsConstructor
public class JsonMappingPreviewService {
    private static final int MAX_REQUEST_BYTES = 131072;
    private static final Set<String> FIELDS = Set.of("version", "source", "mapping");
    // Expression wrappers and literal JSON have separate, smaller limits in the engine.
    private static final ObjectReader READER = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(96).maxNumberLength(1000)
            .maxStringLength(MAX_REQUEST_BYTES).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
        .reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ProjectAccess access;
    private final JsonMappingEngine engine;

    public JsonMappingPreviewResponse preview(String projectId, InputStream input) {
        // Authorization's own read transaction ends before reading a possibly slow request stream.
        access.requireProjectWriteAccess(projectId);
        JsonNode body = read(input);
        if (!body.isObject()) throw invalid("body must be a JSON object.");
        if (body.size() != 3 || !FIELDS.containsAll(body.propertyStream().map(java.util.Map.Entry::getKey).toList())) {
            throw invalid("body requires only version, source and mapping.");
        }
        JsonNode version = body.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            throw invalid("version must be the integer 1.");
        }
        if (!body.has("source")) throw invalid("source is required.");
        if (!body.has("mapping")) throw invalid("mapping is required.");
        return new JsonMappingPreviewResponse(1, engine.map(body.get("source"), body.get("mapping")));
    }

    private JsonNode read(InputStream input) {
        if (input == null) throw invalid("body is required.");
        byte[] bytes;
        try {
            bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
        } catch (IOException exception) {
            throw invalid("body could not be read as JSON.");
        }
        if (bytes.length > MAX_REQUEST_BYTES) throw invalid("body must not exceed 131072 bytes.");
        try {
            JsonNode body = READER.readTree(bytes);
            return body == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : body;
        } catch (IOException exception) {
            // Parser messages can include names and values from caller-supplied data.
            throw invalid("body must be valid JSON within its depth and numeric limits, without duplicate keys.");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
