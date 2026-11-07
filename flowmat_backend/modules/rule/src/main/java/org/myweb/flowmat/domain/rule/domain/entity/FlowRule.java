package org.myweb.flowmat.domain.rule.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "flow_rule")
public class FlowRule extends CreatedUpdatedAuditEntity {

    @Id
    private String ruleId;

    private String projectId;
    private String targetType;
    private String targetId;
    private String ruleName;
    private String ruleDesc;
    private String conditionType;
    private String conditionExpression;
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_config", columnDefinition = "jsonb")
    private String actionConfig;
    private Integer priority;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String enabledYn;
}
