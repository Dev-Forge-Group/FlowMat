package org.myweb.flowmat.global.health;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps {@code /api/actuator/health/readiness} and {@code /liveness} on the public port once the actuator has its own
 * management port (production, ADR-029): load balancer probes and the staging smoke workflow call them there. Status
 * only; health details, metrics and every other endpoint stay on the management port.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
@RequestMapping("/actuator/health")
public class PublicHealthProbeController {

    private final HealthEndpoint healthEndpoint;

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, String>> readiness() {
        return probe("readiness");
    }

    @GetMapping("/liveness")
    public ResponseEntity<Map<String, String>> liveness() {
        return probe("liveness");
    }

    private ResponseEntity<Map<String, String>> probe(String group) {
        HealthComponent health = healthEndpoint.healthForPath(group);
        Status status = health == null ? Status.UNKNOWN : health.getStatus();
        HttpStatus code = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(Map.of("status", status.getCode()));
    }
}
