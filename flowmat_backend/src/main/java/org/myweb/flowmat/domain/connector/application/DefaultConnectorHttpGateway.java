package org.myweb.flowmat.domain.connector.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpGateway;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Component;

/** Resolves only deployment-registered credentials and returns no remote data on any failure. */
@Component
public class DefaultConnectorHttpGateway implements ConnectorHttpGateway {
    private static final int MAX_BYTES = ConnectorHttpTransport.MAX_BYTES;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
            .maxNumberLength(1000).maxStringLength(MAX_BYTES).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    private static final ObjectReader READER = JSON.reader()
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ConnectorTargetPolicy policy;
    private final ConnectorCredentials credentials;
    private final ConnectorHttpTransport transport;
    private final ConnectorHttpProperties properties;

    public DefaultConnectorHttpGateway(ConnectorTargetPolicy policy, ConnectorCredentials credentials,
                                      ConnectorHttpTransport transport, ConnectorHttpProperties properties) {
        this.policy = policy;
        this.credentials = credentials;
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public ConnectorHttpOutcome execute(ConnectorProfileOperation operation, JsonNode requestBody) {
        if (!properties.isEnabled()) return failure("TRANSPORT_FAILED", null, "CONNECTOR_DISABLED", false);
        if (operation == null || !METHODS.contains(operation.method())) {
            return failure("TRANSPORT_FAILED", null, "INVALID_REQUEST", false);
        }
        URI target;
        ConnectorCredentials.Resolved resolved;
        byte[] body;
        try {
            target = policy.target(operation.projectId(), operation.baseOrigin(), operation.relativePath(), operation.credentialAlias());
            if ("GET".equals(operation.method())) {
                if (requestBody != null && !requestBody.isNull()) throw new IllegalArgumentException();
                body = null;
            } else {
                JsonNode source = requestBody == null ? NullNode.getInstance() : requestBody;
                requireTree(source, 1, new int[] {0});
                BoundedJsonOutput output = new BoundedJsonOutput();
                JSON.writeValue(output, source);
                body = output.bytes();
            }
        } catch (BusinessException exception) {
            return failure("TRANSPORT_FAILED", null, "POLICY_REJECTED", false);
        } catch (IOException | RuntimeException exception) {
            return failure("TRANSPORT_FAILED", null, "INVALID_REQUEST", false);
        }
        try {
            // Resolved exactly once; aliases never become caller-selected environment variable names.
            resolved = credentials.resolve(operation.projectId(), operation.credentialAlias());
        } catch (RuntimeException exception) {
            return failure("TRANSPORT_FAILED", null, "CREDENTIAL_UNAVAILABLE", false);
        }
        ConnectorHttpTransport.TransportResponse remote;
        try {
            var request = new ConnectorHttpTransport.TransportRequest(target, operation.method(), resolved.headers(), body, () -> {
                policy.validateProfile(operation.projectId(), operation.baseOrigin(), operation.credentialAlias());
                try {
                    InetAddress[] approved = policy.validateAddresses(target, InetAddress.getAllByName(target.getHost()));
                    return approved[0];
                } catch (UnknownHostException exception) {
                    throw new ConnectorHttpTransport.TransportFailure("DNS_FAILURE", false, null);
                }
            });
            remote = transport.exchange(request);
        } catch (ConnectorHttpTransport.TransportFailure failure) {
            return failure(failure.mayHaveBeenSent() && failure.httpStatus() == null ? "OUTCOME_UNKNOWN" : "TRANSPORT_FAILED",
                failure.httpStatus(), failure.code(), failure.mayHaveBeenSent());
        } catch (RuntimeException exception) {
            // An unexpected transport failure is conservatively treated as having crossed the send boundary.
            return failure("OUTCOME_UNKNOWN", null, "IO_FAILURE", true);
        }
        if (!operation.successStatuses().contains(remote.status())) {
            return failure("HTTP_REJECTED", remote.status(), "HTTP_STATUS_REJECTED", true);
        }
        try {
            byte[] bytes = remote.body();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
            JsonNode response;
            if (bytes.length == 0) {
                response = NullNode.getInstance();
            } else {
                requireJsonContentType(remote.contentType());
                // Check both the wire representation and decoded text, including escaped property names.
                if (containsSecret(new String(bytes, StandardCharsets.UTF_8), resolved.forbiddenValues())) {
                    return failure("RESPONSE_REJECTED", remote.status(), "CREDENTIAL_ECHO", true);
                }
                response = READER.readTree(bytes);
                requireTree(response, 1, new int[] {0});
                if (echoesCredential(response, resolved.forbiddenValues())) {
                    return failure("RESPONSE_REJECTED", remote.status(), "CREDENTIAL_ECHO", true);
                }
            }
            return new ConnectorHttpOutcome("SUCCEEDED", remote.status(), response, null, true);
        } catch (IOException | RuntimeException exception) {
            return failure("RESPONSE_REJECTED", remote.status(), "INVALID_RESPONSE", true);
        }
    }

    private static void requireJsonContentType(String contentType) {
        if (contentType == null) throw new IllegalArgumentException();
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.equals("application/json")
            && !(mediaType.startsWith("application/") && mediaType.endsWith("+json"))) throw new IllegalArgumentException();
    }

    private static void requireTree(JsonNode node, int depth, int[] count) {
        if (node == null || depth > 32 || ++count[0] > 5000) throw new IllegalArgumentException();
        if (node.isContainerNode()) {
            if (node.isObject()) node.fieldNames().forEachRemaining(field -> StorableText.require(field, "response"));
            for (JsonNode child : node) requireTree(child, depth + 1, count);
        } else if (node.isTextual()) {
            StorableText.require(node.textValue(), "response");
        } else if (node.isNumber()) {
            Number number = node.numberValue();
            if (number instanceof Double value && !Double.isFinite(value)
                || number instanceof Float floatValue && !Float.isFinite(floatValue)
                || Math.abs((long) node.decimalValue().scale()) > 1000) throw new IllegalArgumentException();
        } else if (!node.isNull() && !node.isBoolean()) throw new IllegalArgumentException();
    }

    private static boolean echoesCredential(JsonNode node, Set<String> forbidden) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (containsSecret(field.getKey(), forbidden) || echoesCredential(field.getValue(), forbidden)) return true;
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) if (echoesCredential(child, forbidden)) return true;
            return false;
        }
        return containsSecret(node.asText(), forbidden);
    }

    private static boolean containsSecret(String value, Set<String> forbidden) {
        for (String secret : forbidden) if (!secret.isEmpty() && value.contains(secret)) return true;
        return false;
    }

    private static ConnectorHttpOutcome failure(String status, Integer httpStatus, String code, boolean sent) {
        return new ConnectorHttpOutcome(status, httpStatus, null, code, sent);
    }

    private static final class BoundedJsonOutput extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void write(int value) throws IOException {
            if (output.size() == MAX_BYTES) throw new IOException();
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > MAX_BYTES - output.size()) throw new IOException();
            output.write(bytes, offset, length);
        }

        byte[] bytes() { return output.toByteArray(); }
    }
}
