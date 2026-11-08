package org.myweb.flowmat.domain.production.api;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.application.InstructionAttachmentService;
import org.myweb.flowmat.domain.production.api.dto.response.InstructionAttachmentResponse;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequiredArgsConstructor
@RequestMapping("/work-instructions/{instructionId}/attachments")
public class InstructionAttachmentController {
    private final InstructionAttachmentService attachments;
    @GetMapping public ApiResponse<List<InstructionAttachmentResponse>> list(@PathVariable String instructionId) { return ApiResponse.ok(attachments.list(instructionId)); }
    @PutMapping(value="/{attachmentId}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InstructionAttachmentResponse> upload(@PathVariable String instructionId,@PathVariable String attachmentId,@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(attachments.upload(instructionId,attachmentId,file));
    }
    @DeleteMapping("/{attachmentId}") public ApiResponse<Void> remove(@PathVariable String instructionId,@PathVariable String attachmentId) { attachments.remove(instructionId,attachmentId);return ApiResponse.ok(null); }
    @GetMapping("/{attachmentId}/download") public ResponseEntity<byte[]> download(@PathVariable String instructionId,@PathVariable String attachmentId) {
        var file=attachments.download(instructionId,attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.metadata().contentType())).contentLength(file.bytes().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.metadata().fileName(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(file.bytes());
    }
}
