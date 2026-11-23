package org.myweb.flowmat.global.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.myweb.flowmat.domain.user.api.dto.request.DormantTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.EmailCodeVerifyRequest;
import org.myweb.flowmat.domain.user.api.dto.request.FaceSelectRequest;
import org.myweb.flowmat.domain.user.api.dto.request.LogoutRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthExchangeRequest;
import org.myweb.flowmat.domain.user.api.dto.request.OAuthSignupCompleteRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordChangeRequest;
import org.myweb.flowmat.domain.user.api.dto.request.PasswordResetConfirmRequest;
import org.myweb.flowmat.domain.user.api.dto.request.RefreshTokenRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkStartRequest;
import org.myweb.flowmat.domain.user.api.dto.request.SocialLinkUnlinkRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserLoginRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserSignupRequest;
import org.myweb.flowmat.domain.user.api.dto.request.UserUpdateRequest;
import org.myweb.flowmat.domain.user.api.dto.response.FaceMatchResponse;
import org.myweb.flowmat.domain.user.api.dto.response.OAuthExchangeResponse;
import org.myweb.flowmat.domain.user.api.dto.response.UserTokenResponse;
import org.springframework.core.log.LogFormatUtils;

/** Spring MVC diagnostic formatting must not expose credentials; JSON transport remains unchanged. */
class AuthDtoLoggingRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password", "newPassword", "currentPassword",
        "accessToken", "refreshToken", "signupToken", "token", "code");

    @ParameterizedTest(name = "authentication DTO [{index}], bounded diagnostic={2}")
    @MethodSource("authenticationValues")
    void diagnosticFormattingHidesSecretsButJsonStillTransportsThem(Object dto, Map<String, String> secrets,
                                                                   boolean bounded) {
        String diagnostic = LogFormatUtils.formatValue(dto, bounded);
        for (String marker : secrets.values()) {
            assertFalse(diagnostic.contains(marker), "Sensitive authentication data in diagnostic output");
        }
        JsonNode transport = JSON.valueToTree(dto);
        for (var field : secrets.entrySet()) {
            assertEquals(field.getValue(), transport.path(field.getKey()).asText());
        }
    }

    private static Stream<Arguments> authenticationValues() throws Exception {
        List<Case> values = new ArrayList<>();
        for (Class<?> type : List.of(UserLoginRequest.class, RefreshTokenRequest.class, UserTokenResponse.class,
            OAuthExchangeResponse.class, UserSignupRequest.class, UserUpdateRequest.class, OAuthSignupCompleteRequest.class,
            OAuthExchangeRequest.class, DormantTokenRequest.class, LogoutRequest.class, PasswordResetConfirmRequest.class, PasswordChangeRequest.class,
            EmailCodeVerifyRequest.class, SocialLinkStartRequest.class, SocialLinkUnlinkRequest.class)) {
            var components = type.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            Map<String, String> secrets = new LinkedHashMap<>();
            for (int index = 0; index < components.length; index++) {
                var component = components[index];
                types[index] = component.getType();
                if (SENSITIVE_FIELDS.contains(component.getName())) {
                    String marker = "PRIVATE_" + component.getName() + "_MARKER";
                    args[index] = marker;
                    secrets.put(component.getName(), marker);
                } else if (component.getType() == String.class) {
                    args[index] = "x";
                } else if (component.getType() == boolean.class) {
                    args[index] = false;
                } else if (component.getType() == LocalDate.class) {
                    args[index] = LocalDate.of(1980, 1, 1);
                } else {
                    throw new IllegalStateException("Add a typed fixture for " + component.getType().getName());
                }
            }
            values.add(new Case(type.getDeclaredConstructor(types).newInstance(args), secrets));
        }
        // These beans already use Object.toString(), so keep them as safe comparison cases.
        values.add(new Case(JSON.readValue("{\"matchToken\":\"PRIVATE_matchToken_MARKER\",\"userId\":\"x\",\"deviceId\":\"x\"}",
            FaceSelectRequest.class), Map.of("matchToken", "PRIVATE_matchToken_MARKER")));
        values.add(new Case(FaceMatchResponse.builder().accounts(List.of()).matchToken("PRIVATE_matchToken_MARKER").build(),
            Map.of("matchToken", "PRIVATE_matchToken_MARKER")));
        return values.stream().flatMap(value -> Stream.of(Arguments.of(value.dto(), value.secrets(), true),
            Arguments.of(value.dto(), value.secrets(), false)));
    }

    private record Case(Object dto, Map<String, String> secrets) {}
}
