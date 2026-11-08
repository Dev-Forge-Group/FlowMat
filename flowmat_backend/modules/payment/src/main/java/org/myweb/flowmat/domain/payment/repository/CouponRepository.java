package org.myweb.flowmat.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;
import org.myweb.flowmat.domain.payment.domain.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCouponCodeAndCouponStatus(String couponCode, String couponStatus);
}
