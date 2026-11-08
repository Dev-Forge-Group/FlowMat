package org.myweb.flowmat.domain.user.application;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.auth.login-rate-limit")
public class LoginRateLimitProperties {

    @Min(1)
    private int accountLimit = 8;

    @Min(1)
    private int ipLimit = 12;
}
