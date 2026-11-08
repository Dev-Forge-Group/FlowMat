package org.myweb.flowmat.domain.workflow.collab;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.workflow-collab")
public class WorkflowCollabProperties {

    private final Graph graph = new Graph();
    private final Presence presence = new Presence();

    @Getter
    @Setter
    public static class Graph {
        private Duration retention = Duration.ofDays(31);
        private Duration keyTtl = Duration.ofDays(35);
    }

    @Getter
    @Setter
    public static class Presence {
        private Duration heartbeatTimeout = Duration.ofSeconds(45);
        private Duration cleanupInterval = Duration.ofSeconds(15);
    }
}
