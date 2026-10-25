package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.mock.env.MockEnvironment;

class ConnectorCredentialsTest {
    private final ConnectorHttpProperties properties = new ConnectorHttpProperties();
    private final MockEnvironment environment = new MockEnvironment();
    private final ConnectorCredentials credentials = new ConnectorCredentials(properties, environment);

    @Test
    void missingAliasMeansNoAuthenticationAndNoEnvironmentLookup() {
        assertTrue(credentials.resolve("p", null).headers().isEmpty());
        assertTrue(credentials.resolve("p", "").forbiddenValues().isEmpty());
    }

    @Test
    void bearerUsesOnlyTheRegisteredEnvironmentNameAndNeverIncludesSecretsInToString() {
        register(ConnectorHttpProperties.Kind.BEARER);
        environment.setProperty("CONNECTOR_TOKEN", "abc.DEF_123-secret");

        ConnectorCredentials.Resolved resolved = credentials.resolve("p", "registered");

        assertEquals(Map.of("Authorization", "Bearer abc.DEF_123-secret"), resolved.headers());
        assertTrue(resolved.forbiddenValues().contains("abc.DEF_123-secret"));
        assertTrue(resolved.forbiddenValues().contains("Bearer abc.DEF_123-secret"));
        assertFalse(resolved.toString().contains("abc.DEF_123-secret"));
        assertFalse(resolved.toString().contains("CONNECTOR_TOKEN"));
        assertThrows(UnsupportedOperationException.class, () -> resolved.headers().put("Other", "value"));
        assertThrows(UnsupportedOperationException.class, () -> resolved.forbiddenValues().add("value"));
    }

    @Test
    void basicIsUtf8AndItsRawAndEncodedCredentialFormsAreSensitive() {
        register(ConnectorHttpProperties.Kind.BASIC);
        environment.setProperty("CONNECTOR_USERNAME", "\uc0ac\uc6a9\uc790");
        environment.setProperty("CONNECTOR_PASSWORD", "p\u00e4ss:word");
        String encoded = Base64.getEncoder().encodeToString("\uc0ac\uc6a9\uc790:p\u00e4ss:word".getBytes(StandardCharsets.UTF_8));

        ConnectorCredentials.Resolved resolved = credentials.resolve("p", "registered");

        assertEquals("Basic " + encoded, resolved.headers().get("Authorization"));
        assertTrue(resolved.forbiddenValues().contains("\uc0ac\uc6a9\uc790"));
        assertTrue(resolved.forbiddenValues().contains("p\u00e4ss:word"));
        assertTrue(resolved.forbiddenValues().contains(encoded));
        assertTrue(resolved.forbiddenValues().contains("Basic " + encoded));
        assertFalse(resolved.toString().contains("p\u00e4ss:word"));
        assertFalse(resolved.toString().contains(encoded));
    }

    @Test
    void credentialsCannotCrossProjectsAndMissingAliasesOrValuesFailSafely() {
        register(ConnectorHttpProperties.Kind.BEARER);
        environment.setProperty("CONNECTOR_TOKEN", "secret-token-value");
        reject(() -> credentials.resolve("other-project", "registered"));
        reject(() -> credentials.resolve("p", "CONNECTOR_TOKEN"));
        reject(() -> credentials.resolve("p", "unknown"));
        environment.setProperty("CONNECTOR_TOKEN", "");
        reject(() -> credentials.resolve("p", "registered"));
        properties.getCredentials().get("registered").setTokenEnvironment("MISSING_TOKEN");
        reject(() -> credentials.resolve("p", "registered"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc\r\nInjected: secret", "abc\u0000secret", "abc\tsecret", "abc secret", "\u00e9-secret"})
    void bearerRejectsUnsafeOrAmbiguousHeaderValues(String value) {
        register(ConnectorHttpProperties.Kind.BEARER);
        environment.setProperty("CONNECTOR_TOKEN", value);
        reject(() -> credentials.resolve("p", "registered"));
    }

    @Test
    void basicRejectsColonInUsernameControlCharactersAndOversizedSecrets() {
        register(ConnectorHttpProperties.Kind.BASIC);
        environment.setProperty("CONNECTOR_USERNAME", "user:name");
        environment.setProperty("CONNECTOR_PASSWORD", "valid-password");
        reject(() -> credentials.resolve("p", "registered"));
        environment.setProperty("CONNECTOR_USERNAME", "user");
        environment.setProperty("CONNECTOR_PASSWORD", "password\r\ninjected");
        reject(() -> credentials.resolve("p", "registered"));
        environment.setProperty("CONNECTOR_PASSWORD", "x".repeat(4097));
        reject(() -> credentials.resolve("p", "registered"));
    }

    @Test
    void environmentNamesCannotBeProvidedThroughUserAliasesOrConfigurationExpressions() {
        register(ConnectorHttpProperties.Kind.BEARER);
        properties.getCredentials().get("registered").setTokenEnvironment("${OTHER_SECRET}");
        reject(() -> credentials.resolve("p", "registered"));
        reject(() -> credentials.resolve("p", "${CONNECTOR_TOKEN}"));
    }

    private void register(ConnectorHttpProperties.Kind kind) {
        ConnectorHttpProperties.Credential credential = new ConnectorHttpProperties.Credential();
        credential.setProjectId("p");
        credential.setKind(kind);
        credential.setTokenEnvironment("CONNECTOR_TOKEN");
        credential.setUsernameEnvironment("CONNECTOR_USERNAME");
        credential.setPasswordEnvironment("CONNECTOR_PASSWORD");
        properties.setCredentials(Map.of("registered", credential));
    }

    private static void reject(Runnable operation) {
        BusinessException exception = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertFalse(exception.getMessage().contains("secret-token-value"));
        assertFalse(exception.getMessage().contains("CONNECTOR_TOKEN"));
        assertTrue(exception.getMessage().length() < 160);
    }
}
