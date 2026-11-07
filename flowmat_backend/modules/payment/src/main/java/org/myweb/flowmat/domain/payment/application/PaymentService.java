package org.myweb.flowmat.domain.payment.application;

import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.api.dto.response.PaymentResponse;
import org.myweb.flowmat.domain.payment.api.dto.response.SubscriptionResponse;

public interface PaymentService {

    List<PaymentResponse> getPaymentsByUserId(String userId);

    PaymentResponse getPayment(UUID paymentId);

    SubscriptionResponse getActiveSubscription(String userId);

    boolean hasActiveSubscription(String userId);
}
