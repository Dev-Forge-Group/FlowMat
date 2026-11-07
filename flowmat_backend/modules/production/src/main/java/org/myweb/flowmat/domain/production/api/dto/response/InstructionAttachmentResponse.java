package org.myweb.flowmat.domain.production.api.dto.response;
import java.time.OffsetDateTime;
public record InstructionAttachmentResponse(String attachmentId,String instructionId,String fileName,String contentType,
    long sizeBytes,String sha256,String createdBy,OffsetDateTime createdAt) {}
