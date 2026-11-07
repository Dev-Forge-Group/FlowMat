package org.myweb.flowmat.global.slack;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class SlackProperties {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;
}
