package org.myweb.flowmat.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The committed defaults behind ADR-029 (management port) and ADR-030 (face login off). */
class ProductionConfigurationTest {

    @Test
    void prodProfileMovesTheActuatorToItsOwnPortAndExposesOnlyHealthAndMetrics() throws IOException {
        PropertySource<?> prod = documents().stream()
            .filter(document -> "prod".equals(String.valueOf(document.getProperty("spring.config.activate.on-profile"))))
            .findFirst()
            .orElseThrow();
        assertThat(String.valueOf(prod.getProperty("management.server.port"))).isEqualTo("${MANAGEMENT_SERVER_PORT:8081}");
        assertThat(String.valueOf(prod.getProperty("management.endpoints.web.exposure.include"))).isEqualTo("health,prometheus");
    }

    @Test
    void faceLoginIsOffUnlessEnabled() throws IOException {
        assertThat(String.valueOf(documents().get(0).getProperty("app.auth.face-login.enabled")))
            .isEqualTo("${APP_AUTH_FACE_LOGIN_ENABLED:false}");
    }

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
    }
}
