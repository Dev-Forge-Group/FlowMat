package org.myweb.flowmat.domain.connector.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConnectorCredentials {
    private final ConnectorHttpProperties properties;
    private final Environment environment;

    public ConnectorCredentials(ConnectorHttpProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    Resolved resolve(String projectId, String alias) {
        ConnectorHttpProperties.Credential credential = registered(properties, projectId, alias);
        if (credential == null) return new Resolved(Map.of(), Set.of());
        Set<String> forbidden = new LinkedHashSet<>();
        String authorization;
        if (credential.getKind() == ConnectorHttpProperties.Kind.BEARER) {
            String token = secret(credential.getTokenEnvironment());
            if (!token.matches("[A-Za-z0-9._~+/\\-]+=*")) throw invalid();
            forbidden.add(token);
            authorization = "Bearer " + token;
        } else if (credential.getKind() == ConnectorHttpProperties.Kind.BASIC) {
            String username = secret(credential.getUsernameEnvironment());
            String password = secret(credential.getPasswordEnvironment());
            if (username.indexOf(':') >= 0) throw invalid();
            String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            forbidden.add(username);
            forbidden.add(password);
            forbidden.add(encoded);
            authorization = "Basic " + encoded;
        } else {
            throw invalid();
        }
        forbidden.add(authorization);
        return new Resolved(Map.of("Authorization", authorization), forbidden);
    }

    static ConnectorHttpProperties.Credential registered(ConnectorHttpProperties properties, String projectId, String alias) {
        if (projectId == null || projectId.isBlank()) throw invalid();
        if (alias == null || alias.isEmpty()) return null;
        if (!alias.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || properties.getCredentials() == null) throw invalid();
        ConnectorHttpProperties.Credential credential = properties.getCredentials().get(alias);
        if (credential == null || !projectId.equals(credential.getProjectId()) || credential.getKind() == null) throw invalid();
        return credential;
    }

    static boolean isValidEnvironmentName(String name) {
        return name != null && name.matches("[A-Z][A-Z0-9_]{0,127}");
    }

    private String secret(String name) {
        if (!isValidEnvironmentName(name)) throw invalid();
        String value;
        try {
            value = environment.getProperty(name);
        } catch (RuntimeException exception) {
            throw invalid();
        }
        if (value == null || value.isEmpty() || value.length() > 4096
            || value.getBytes(StandardCharsets.UTF_8).length > 4096
            || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == 0x2028 || codePoint == 0x2029)) {
            throw invalid();
        }
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "credentialAlias is invalid or its configured credential is unavailable.");
    }

    public record Resolved(Map<String, String> headers, Set<String> forbiddenValues) {
        public Resolved {
            headers = Map.copyOf(headers);
            forbiddenValues = Set.copyOf(forbiddenValues);
        }

        @Override
        public String toString() {
            return "Resolved[headerCount=" + headers.size() + ", sensitiveValueCount=" + forbiddenValues.size() + "]";
        }
    }
}
