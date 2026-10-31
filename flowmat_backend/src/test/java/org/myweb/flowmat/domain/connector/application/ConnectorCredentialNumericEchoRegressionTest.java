package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorHttpOutcome;
import org.myweb.flowmat.domain.connector.application.publicapi.ConnectorProfileOperation;
import org.springframework.mock.env.MockEnvironment;

class ConnectorCredentialNumericEchoRegressionTest {
    // A synthetic credential used only by this in-memory fixture; it is not an environment or provider credential.
    private static final String SYNTHETIC_TOKEN = "100000000000000000000";
    private final ConnectorTargetPolicy policy = mock(ConnectorTargetPolicy.class);
    private final ConnectorHttpTransport transport = mock(ConnectorHttpTransport.class);
    private final ConnectorHttpProperties properties = new ConnectorHttpProperties();
    private DefaultConnectorHttpGateway gateway;

    @BeforeEach
    void configure() {
        properties.setEnabled(true);
        ConnectorHttpProperties.Credential credential = new ConnectorHttpProperties.Credential();
        credential.setProjectId("project");
        credential.setKind(ConnectorHttpProperties.Kind.BEARER);
        credential.setTokenEnvironment("SYNTHETIC_CONNECTOR_TEST_TOKEN");
        properties.setCredentials(Map.of("registered", credential));
        ConnectorCredentials credentials = new ConnectorCredentials(properties,
            new MockEnvironment().withProperty("SYNTHETIC_CONNECTOR_TEST_TOKEN", SYNTHETIC_TOKEN));
        gateway = new DefaultConnectorHttpGateway(policy, credentials, transport, properties);
        when(policy.target("project", "https://api.example.test", "/operation", "registered"))
            .thenReturn(URI.create("https://api.example.test/operation"));
        when(policy.target("project", "https://api.example.test", "/operation", null))
            .thenReturn(URI.create("https://api.example.test/operation"));
    }

    @ParameterizedTest(name = "credential echo: {0}")
    @MethodSource("numericEchoBodies")
    void rejectsScientificNumbersThatExpandToCredentialText(String label, String remoteBody) {
        response(remoteBody);

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("RESPONSE_REJECTED", outcome.status());
        assertEquals("CREDENTIAL_ECHO", outcome.errorCode());
        assertEquals(200, outcome.httpStatus());
        assertTrue(outcome.mayHaveBeenSent());
        assertTrue(outcome.response() == null, "Rejected response evidence must not be exposed");
        assertFalse(outcome.toString().contains(SYNTHETIC_TOKEN), "Summary must not expose the credential");
    }

    private static Stream<Arguments> numericEchoBodies() {
        return Stream.of(
            Arguments.of("top-level exponent", "1e20"),
            Arguments.of("object exponent", "{\"value\":1e20}"),
            Arguments.of("nested array exponent", "{\"rows\":[false,{\"value\":1.00E+20}]}"),
            Arguments.of("array scalar exponent", "[null,100e18]"),
            Arguments.of("negative exponent value with sensitive digits", "{\"value\":-1e20}"));
    }

    @ParameterizedTest(name = "different numeric value: {0}")
    @MethodSource("differentNumbers")
    void preservesExactScientificNumbersThatDoNotContainCredentialText(String label, String remoteBody,
        String expectedDecimal) {
        response(remoteBody);

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("SUCCEEDED", outcome.status());
        assertTrue(outcome.errorCode() == null);
        assertNotNull(outcome.response());
        assertEquals(0, new BigDecimal(expectedDecimal).compareTo(outcome.response().path("value").decimalValue()));
    }

    private static Stream<Arguments> differentNumbers() {
        return Stream.of(
            Arguments.of("different large magnitude", "{\"value\":2e20}", "2E+20"),
            Arguments.of("long decimal mantissa", "{\"value\":9.007199254740993123456789e15}", "9007199254740993.123456789"),
            Arguments.of("small nonzero exponent", "{\"value\":1.23456789123456789e-20}", "1.23456789123456789E-20"));
    }

    @ParameterizedTest(name = "uncredentialed response: {0}")
    @MethodSource("uncredentialedBodies")
    void acceptsTheSameNumericValuesWhenNoCredentialIsConfigured(String label, String remoteBody) throws Exception {
        response(remoteBody);

        ConnectorHttpOutcome outcome = gateway.execute(operation(null), NullNode.getInstance());

        assertEquals("SUCCEEDED", outcome.status());
        assertTrue(outcome.errorCode() == null);
        JsonNode number = outcome.response().isObject() ? outcome.response().path("value") : outcome.response();
        assertTrue(number.isNumber());
        assertEquals(0, new BigDecimal(SYNTHETIC_TOKEN).compareTo(number.decimalValue()));
        // Serializing the accepted tree must remain valid exact JSON without a transport or database dependency.
        assertNotNull(new ObjectMapper().readTree(outcome.response().toString()));
    }

    private static Stream<Arguments> uncredentialedBodies() {
        return Stream.of(
            Arguments.of("top-level exponent", "1e20"),
            Arguments.of("object exponent", "{\"value\":1.00e20}"));
    }

    @Test
    void stillRejectsDecodedStringEchoesWithoutReturningTheirBody() {
        response("{\"value\":\"\\u0031" + "0".repeat(20) + "\"}");

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("RESPONSE_REJECTED", outcome.status());
        assertEquals("CREDENTIAL_ECHO", outcome.errorCode());
        assertTrue(outcome.response() == null, "Rejected response evidence must not be exposed");
    }

    @Test
    void keepsTheExistingScientificTextEchoCheck() {
        useSyntheticToken("1E+20");
        response("1e20");

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("RESPONSE_REJECTED", outcome.status());
        assertEquals("CREDENTIAL_ECHO", outcome.errorCode());
        assertTrue(outcome.response() == null, "Rejected response evidence must not be exposed");
    }

    @Test
    void doesNotTreatAnOpaqueLeadingZeroCredentialAsANumber() {
        useSyntheticToken("0" + SYNTHETIC_TOKEN);
        response("1e20");

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(0, new BigDecimal(SYNTHETIC_TOKEN).compareTo(outcome.response().decimalValue()));
    }

    @Test
    void retainsTheExistingMaximumAcceptedNumberScale() {
        response("{\"value\":2e1000}");

        ConnectorHttpOutcome outcome = gateway.execute(operation("registered"), NullNode.getInstance());

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(-1000, outcome.response().path("value").decimalValue().scale());
        assertEquals(0, new BigDecimal("2e1000").compareTo(outcome.response().path("value").decimalValue()));
    }

    private void useSyntheticToken(String token) {
        gateway = new DefaultConnectorHttpGateway(policy, new ConnectorCredentials(properties,
            new MockEnvironment().withProperty("SYNTHETIC_CONNECTOR_TEST_TOKEN", token)), transport, properties);
    }

    private void response(String body) {
        when(transport.exchange(any())).thenReturn(new ConnectorHttpTransport.TransportResponse(200,
            body.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static ConnectorProfileOperation operation(String credentialAlias) {
        return new ConnectorProfileOperation("project", "profile", "version", 1,
            "https://api.example.test", credentialAlias, "operation", "POST", "/operation", Set.of(200));
    }
}
