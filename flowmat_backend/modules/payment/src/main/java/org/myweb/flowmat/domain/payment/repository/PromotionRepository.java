package org.myweb.flowmat.domain.payment.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findAllByPromotionStatusAndValidFromBeforeAndValidUntilAfter(
            String promotionStatus, OffsetDateTime from, OffsetDateTime until);
}
