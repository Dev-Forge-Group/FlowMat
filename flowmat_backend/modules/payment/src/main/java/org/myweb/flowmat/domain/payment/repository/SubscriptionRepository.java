package org.myweb.flowmat.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserIdAndStatus(String userId, String status);

    boolean existsByUserIdAndStatus(String userId, String status);
}
