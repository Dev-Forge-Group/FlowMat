package org.myweb.flowmat.domain.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Payment> findByPgTransactionId(String pgTransactionId);
}
