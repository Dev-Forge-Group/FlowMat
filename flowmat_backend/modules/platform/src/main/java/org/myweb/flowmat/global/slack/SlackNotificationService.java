package org.myweb.flowmat.global.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackNotificationService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final SlackProperties slackProperties;

    @Async
    public void sendProjectInviteAlert(String projectName, String inviterName,
                                       String invitedEmail, String role) {
        String message = String.format(
            "📬 *New project invite sent*%n• Project: %s%n• Inviter: %s%n• Invited: %s%n• Role: %s",
            projectName, inviterName, invitedEmail, role
        );
        send(message);
    }

    private void send(String message) {
        String webhookUrl = slackProperties.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            String body = "{\"text\": \"" + escaped + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Slack notification returned status {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to send Slack notification: {}", e.getMessage());
        }
    }
}
