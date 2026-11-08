package org.myweb.flowmat.domain.workforce.repository;

import java.util.List;
import org.myweb.flowmat.domain.workforce.domain.entity.LaborPremiumRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaborPremiumRuleRepository extends JpaRepository<LaborPremiumRule, String> {

    List<LaborPremiumRule> findAllByProjectIdAndUserIdIsNullOrderBySetAtDescRuleIdDesc(String projectId);

    List<LaborPremiumRule> findAllByProjectIdAndUserIdOrderBySetAtDescRuleIdDesc(String projectId, String userId);
}
