package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A defect gathered on a nonconformity; a defect is on at most one. */
@Getter
@Setter
@Entity
@Table(name = "nonconformity_defect")
public class NonconformityDefect {

    @Id
    private String nonconformityDefectId;

    private String nonconformityId;
    private String defectLogId;
}
