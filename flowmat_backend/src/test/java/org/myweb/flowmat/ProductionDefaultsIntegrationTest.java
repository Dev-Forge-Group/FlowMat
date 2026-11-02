package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real ports with the production management settings (ADR-029, a free port instead of 8081) and the default face login
 * switch (ADR-030).
 */
@ActiveProfiles("test")
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "jwt.secret=smoke-test-secret-key-that-is-at-least-32-bytes",
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "spring.mail.host=localhost",
    "spring.mail.port=2525",
    "spring.mail.username=",
    "spring.mail.password=",
    "app.frontend-url=http://localhost:5173",
    "app.oauth2.redirect-uri=http://localhost:5173/oauth/callback",
    "app.cors.allowed-origins=http://localhost:5173",
    "management.server.port=0",
    "management.endpoints.web.exposure.include=health,prometheus",
})
class ProductionDefaultsIntegrationTest extends IntegrationTestSupport {

    private static final String METRIC = "jvm_memory_used_bytes";

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void metricsAnswerOnlyOnTheManagementPort() throws Exception {
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains(METRIC);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isNotEqualTo(200);

        HttpResponse<String> publicMetrics = get(port, "/api/actuator/prometheus");
        assertThat(publicMetrics.statusCode()).isNotEqualTo(200);
        assertThat(publicMetrics.body()).doesNotContain(METRIC);
    }

    @Test
    void publicPortKeepsStatusOnlyProbes() throws Exception {
        for (String probe : List.of("readiness", "liveness")) {
            HttpResponse<String> response = get(port, "/api/actuator/health/" + probe);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }
        assertThat(get(port, "/api/actuator/health").statusCode()).isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void faceLoginIsOffByDefault() throws Exception {
        for (String path : List.of("/api/auth/face/login", "/api/auth/face/match", "/api/auth/face/select")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"descriptor\":\"[0]\"}"))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).contains("Face login is not enabled.");
        }
    }

    private HttpResponse<String> get(int target, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
