package org.myweb.flowmat.domain.payment.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "plan")
public class Plan extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "plan_code", nullable = false, unique = true, length = 30)
    private String planCode;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    @Column(name = "plan_desc", columnDefinition = "text")
    private String planDesc;

    // 허용 사용자 수 (기본 1, 무제한 없음)
    @Column(name = "max_users", nullable = false)
    private Integer maxUsers = 1;

    // 허용 공장 수 (기본 1, 무제한 없음)
    @Column(name = "max_factories", nullable = false)
    private Integer maxFactories = 1;

    // active / inactive / deprecated
    @Column(name = "plan_status", nullable = false, length = 20)
    private String planStatus = "active";

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
