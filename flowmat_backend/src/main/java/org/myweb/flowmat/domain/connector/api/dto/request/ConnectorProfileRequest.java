package org.myweb.flowmat.domain.connector.api.dto.request;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.StorableText;

/** Owner-approved static operations. Credentials and caller-supplied headers are deliberately not request fields. */
public record ConnectorProfileRequest(Long expectedVersion, String name, String baseOrigin, String credentialAlias,
                                      Boolean enabled, List<Operation> operations) {
    private static final Set<String> CREATE_FIELDS = Set.of("name", "baseOrigin", "credentialAlias", "enabled", "operations");
    private static final Set<String> UPDATE_FIELDS = Set.of("expectedVersion", "name", "baseOrigin", "credentialAlias", "enabled", "operations");
    private static final Set<String> OPERATION_FIELDS = Set.of("operationId", "method", "relativePath", "successStatuses");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final ObjectReader READER = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxNumberLength(32)
            .maxStringLength(131072).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Operation(String operationId, String method, String relativePath, Set<Integer> successStatuses) {
        public Operation {
            if (successStatuses != null) successStatuses = Set.copyOf(successStatuses);
        }

        @Override
        public String toString() { return "ConnectorOperation[operationId=" + operationId + ", method=" + method + "]"; }
    }

    public ConnectorProfileRequest {
        if (operations != null) operations = List.copyOf(operations);
    }

    @Override
    public String toString() { return "ConnectorProfileRequest[operationCount=" + (operations == null ? 0 : operations.size()) + "]"; }

    /** The controller authorizes the owner before invoking this bounded stream reader. */
    public static ConnectorProfileRequest read(InputStream input, boolean update) {
        JsonNode body = readObject(input);
        fields(body, update ? UPDATE_FIELDS : CREATE_FIELDS);
        Long version = update ? wholeVersion(body.get("expectedVersion")) : null;
        List<Operation> operations = new ArrayList<>();
        JsonNode array = body.path("operations");
        if (!array.isArray() || array.isEmpty() || array.size() > 64) throw bad("operations must contain between 1 and 64 operations.");
        for (JsonNode entry : array) {
            fields(entry, OPERATION_FIELDS);
            JsonNode statuses = entry.path("successStatuses");
            if (!statuses.isArray() || statuses.isEmpty() || statuses.size() > 100) {
                throw bad("successStatuses must contain explicit HTTP success statuses.");
            }
            Set<Integer> codes = new LinkedHashSet<>();
            for (JsonNode status : statuses) {
                if (!status.isIntegralNumber() || !status.canConvertToInt()) throw bad("successStatuses must contain integers from 200 to 299.");
                codes.add(status.intValue());
            }
            operations.add(new Operation(text(entry, "operationId", false), text(entry, "method", false),
                text(entry, "relativePath", false), codes));
        }
        JsonNode enabled = body.path("enabled");
        if (!enabled.isBoolean()) throw bad("enabled must be true or false.");
        return new ConnectorProfileRequest(version, text(body, "name", false), text(body, "baseOrigin", false),
            text(body, "credentialAlias", true), enabled.booleanValue(), operations).validated(update);
    }

    public static Status readStatus(InputStream input) {
        JsonNode body = readObject(input);
        fields(body, Set.of("expectedVersion", "enabled"));
        JsonNode enabled = body.path("enabled");
        if (!enabled.isBoolean()) throw bad("enabled must be true or false.");
        return new Status(wholeVersion(body.get("expectedVersion")), enabled.booleanValue());
    }

    public record Status(long expectedVersion, boolean enabled) { }

    public static long queryVersion(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw bad("expectedVersion is required, a nonnegative integer.");
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw bad("expectedVersion is required, a nonnegative integer."); }
    }

    /** Used by the service as well, so internal callers cannot bypass the approved-operation contract. */
    public ConnectorProfileRequest validated(boolean update) {
        if (update && (expectedVersion == null || expectedVersion < 0)) throw bad("expectedVersion is required, a nonnegative integer.");
        if (!update && expectedVersion != null) throw bad("expectedVersion does not apply to profile creation.");
        StorableText.require(name, "name");
        if (name == null || name.isBlank() || name.trim().length() > 120) throw bad("name is required and must not exceed 120 characters.");
        StorableText.require(baseOrigin, "baseOrigin");
        if (baseOrigin == null || baseOrigin.isBlank() || baseOrigin.length() > 512) throw bad("baseOrigin is required and must not exceed 512 characters.");
        if (credentialAlias != null && !credentialAlias.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) {
            throw bad("credentialAlias must be a registered logical alias of at most 100 characters.");
        }
        if (enabled == null) throw bad("enabled must be true or false.");
        if (operations == null || operations.isEmpty() || operations.size() > 64) throw bad("operations must contain between 1 and 64 operations.");
        Set<String> seen = new HashSet<>();
        for (Operation operation : operations) {
            if (operation == null || operation.operationId() == null || !operation.operationId().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw bad("operationId must be an identifier of at most 64 characters.");
            }
            if (!seen.add(operation.operationId())) throw bad("operationId must be unique within the profile.");
            if (!METHODS.contains(operation.method() == null ? "" : operation.method())) throw bad("method must be GET, POST, PUT, PATCH or DELETE.");
            requirePath(operation.relativePath());
            if (operation.successStatuses() == null || operation.successStatuses().isEmpty()
                || operation.successStatuses().stream().anyMatch(code -> code == null || code < 200 || code > 299)) {
                throw bad("successStatuses must contain integers from 200 to 299.");
            }
        }
        return new ConnectorProfileRequest(expectedVersion, name.trim(), baseOrigin, credentialAlias, enabled, operations);
    }

    private static void requirePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512 || !path.startsWith("/") || path.contains("//")
            || path.indexOf('\\') >= 0 || path.indexOf('%') >= 0 || path.indexOf('{') >= 0 || path.indexOf('}') >= 0
            || path.indexOf('$') >= 0 || path.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw bad("relativePath must be a static absolute path without escapes, interpolation or an alternate target.");
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) throw bad("relativePath must not contain dot segments.");
        }
        try {
            URI uri = new URI(path);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw bad("relativePath must not contain an origin, query or fragment.");
            }
        } catch (URISyntaxException exception) { throw bad("relativePath must be a valid static path."); }
    }

    private static String text(JsonNode object, String name, boolean nullable) {
        JsonNode field = object.get(name);
        if (nullable && (field == null || field.isNull())) return null;
        if (field == null || !field.isTextual()) throw bad(name + " must be text.");
        return field.textValue();
    }

    private static long wholeVersion(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw bad("expectedVersion is required, a nonnegative integer.");
        }
        return value.longValue();
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        if (!object.isObject() || !allowed.containsAll(object.propertyStream().map(java.util.Map.Entry::getKey).toList())) {
            throw bad("body contains unsupported fields or an invalid object.");
        }
    }

    private static JsonNode readObject(InputStream input) {
        if (input == null) throw bad("body is required.");
        try {
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072) throw bad("body must not exceed 131072 bytes.");
            JsonNode node = READER.readTree(bytes);
            if (node == null || !node.isObject()) throw bad("body must be a JSON object.");
            return node;
        } catch (IOException exception) {
            throw bad("body must be valid bounded JSON without duplicate keys.");
        }
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
