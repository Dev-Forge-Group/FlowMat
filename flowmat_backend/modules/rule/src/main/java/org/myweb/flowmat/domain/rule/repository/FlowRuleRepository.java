package org.myweb.flowmat.domain.rule.repository;

import java.util.List;
import java.util.Optional;
import org.myweb.flowmat.domain.rule.domain.entity.FlowRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowRuleRepository extends JpaRepository<FlowRule, String> {

    List<FlowRule> findAllByDeletedYnOrderByPriorityDescCreatedAtAsc(String deletedYn);

    List<FlowRule> findAllByProjectIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(String projectId, String deletedYn);

    List<FlowRule> findAllByTargetTypeAndTargetIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(
        String targetType,
        String targetId,
        String deletedYn
    );

    List<FlowRule> findAllByProjectIdAndTargetTypeAndTargetIdAndDeletedYnOrderByPriorityDescCreatedAtAsc(
        String projectId,
        String targetType,
        String targetId,
        String deletedYn
    );

    Optional<FlowRule> findByRuleIdAndDeletedYn(String ruleId, String deletedYn);
}
