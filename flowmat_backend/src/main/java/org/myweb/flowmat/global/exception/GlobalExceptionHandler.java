package org.myweb.flowmat.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity
            .status(e.getErrorCode().getStatus())
            .body(ApiResponse.error(resolveBusinessMessage(e)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(MissingServletRequestParameterException e) {
        return ResponseEntity
            .status(ErrorCode.BAD_REQUEST.getStatus())
            .body(ApiResponse.error(e.getParameterName() + " is required."));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        return ResponseEntity
            .status(ErrorCode.BAD_REQUEST.getStatus())
            .body(ApiResponse.error(resolveBindMessage(e)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        return ResponseEntity
            .status(ErrorCode.BAD_REQUEST.getStatus())
            .body(ApiResponse.error(resolveConstraintViolationMessage(e)));
    }

    /** Two writers raced on a {@code @Version} entity; the loser should reload rather than see a 500. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict ({})", e.getClass().getSimpleName());
        return ResponseEntity
            .status(ErrorCode.CONFLICT.getStatus())
            .body(ApiResponse.error(ErrorCode.CONFLICT.getMessage()));
    }

    /**
     * A unique or check constraint rejected the write, e.g. the same requestId sent twice at once. Services validate
     * first, so this is normally a race; retrying returns the stored result or a specific message.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.info("Constraint conflict ({})", e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity
            .status(ErrorCode.CONFLICT.getStatus())
            .body(ApiResponse.error("This conflicts with a change that was just saved. Reload and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        // Never echo unexpected exception messages: they can contain SQL, table names, or stack details.
        // The request id in the log line links the client-visible error to the full cause.
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.getStatus())
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    private static String resolveBusinessMessage(BusinessException e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : e.getErrorCode().getMessage();
    }

    private static String resolveBindMessage(BindException e) {
        if (e.getBindingResult().getFieldError() != null && e.getBindingResult().getFieldError().getDefaultMessage() != null) {
            return e.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ErrorCode.BAD_REQUEST.getMessage();
    }

    private static String resolveConstraintViolationMessage(ConstraintViolationException e) {
        return e.getConstraintViolations().stream()
            .findFirst()
            .map(violation -> violation.getMessage())
            .filter(message -> message != null && !message.isBlank())
            .orElse(ErrorCode.BAD_REQUEST.getMessage());
    }
}
