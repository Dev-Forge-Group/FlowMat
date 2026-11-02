package org.myweb.flowmat.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** Broken JSON or a body that does not fit the request type. The cause stays out of the answer. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity
            .status(ErrorCode.BAD_REQUEST.getStatus())
            .body(ApiResponse.error("The request body could not be read."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity
            .status(ErrorCode.BAD_REQUEST.getStatus())
            .body(ApiResponse.error(e.getName() + " has an invalid value."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (e.getSupportedHttpMethods() != null) {
            response.allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(ApiResponse.error("Method " + e.getMethod() + " is not supported here."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(ApiResponse.error("This content type is not supported here."));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return ResponseEntity
            .status(ErrorCode.NOT_FOUND.getStatus())
            .body(ApiResponse.error(ErrorCode.NOT_FOUND.getMessage()));
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
