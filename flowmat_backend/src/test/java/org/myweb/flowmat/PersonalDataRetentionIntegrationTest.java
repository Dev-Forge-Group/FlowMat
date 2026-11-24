package org.myweb.flowmat;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.user.application.PersonalDataRetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Personal data kept only as long as needed (docs/operations/personal-data.md P1, P2): a user who withdrew 31 days ago is
 * erased (row and login ID kept, name, contact, birth date, social link, face descriptor and login history gone), one who
 * withdrew 5 days ago is not yet, and login history older than a year goes for everyone. A second sweep changes nothing.
 */
class PersonalDataRetentionIntegrationTest extends IntegrationTestSupport {

    @Autowired private PersonalDataRetentionService retention;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aWithdrawnUserPastRetentionIsErasedAndOldLoginHistoryGoes() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID gone = user("pd-gone-" + tag, "withdrawn", now.minusDays(31));
        UUID recent = user("pd-recent-" + tag, "withdrawn", now.minusDays(5));
        UUID active = user("pd-active-" + tag, "active", null);
        jdbc.update("insert into face_descriptor (user_id, descriptor) values (?, '[0.1,0.2]')", "pd-gone-" + tag);
        jdbc.update("insert into social_accounts (user_pk, provider, provider_user_id) values (?, 'GOOGLE', ?)", gone, "g-" + tag);
        login(gone, now.minusDays(40));
        login(active, now.minusDays(400));
        UUID kept = login(active, now.minusDays(1));

        PersonalDataRetentionService.Swept swept = retention.sweep(now, Duration.ofDays(30), Duration.ofDays(365));

        assertThat(swept.erasedUserIds()).contains("pd-gone-" + tag).doesNotContain("pd-recent-" + tag, "pd-active-" + tag);
        Map<String, Object> erased = jdbc.queryForMap("select * from users where id = ?", gone);
        assertThat(erased.get("user_id")).isEqualTo("pd-gone-" + tag);
        assertThat(erased.get("user_name")).isEqualTo("Withdrawn user");
        assertThat(erased.get("user_email")).isEqualTo("erased-" + gone + "@erased.invalid");
        assertThat(erased.get("user_nickname")).isNull();
        assertThat(erased.get("user_tel")).isNull();
        assertThat(erased.get("user_birth")).isNull();
        assertThat(erased.get("personal_data_erased_at")).isNotNull();
        assertThat(count("select count(*) from face_descriptor where user_id = ?", "pd-gone-" + tag)).isZero();
        assertThat(count("select count(*) from social_accounts where user_pk = ?", gone)).isZero();
        assertThat(count("select count(*) from user_login_history where user_id = ?", gone)).isZero();
        // Within retention: untouched.
        assertThat(jdbc.queryForObject("select user_name from users where id = ?", String.class, recent)).isEqualTo("pd-recent-" + tag);
        // Login history: the year-old row of an active user goes, yesterday's stays.
        assertThat(jdbc.queryForList("select login_history_id from user_login_history where user_id = ?", UUID.class, active))
            .containsExactly(kept);

        PersonalDataRetentionService.Swept again = retention.sweep(now, Duration.ofDays(30), Duration.ofDays(365));
        assertThat(again.erasedUserIds()).doesNotContain("pd-gone-" + tag);
    }

    private UUID user(String userId, String status, OffsetDateTime withdrawnAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, user_id, user_name, user_nickname, user_email, user_pwd, user_birth, user_tel, user_status,"
                + " delete_yn, withdrawn_at) values (?, ?, ?, ?, ?, 'x', date '1990-01-01', ?, ?, ?, ?)",
            id, userId, userId, userId, userId + "@test.local", "010" + Math.abs(userId.hashCode()), status,
            "withdrawn".equals(status) ? "Y" : "N", withdrawnAt == null ? null : Timestamp.from(withdrawnAt.toInstant()));
        return id;
    }

    private UUID login(UUID user, OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into user_login_history (login_history_id, user_id, login_ip, login_at) values (?, ?, '10.0.0.1', ?)",
            id, user, Timestamp.from(at.toInstant()));
        return id;
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }
}
