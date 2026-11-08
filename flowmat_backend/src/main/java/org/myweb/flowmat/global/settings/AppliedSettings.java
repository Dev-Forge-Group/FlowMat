package org.myweb.flowmat.global.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockAlertSettings;
import org.myweb.flowmat.domain.workflow.collab.WorkflowCollabProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The runtime settings this instance applied, after validation, and a revision that names them: the same values give
 * the same revision, so two instances, or an instance before and after a change, can be compared. Logged once at
 * startup and, where the actuator's info endpoint is exposed, shown under {@code settings}. Only settings that do not
 * touch business records and hold no secrets are listed.
 */
@Slf4j
@Component
public class AppliedSettings implements InfoContributor {

    private final Map<String, String> values;
    private final String revision;

    public AppliedSettings(WorkflowCollabProperties collab, StockAlertSettings stockAlert) {
        Map<String, String> applied = new LinkedHashMap<>();
        applied.put("app.workflow-collab.graph.retention", collab.getGraph().getRetention().toString());
        applied.put("app.workflow-collab.graph.key-ttl", collab.getGraph().getKeyTtl().toString());
        applied.put("app.workflow-collab.presence.heartbeat-timeout", collab.getPresence().getHeartbeatTimeout().toString());
        applied.put("app.workflow-collab.presence.cleanup-interval", collab.getPresence().getCleanupInterval().toString());
        applied.put("app.stock-alert.expiry-warning-days", Integer.toString(stockAlert.expiryWarningDays()));
        applied.put("app.stock-alert.sweep-interval", stockAlert.sweepInterval().toString());
        this.values = Map.copyOf(applied);
        this.revision = revisionOf(applied);
    }

    public Map<String, String> values() {
        return values;
    }

    public String revision() {
        return revision;
    }

    /** The first 12 hex digits of SHA-256 over the settings in name order, one {@code name=value} line each. */
    static String revisionOf(Map<String, String> values) {
        String canonical = values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue() + "\n")
            .collect(Collectors.joining());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("settings", Map.of("revision", revision, "values", new java.util.TreeMap<>(values)));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logApplied() {
        log.info("Applied runtime settings revision {}: {}", revision, new java.util.TreeMap<>(values));
    }
}
