package org.myweb.flowmat.domain.notification.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.notification.api.dto.request.NotificationSubscriptionRequest;
import org.myweb.flowmat.domain.notification.api.dto.response.NotificationSubscriptionResponse;
import org.myweb.flowmat.domain.notification.application.NotificationSubscriptionService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The current person's notification choices in a project (docs/domain/notification.md NS2). Project read. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/notification-subscriptions")
public class NotificationSubscriptionController {

    private final NotificationSubscriptionService service;

    @GetMapping
    public ApiResponse<List<NotificationSubscriptionResponse>> mine(
        @RequestParam(value = "projectId", required = false) String projectId
    ) {
        return ApiResponse.ok(service.mine(projectId));
    }

    @PutMapping
    public ApiResponse<List<NotificationSubscriptionResponse>> set(@RequestBody NotificationSubscriptionRequest request) {
        return ApiResponse.ok(service.set(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
            .body(ApiResponse.error("Send projectId, topic, subscribed as true or false and daysAhead as whole days."));
    }
}
