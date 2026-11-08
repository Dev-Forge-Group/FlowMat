package org.myweb.flowmat.domain.purchasing.repository;

import java.util.List;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseApprovalStep;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseApprovalStepRepository extends JpaRepository<PurchaseApprovalStep, String> {
    List<PurchaseApprovalStep> findAllByProjectIdOrderByStepNoAsc(String projectId);
}
