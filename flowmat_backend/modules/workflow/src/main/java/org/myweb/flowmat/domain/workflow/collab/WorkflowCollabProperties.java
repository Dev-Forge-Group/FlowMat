package org.myweb.flowmat.domain.workflow.collab;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Live collaboration on a workflow: how long graph changes are kept for clients to catch up, and when a silent editor
 * counts as gone. Bound from {@code app.workflow-collab.*}; values that would break either stop the application at
 * startup.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.workflow-collab")
public class WorkflowCollabProperties {

    @Valid
    private final Graph graph = new Graph();
    @Valid
    private final Presence presence = new Presence();

    @Getter
    @Setter
    public static class Graph {
        @NotNull
        private Duration retention = Duration.ofDays(31);
        @NotNull
        private Duration keyTtl = Duration.ofDays(35);

        @AssertTrue(message = "app.workflow-collab.graph.retention must be positive")
        public boolean isRetentionPositive() {
            return positive(retention);
        }

        /**
         * The keys, the change sequence among them, expire after {@code key-ttl}. Shorter than the retention, the
         * sequence would restart while changes are still kept, and a client catching up could skip changes.
         */
        @AssertTrue(message = "app.workflow-collab.graph.key-ttl must be at least the retention")
        public boolean isKeyTtlCoveringRetention() {
            return retention == null || keyTtl == null || keyTtl.compareTo(retention) >= 0;
        }
    }

    @Getter
    @Setter
    public static class Presence {
        @NotNull
        private Duration heartbeatTimeout = Duration.ofSeconds(45);
        @NotNull
        private Duration cleanupInterval = Duration.ofSeconds(15);

        @AssertTrue(message = "app.workflow-collab.presence.heartbeat-timeout and cleanup-interval must be positive")
        public boolean isPositive() {
            return positive(heartbeatTimeout) && positive(cleanupInterval);
        }

        /** A cleanup slower than the timeout would leave a gone editor's presence up for longer than the timeout says. */
        @AssertTrue(message = "app.workflow-collab.presence.cleanup-interval must not exceed the heartbeat timeout")
        public boolean isCleanupWithinTimeout() {
            return heartbeatTimeout == null || cleanupInterval == null || cleanupInterval.compareTo(heartbeatTimeout) <= 0;
        }
    }

    private static boolean positive(Duration value) {
        return value == null || (!value.isNegative() && !value.isZero());
    }
}
