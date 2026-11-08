package org.myweb.flowmat.domain.payment.application;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.payment.api.dto.response.PaymentResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.SubscriptionResponse;
import org.myweb.flowmat.domain.payment.domain.entity.Payment;
import org.myweb.flowmat.domain.payment.domain.entity.Subscription;
import org.myweb.flowmat.domain.payment.repository.PaymentRepository;
import org.myweb.flowmat.domain.payment.repository.SubscriptionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.rbac.PermissionService;
import org.myweb.flowmat.global.rbac.SystemPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PermissionService permissionService;

    @Override
    public List<PaymentResponse> getPaymentsByUserId(String userId) {
        requireSelfOrAdmin(userId);
        return paymentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Override
    public PaymentResponse getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Answer 404 rather than 403 so payment ids of other users cannot be probed.
        if (!isSelfOrAdmin(payment.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return PaymentResponse.from(payment);
    }

    @Override
    public SubscriptionResponse getActiveSubscription(String userId) {
        requireSelfOrAdmin(userId);
        Subscription subscription = subscriptionRepository.findByUserIdAndStatus(userId, "active")
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return SubscriptionResponse.from(subscription);
    }

    /** Internal check for other services; callers are responsible for authorization. */
    @Override
    public boolean hasActiveSubscription(String userId) {
        return subscriptionRepository.existsByUserIdAndStatus(userId, "active");
    }

    private void requireSelfOrAdmin(String userId) {
        if (!isSelfOrAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You can only view your own billing information.");
        }
    }

    private boolean isSelfOrAdmin(String userId) {
        String currentUserId = permissionService.requireCurrentUserId();
        return currentUserId.equals(userId) || permissionService.hasPermission(SystemPermission.USER_MANAGE);
    }
}
