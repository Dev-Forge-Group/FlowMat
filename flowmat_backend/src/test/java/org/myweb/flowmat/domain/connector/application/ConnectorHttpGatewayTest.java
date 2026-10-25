package org.myweb.flowmat.domain.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;

class ConnectorHttpGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectorTargetPolicy policy = mock(ConnectorTargetPolicy.class);
    private final ConnectorCredentials credentials = mock(ConnectorCredentials.class);
    private final ConnectorHttpTransport transport = mock(ConnectorHttpTransport.class);
    private final ConnectorHttpProperties properties = new ConnectorHttpProperties();
    private DefaultConnectorHttpGateway gateway;

    @BeforeEach
    void configure() {
        properties.setEnabled(true);
        gateway = new DefaultConnectorHttpGateway(policy, credentials, transport, properties);
        when(policy.target("project", "https://api.example.test", "/operation", "registered"))
            .thenReturn(URI.create("https://api.example.test/operation"));
        when(credentials.resolve("project", "registered")).thenReturn(new ConnectorCredentials.Resolved(
            Map.of("Authorization", "Bearer private-token"), Set.of("private-token", "Bearer private-token")));
    }

    @Test
    void returnsStrictJsonWithDecimalScaleAndDateStringsPreserved() throws Exception {
        response(200, "{\"amount\":1.2500,\"integer\":900719925474099312345,\"date\":\"2026-10-23\"}", "application/json");
        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), mapper.readTree("{\"input\":1}"));

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.response().path("amount").decimalValue().scale()).isEqualTo(4);
        assertThat(outcome.response().path("integer").asText()).isEqualTo("900719925474099312345");
        assertThat(outcome.response().path("date").textValue()).isEqualTo("2026-10-23");
        assertThat(outcome.mayHaveBeenSent()).isTrue();
        ArgumentCaptor<ConnectorHttpTransport.TransportRequest> capture =
            ArgumentCaptor.forClass(ConnectorHttpTransport.TransportRequest.class);
        verify(transport).exchange(capture.capture());
        assertThat(capture.getValue().headers()).containsEntry("Authorization", "Bearer private-token");
        assertThat(new String(capture.getValue().body(), StandardCharsets.UTF_8)).isEqualTo("{\"input\":1}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{} {}", "{\"n\":1e1001}", "[", "null true"})
    void rejectsMalformedOrUnboundedRemoteJsonWithoutEchoingIt(String remoteBody) {
        response(200, remoteBody, "application/json");
        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());

        assertThat(outcome.status()).isEqualTo("RESPONSE_REJECTED");
        assertThat(outcome.response()).isNull();
        assertThat(outcome.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(outcome.toString()).doesNotContain("parser", "remoteBody");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"value\":\"\\u0000\"}", "{\"\\u0000\":true}",
        "{\"value\":\"\\ud800\"}", "{\"value\":\"\\udfff\"}",
        "{\"\\ud800\":true}", "{\"\\udfff\":true}"})
    void rejectsRemoteTextThatCannotBeStoredAsJsonb(String remoteBody) {
        response(200, remoteBody, "application/json");

        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());

        assertThat(outcome.status()).isEqualTo("RESPONSE_REJECTED");
        assertThat(outcome.errorCode()).isEqualTo("INVALID_RESPONSE");
        assertThat(outcome.response()).isNull();
        assertThat(outcome.mayHaveBeenSent()).isTrue();
    }

    @Test
    void preservesStorableUnicodePairsAndEscapedControlCharacters() {
        response(200, "{\"\\ud83d\\ude80\":\"\\ud83d\\ude80\\n\\t\"}", "application/json");

        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.response().fieldNames().next()).isEqualTo("\ud83d\ude80");
        assertThat(outcome.response().elements().next().textValue()).isEqualTo("\ud83d\ude80\n\t");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"token\":\"prefix-private-token-suffix\"}",
        "{\"private-token\":true}", "{\"token\":\"private-\\u0074oken\"}"})
    void rejectsCredentialEchoInValuesKeysAndEscapedStrings(String remoteBody) {
        response(200, remoteBody, "application/json");
        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());

        assertThat(outcome.status()).isEqualTo("RESPONSE_REJECTED");
        assertThat(outcome.errorCode()).isEqualTo("CREDENTIAL_ECHO");
        assertThat(outcome.response()).isNull();
        assertThat(outcome.toString()).doesNotContain("private-token", remoteBody);
    }

    @Test
    void rejectsUnexpectedStatusAndContentTypeWithoutReturningRemoteErrorBody() {
        response(401, "private-token remote error", "text/plain");
        ConnectorHttpOutcome denied = gateway.execute(operation("POST"), NullNode.getInstance());
        assertThat(denied.status()).isEqualTo("HTTP_REJECTED");
        assertThat(denied.httpStatus()).isEqualTo(401);
        assertThat(denied.response()).isNull();
        assertThat(denied.toString()).doesNotContain("private-token");

        response(200, "{\"okay\":true}", "text/html");
        ConnectorHttpOutcome wrongType = gateway.execute(operation("POST"), NullNode.getInstance());
        assertThat(wrongType.status()).isEqualTo("RESPONSE_REJECTED");
        assertThat(wrongType.errorCode()).isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void acceptsEmpty204AsJsonNullAndDoesNotSendGetBody() {
        when(transport.exchange(any())).thenReturn(new ConnectorHttpTransport.TransportResponse(204, new byte[0], null));
        ConnectorHttpOutcome outcome = gateway.execute(operation("GET"), NullNode.getInstance());
        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.response().isNull()).isTrue();
        var capture = ArgumentCaptor.forClass(ConnectorHttpTransport.TransportRequest.class);
        verify(transport).exchange(capture.capture());
        assertThat(capture.getValue().body()).isNull();
    }

    @Test
    void deploymentDisableRejectsBeforeCredentialsOrNetwork() {
        properties.setEnabled(false);
        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());
        assertThat(outcome.status()).isEqualTo("TRANSPORT_FAILED");
        assertThat(outcome.errorCode()).isEqualTo("CONNECTOR_DISABLED");
        assertThat(outcome.mayHaveBeenSent()).isFalse();
        verify(credentials, never()).resolve(any(), any());
        verify(transport, never()).exchange(any());
    }

    @Test
    void invalidGetBodyAndOversizedRequestNeverReachNetwork() {
        ConnectorHttpOutcome get = gateway.execute(operation("GET"), mapper.createObjectNode().put("value", 1));
        assertThat(get.errorCode()).isEqualTo("INVALID_REQUEST");
        ConnectorHttpOutcome large = gateway.execute(operation("POST"),
            mapper.createObjectNode().put("value", "x".repeat(131072)));
        assertThat(large.errorCode()).isEqualTo("INVALID_REQUEST");
        verify(transport, never()).exchange(any());
    }

    @Test
    void timeoutKeepsUnknownRemoteOutcomeAndNeverIncludesExceptionCause() {
        when(transport.exchange(any())).thenThrow(new ConnectorHttpTransport.TransportFailure("TOTAL_TIMEOUT", true, null));
        ConnectorHttpOutcome outcome = gateway.execute(operation("POST"), NullNode.getInstance());
        assertThat(outcome.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(outcome.errorCode()).isEqualTo("TOTAL_TIMEOUT");
        assertThat(outcome.mayHaveBeenSent()).isTrue();
        assertThat(outcome.response()).isNull();
    }

    private void response(int status, String body, String contentType) {
        when(transport.exchange(any())).thenReturn(new ConnectorHttpTransport.TransportResponse(status,
            body.getBytes(StandardCharsets.UTF_8), contentType));
    }

    private static ConnectorProfileOperation operation(String method) {
        return new ConnectorProfileOperation("project", "profile", "version", 1,
            "https://api.example.test", "registered", "operation", method, "/operation", Set.of(200, 204));
    }
}
