package org.myweb.flowmat.global.exception;

import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Upload failures for every multipart endpoint (instruction attachments today, docs/domain/work-instruction.md W9-W11).
 * Multipart parsing happens before a controller is chosen, so the limits need an app-wide handler ahead of
 * {@link GlobalExceptionHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(ApiResponse.error("file exceeds the configured upload limit."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> missing(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("file is required."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MultipartException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("file requires a valid multipart upload."));
    }
}
