package org.myweb.flowmat.domain.connector.application;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "flowmat.connector")
public class ConnectorHttpProperties {
    private volatile boolean enabled;
    private List<String> allowedOrigins = List.of();
    private List<String> privateCidrs = List.of();
    private boolean allowPrivateHttp;
    private Map<String, Credential> credentials = Map.of();

    public enum Kind { BEARER, BASIC }

    @Getter
    @Setter
    public static class Credential {
        private String projectId;
        private Kind kind;
        private String tokenEnvironment;
        private String usernameEnvironment;
        private String passwordEnvironment;
    }
}
