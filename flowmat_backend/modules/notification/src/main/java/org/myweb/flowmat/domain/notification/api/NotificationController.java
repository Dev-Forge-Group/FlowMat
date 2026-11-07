package org.myweb.flowmat.domain.notification.api;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.notification.api.dto.response.NotificationResponse;
import org.myweb.flowmat.domain.notification.application.NotificationService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The current person's notifications (docs/domain/notification.md NT3). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<NotificationResponse>> mine(@RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(notificationService.mine(unreadOnly));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unreadCount() {
        return ApiResponse.ok(Map.of("unread", notificationService.unreadCount()));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markRead(@PathVariable String notificationId) {
        return ApiResponse.ok(notificationService.markRead(notificationId));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        return ApiResponse.ok(Map.of("marked", notificationService.markAllRead()));
    }
}
