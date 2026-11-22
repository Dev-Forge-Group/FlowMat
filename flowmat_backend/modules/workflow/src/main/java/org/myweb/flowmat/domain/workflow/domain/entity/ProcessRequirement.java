package org.myweb.flowmat.domain.workflow.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** One execution requirement of a node (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4). */
@Getter
@Setter
@Entity
@Table(name = "process_requirement")
public class ProcessRequirement {
    @Id
    private String processRequirementId;
    private String processId;
    private int sortOrder;
    private String kind;
    private String ref;
    private BigDecimal amount;
    private String unit;
    private String per;
}
