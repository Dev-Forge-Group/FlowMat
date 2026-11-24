package org.myweb.flowmat.domain.user.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal data kept only as long as needed (docs/operations/personal-data.md P1, P2). A user who withdrew more than the
 * withdrawal retention ago keeps the row other records point at (its user ID), but loses name, nickname, email, phone,
 * birth date, photo, password, social links, face descriptor and login history; the erasure is marked so it happens once.
 * Everyone's login history older than its retention goes too. Safe to run again or on two instances at once.
 */
@Service
@RequiredArgsConstructor
public class PersonalDataRetentionService {

    /** What one sweep erased: the users whose personal data went and the login history rows deleted. */
    public record Swept(List<String> erasedUserIds, int loginHistoryRows) {
    }

    private final JdbcTemplate jdbc;

    @Transactional
    public Swept sweep(OffsetDateTime now, Duration withdrawnRetention, Duration loginHistoryRetention) {
        Timestamp at = Timestamp.from(now.toInstant());
        // The login ID stays: production, inventory and audit rows point at it. The row's own key names the login history.
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
            update users set user_name = 'Withdrawn user', user_nickname = null,
                   user_email = 'erased-' || id::text || '@erased.invalid', user_tel = null, user_birth = null,
                   avatar_url = null, user_pwd = '!erased', dormant_token = null, personal_data_erased_at = ?
             where user_status = 'withdrawn' and withdrawn_at < ? and personal_data_erased_at is null
            returning id, user_id
            """, at, Timestamp.from(now.minus(withdrawnRetention).toInstant()));
        List<String> erased = rows.stream().map(row -> (String) row.get("user_id")).sorted().toList();
        int history = 0;
        if (!erased.isEmpty()) {
            String[] loginIds = erased.toArray(String[]::new);
            java.util.UUID[] keys = rows.stream().map(row -> (java.util.UUID) row.get("id")).toArray(java.util.UUID[]::new);
            jdbc.update("delete from face_descriptor where user_id = any (?)", (Object) loginIds);
            jdbc.update("delete from social_accounts where user_pk = any (?)", (Object) keys);
            history += jdbc.update("delete from user_login_history where user_id = any (?)", (Object) keys);
        }
        history += jdbc.update("delete from user_login_history where login_at < ?",
            Timestamp.from(now.minus(loginHistoryRetention).toInstant()));
        return new Swept(List.copyOf(erased), history);
    }
}
