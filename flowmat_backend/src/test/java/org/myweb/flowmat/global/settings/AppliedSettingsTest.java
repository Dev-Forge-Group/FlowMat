package org.myweb.flowmat.global.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockAlertSettings;
import org.myweb.flowmat.domain.workflow.collab.WorkflowCollabProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The runtime settings bind with their defaults, refuse values that would break them, and name what was applied. */
class AppliedSettingsTest {

    @Configuration
    @EnableConfigurationProperties({WorkflowCollabProperties.class, StockAlertSettings.class})
    @Import(AppliedSettings.class)
    static class Settings {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(Settings.class);

    @Test
    void defaultsBindAndAreNamedByARevision() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AppliedSettings applied = context.getBean(AppliedSettings.class);
            assertThat(applied.values()).containsEntry("app.workflow-collab.graph.retention", "PT744H")
                .containsEntry("app.workflow-collab.graph.key-ttl", "PT840H")
                .containsEntry("app.workflow-collab.presence.heartbeat-timeout", "PT45S")
                .containsEntry("app.workflow-collab.presence.cleanup-interval", "PT15S")
                .containsEntry("app.stock-alert.expiry-warning-days", "7")
                .containsEntry("app.stock-alert.sweep-interval", "PT5M");
            assertThat(applied.revision()).hasSize(12).matches("[0-9a-f]+");

            Info.Builder info = new Info.Builder();
            applied.contribute(info);
            assertThat(info.build().getDetails()).containsKey("settings");
        });
    }

    @Test
    void theSameValuesGiveTheSameRevisionAndAChangeGivesAnother() {
        runner.run(defaults -> runner.withPropertyValues("app.stock-alert.expiry-warning-days=10").run(changed -> {
            String before = defaults.getBean(AppliedSettings.class).revision();
            assertThat(AppliedSettings.revisionOf(defaults.getBean(AppliedSettings.class).values())).isEqualTo(before);
            assertThat(changed.getBean(AppliedSettings.class).revision()).isNotEqualTo(before);
        }));
        assertThat(AppliedSettings.revisionOf(Map.of("b", "2", "a", "1")))
            .isEqualTo(AppliedSettings.revisionOf(new java.util.LinkedHashMap<>(Map.of("a", "1", "b", "2"))));
    }

    @Test
    void bothReadersOfTheExpiryWindowShareOneBoundValue() {
        runner.withPropertyValues("app.stock-alert.expiry-warning-days=14", "app.stock-alert.sweep-interval=PT1M")
            .run(context -> {
                StockAlertSettings settings = context.getBean(StockAlertSettings.class);
                assertThat(settings.expiryWarningDays()).isEqualTo(14);
                assertThat(settings.sweepInterval()).isEqualTo(Duration.ofMinutes(1));
            });
    }

    @Test
    void aKeyTtlShorterThanTheRetentionStopsStartup() {
        refused("app.workflow-collab.graph.key-ttl=P30D", "key-ttl must be at least the retention");
    }

    @Test
    void aCleanupSlowerThanTheHeartbeatTimeoutStopsStartup() {
        refused("app.workflow-collab.presence.cleanup-interval=PT60S", "cleanup-interval must not exceed the heartbeat timeout");
    }

    @Test
    void zeroOrNegativeDurationsStopStartup() {
        refused("app.workflow-collab.graph.retention=PT0S", "retention must be positive");
        refused("app.workflow-collab.presence.heartbeat-timeout=-PT1S", "must be positive");
        refused("app.stock-alert.sweep-interval=PT0S", "sweep-interval must be positive");
    }

    @Test
    void anExpiryWindowOutOfRangeStopsStartup() {
        refused("app.stock-alert.expiry-warning-days=-1", "expiryWarningDays");
        refused("app.stock-alert.expiry-warning-days=3651", "expiryWarningDays");
    }

    private void refused(String property, String reason) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertThat(messages.toString()).contains(reason);
        });
    }
}
