package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** One revision of a product's work instruction (docs/domain/work-instruction.md): draft → released → retired. */
@Getter
@Setter
@Entity
@Table(name = "work_instruction")
public class WorkInstruction extends CreatedUpdatedAuditEntity {

    @Id
    private String instructionId;

    private String projectId;
    /** The product the instruction is for. */
    private String itemId;
    private Integer revisionNo;
    private String status;
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    private String documentUrl;

    /** "Y": a run working to this revision cannot finish until its required steps are confirmed. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "blocks_finish_yn", length = 1)
    private String blocksFinishYn = "N";

    private String releasedBy;
    private OffsetDateTime releasedAt;
}
