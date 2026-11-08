package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** A member who approves a project's purchase orders worth a value or more (ADR-027 decision 21, V122). */
@Getter
@Setter
@Entity
@Table(name = "purchase_approval_step")
public class PurchaseApprovalStep {
    @Id
    private String stepId;
    private String projectId;
    /** 1 up, lowest value first. */
    private Integer stepNo;
    private BigDecimal fromValue;
    private String approverId;
}
