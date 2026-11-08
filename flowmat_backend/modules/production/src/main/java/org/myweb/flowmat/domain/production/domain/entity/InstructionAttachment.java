package org.myweb.flowmat.domain.production.domain.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;
@Getter @Setter @Entity @Table(name="instruction_attachment")
public class InstructionAttachment extends CreatedUpdatedAuditEntity {
    @Id private String attachmentId;
    private String instructionId;
    private String storageType;
    private String storageKey;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
}
