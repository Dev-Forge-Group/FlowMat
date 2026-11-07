package org.myweb.flowmat.domain.payment.api;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.payment.api.dto.response.PaymentResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.SubscriptionResponse;
import org.myweb.flowmat.domain.payment.application.PaymentService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    // 유저 결제 내역 조회
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getPayments(
        @PathVariable("userId") String userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getPaymentsByUserId(userId)));
    }

    // 결제 단건 조회
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@PathVariable("paymentId") UUID paymentId) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getPayment(paymentId)));
    }

    // 유저 활성 구독 조회
    @GetMapping("/subscriptions/{userId}/active")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getActiveSubscription(
        @PathVariable("userId") String userId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getActiveSubscription(userId)));
    }
}
