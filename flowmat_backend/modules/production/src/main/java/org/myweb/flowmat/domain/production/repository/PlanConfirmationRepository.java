package org.myweb.flowmat.domain.production.repository;

import org.myweb.flowmat.domain.production.domain.entity.PlanConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanConfirmationRepository extends JpaRepository<PlanConfirmation, PlanConfirmation.Key> {
}
