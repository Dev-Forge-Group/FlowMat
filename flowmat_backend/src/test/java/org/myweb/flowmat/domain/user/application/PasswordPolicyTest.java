package org.myweb.flowmat.domain.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.myweb.flowmat.global.exception.BusinessException;

/** A new password (docs/operations/login-security.md): 8 characters to 72 bytes, not the user ID or email. */
class PasswordPolicyTest {

    @Test
    void takesAPasswordOfEightCharactersUpToSeventyTwoBytes() {
        assertThat(PasswordPolicy.require("  melon-milk  ", "tester", "tester@example.com")).isEqualTo("melon-milk");
        assertThat(PasswordPolicy.require("가나다라마바사아", "tester", null)).isEqualTo("가나다라마바사아");
        assertThat(PasswordPolicy.require("a".repeat(72), "tester", null)).hasSize(72);
    }

    @Test
    void refusesShortLongAndOwnIdOrEmail() {
        assertThatThrownBy(() -> PasswordPolicy.require("short12", "tester", null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("at least 8");
        assertThatThrownBy(() -> PasswordPolicy.require(null, "tester", null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("at least 8");
        // 25 three-byte characters are 75 bytes: BCrypt would read only the first 72.
        assertThatThrownBy(() -> PasswordPolicy.require("가".repeat(25), "tester", null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("at most 72 bytes");
        assertThatThrownBy(() -> PasswordPolicy.require("Tester-Account", "tester-account", null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("user ID or email");
        assertThatThrownBy(() -> PasswordPolicy.require("Tester@Example.com", "tester", "tester@example.com"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("user ID or email");
    }
}
