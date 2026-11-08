package org.myweb.flowmat.domain.purchasing.repository;

import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseSettingRepository extends JpaRepository<PurchaseSetting, String> {
}
