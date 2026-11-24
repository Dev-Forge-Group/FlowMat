package org.myweb.flowmat.batch;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.user.application.PersonalDataRetentionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Once a day, erases what is past its retention (docs/operations/personal-data.md P1, P2): withdrawn users' personal data
 * after {@code app.privacy.withdrawn-retention} and login history after {@code app.privacy.login-history-retention}. Off
 * unless {@code app.privacy.sweep-cron} is set: the periods wait for legal review (§5), and an erase cannot be undone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalDataRetentionBatchService {

    private final PersonalDataRetentionService retention;

    @Value("${app.privacy.withdrawn-retention:P30D}")
    private Duration withdrawnRetention;

    @Value("${app.privacy.login-history-retention:P365D}")
    private Duration loginHistoryRetention;

    @Scheduled(cron = "${app.privacy.sweep-cron:-}", zone = "UTC")
    public void sweep() {
        PersonalDataRetentionService.Swept swept = retention.sweep(OffsetDateTime.now(ZoneOffset.UTC), withdrawnRetention,
            loginHistoryRetention);
        if (!swept.erasedUserIds().isEmpty() || swept.loginHistoryRows() > 0) {
            log.info("Personal data retention: erased {} withdrawn user(s), deleted {} login history row(s)",
                swept.erasedUserIds().size(), swept.loginHistoryRows());
        }
    }
}
