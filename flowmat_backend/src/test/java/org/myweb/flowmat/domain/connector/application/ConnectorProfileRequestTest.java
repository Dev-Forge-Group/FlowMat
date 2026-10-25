package org.myweb.flowmat.domain.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.connector.api.dto.request.ConnectorProfileRequest;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class ConnectorProfileRequestTest {
    private static final String BODY = "{\"name\":\"API\",\"baseOrigin\":\"https://api.example.com\",\"credentialAlias\":null,"
        + "\"enabled\":true,\"operations\":[{\"operationId\":\"read\",\"method\":\"GET\",\"relativePath\":\"/v1/items\",\"successStatuses\":[200,204]}]}";

    @Test
    void readsOnlyExplicitProfileConfigurationAndWholeVersion() {
        ConnectorProfileRequest created = read(BODY, false);
        assertEquals("API", created.name());
        assertEquals(2, created.operations().getFirst().successStatuses().size());
        ConnectorProfileRequest updated = read(BODY.replace("{\"name\"", "{\"expectedVersion\":7,\"name\""), true);
        assertEquals(7L, updated.expectedVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "-1", "9223372036854775808", "\"1\"", "true", "null"})
    void rejectsCoercedOrOutOfRangeExpectedVersions(String value) {
        invalid(BODY.replace("{\"name\"", "{\"expectedVersion\":" + value + ",\"name\""), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "\"true\"", "null"})
    void rejectsCoercedEnabledValues(String value) {
        invalid(BODY.replace("\"enabled\":true", "\"enabled\":" + value), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"200.5", "200.0", "199", "300", "\"200\"", "true", "null", "2147483648"})
    void rejectsInvalidSuccessStatuses(String value) {
        invalid(BODY.replace("[200,204]", "[" + value + "]"), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example/path", "/a/../admin", "/a/./b", "/a%2fb", "/a?token=x", "/a#part", "/{id}", "/a\\\\b", "https://evil.example/path"})
    void refusesPathsThatCanChangeTheApprovedTargetOrOperation(String value) {
        invalid(BODY.replace("/v1/items", value), false);
    }

    @Test
    void rejectsUnknownCredentialFieldsDuplicatesAndTrailingJson() {
        invalid(BODY.replace("{\"name\"", "{\"password\":\"private\",\"name\""), false);
        invalid(BODY.replace("{\"name\"", "{\"headers\":{},\"name\""), false);
        invalid(BODY.replace("{\"name\"", "{\"name\":\"first\",\"name\""), false);
        invalid(BODY + "{}", false);
        invalid(BODY.replace("\"successStatuses\":[200,204]", "\"successStatuses\":[]"), false);
        invalid(BODY.replace("\"operationId\":\"read\"", "\"operationId\":\"read\",\"headers\":{}"), false);
    }

    @Test
    void refusesLargeInputBeforeParsingAndDoesNotEchoValues() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> read(" ".repeat(131073), false));
        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("body must not exceed 131072 bytes.", exception.getMessage());
        BusinessException secret = assertThrows(BusinessException.class,
            () -> read(BODY.replace("{\"name\"", "{\"secret-password\":\"private\",\"name\""), false));
        org.junit.jupiter.api.Assertions.assertFalse(secret.getMessage().contains("private"));
        org.junit.jupiter.api.Assertions.assertFalse(secret.getMessage().contains("secret-password"));
    }

    private static ConnectorProfileRequest read(String body, boolean update) {
        return ConnectorProfileRequest.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), update);
    }

    private static void invalid(String body, boolean update) {
        assertEquals(ErrorCode.BAD_REQUEST,
            assertThrows(BusinessException.class, () -> read(body, update)).getErrorCode());
    }
}
