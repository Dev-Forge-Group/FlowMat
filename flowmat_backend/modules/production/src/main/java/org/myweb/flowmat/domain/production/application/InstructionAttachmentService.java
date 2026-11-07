package org.myweb.flowmat.domain.production.application;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.domain.production.api.dto.response.InstructionAttachmentResponse;
import org.myweb.flowmat.domain.production.domain.entity.InstructionAttachment;
import org.myweb.flowmat.domain.production.domain.entity.WorkInstruction;
import org.myweb.flowmat.domain.production.repository.InstructionAttachmentRepository;
import org.myweb.flowmat.domain.production.repository.WorkInstructionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.config.StorageProperties;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.storage.StorageService;
import org.myweb.flowmat.global.storage.UploadValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service @RequiredArgsConstructor @Slf4j
public class InstructionAttachmentService {
    private final InstructionAttachmentRepository attachments;
    private final WorkInstructionRepository instructions;
    private final ProjectAccessService access;
    private final StorageService storage;
    private final StorageProperties properties;
    public record Download(InstructionAttachmentResponse metadata,byte[] bytes) {}
    @Transactional(readOnly=true)
    public List<InstructionAttachmentResponse> list(String instructionId) {
        WorkInstruction i=readable(instructionId);
        return attachments.findAllByInstructionIdAndDeletedYnOrderByCreatedAtAscAttachmentIdAsc(i.getInstructionId(),"N").stream().map(InstructionAttachmentService::response).toList();
    }
    @Transactional
    public InstructionAttachmentResponse upload(String instructionId,String attachmentId,MultipartFile file) {
        WorkInstruction i=writable(instructionId);
        String id=uuid(attachmentId);
        UploadValidation.File upload;
        try { upload=UploadValidation.validate(file,properties); }
        catch(IOException e) { throw new BusinessException(ErrorCode.BAD_REQUEST,e.getMessage()); }
        String actor=access.requireCurrentUserId();
        InstructionAttachment previous=attachments.findById(id).orElse(null);
        if(previous!=null) {
            if(i.getInstructionId().equals(previous.getInstructionId()) && "N".equals(previous.getDeletedYn())
                && actor.equals(previous.getCreatedBy()) && upload.fileName().equals(previous.getFileName())
                && upload.contentType().equals(previous.getContentType()) && upload.bytes().length==previous.getSizeBytes()
                && upload.sha256().equals(previous.getSha256())) return response(previous);
            throw new BusinessException(ErrorCode.CONFLICT,"attachmentId already belongs to a different or removed upload. Reload attachments.");
        }
        draft(i);
        String key;
        try { key=storage.store(file,"instructions/"+i.getInstructionId()); }
        catch(IOException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"File storage is unavailable; retry with the same attachmentId and file."); }
        // A rollback owns only this new object; copied/released references are never removed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK) {
                    try { storage.delete(key); }
                    catch(IOException e) { log.warn("Instruction upload rollback cleanup failed; inspect unreferenced files."); }
                }
            }
        });
        InstructionAttachment a=new InstructionAttachment();
        a.setAttachmentId(id); a.setInstructionId(i.getInstructionId()); a.setStorageType(storage.type()); a.setStorageKey(key);
        a.setFileName(upload.fileName()); a.setContentType(upload.contentType()); a.setSizeBytes((long)upload.bytes().length); a.setSha256(upload.sha256()); a.setCreatedBy(actor);
        return response(attachments.saveAndFlush(a));
    }
    @Transactional(readOnly=true)
    public Download download(String instructionId,String attachmentId) {
        WorkInstruction i=readable(instructionId);
        InstructionAttachment a=attachment(i.getInstructionId(),attachmentId);
        if(!"N".equals(a.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        if(!storage.type().equals(a.getStorageType())) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"The attachment storage backend is not configured.");
        try(var in=storage.open(a.getStorageKey())) {
            if(a.getSizeBytes()>Integer.MAX_VALUE-1) throw new IOException("Invalid stored size.");
            byte[] bytes=in.readNBytes(a.getSizeBytes().intValue()+1);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(bytes.length!=a.getSizeBytes() || !hash.equals(a.getSha256())) throw new IOException("Stored content integrity check failed.");
            return new Download(response(a),bytes);
        } catch(IOException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"Stored attachment is unavailable. Retry the download or contact the project owner."); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional public void remove(String instructionId,String attachmentId) {
        WorkInstruction i=writable(instructionId); draft(i);
        InstructionAttachment a=attachment(i.getInstructionId(),attachmentId);
        if("Y".equals(a.getDeletedYn())) return;
        a.setDeletedYn("Y"); a.setUpdatedBy(access.requireCurrentUserId()); attachments.save(a);
        // Keep blobs for revision history/references. Retention cleanup is a separate administrative operation.
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void copyReferences(String sourceId,String copyId,String actor) {
        for(InstructionAttachment source:attachments.findAllByInstructionIdAndDeletedYnOrderByCreatedAtAscAttachmentIdAsc(sourceId,"N")) {
            InstructionAttachment a=new InstructionAttachment();
            a.setAttachmentId(UUID.randomUUID().toString()); a.setInstructionId(copyId); a.setStorageType(source.getStorageType()); a.setStorageKey(source.getStorageKey());
            a.setFileName(source.getFileName()); a.setContentType(source.getContentType()); a.setSizeBytes(source.getSizeBytes()); a.setSha256(source.getSha256()); a.setCreatedBy(actor); attachments.save(a);
        }
    }
    private WorkInstruction readable(String id) {
        WorkInstruction i=instructions.findByInstructionIdAndDeletedYn(id,"N").orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(i.getProjectId()); return i;
    }
    private WorkInstruction writable(String id) {
        WorkInstruction i=instructions.findForUpdate(id).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(i.getProjectId()); return i;
    }
    private static void draft(WorkInstruction i) {
        if(!"draft".equals(i.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"Attachments of released or retired instructions are immutable; make a new revision.");
    }
    private InstructionAttachment attachment(String instructionId,String id) {
        return attachments.findById(uuid(id)).filter(a->instructionId.equals(a.getInstructionId())).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
    }
    private static String uuid(String id) {
        try { String parsed=UUID.fromString(id).toString(); if(parsed.equals(id)) return parsed; }
        catch(IllegalArgumentException e) { /* rejected below */ }
        throw new BusinessException(ErrorCode.BAD_REQUEST,"attachmentId must be a canonical UUID.");
    }
    private static InstructionAttachmentResponse response(InstructionAttachment a) {
        return new InstructionAttachmentResponse(a.getAttachmentId(),a.getInstructionId(),a.getFileName(),a.getContentType(),a.getSizeBytes(),a.getSha256(),a.getCreatedBy(),a.getCreatedAt());
    }
}
