package com.backend.exception;

import com.backend.auth.dto.ErrorResponse;
import com.backend.auth.dto.FieldErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateUsername(DuplicateUsernameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_USERNAME", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_EMAIL", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailed(AuthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("AUTHENTICATION_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> errors = new ArrayList<FieldErrorDetail>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorDetail(error.getField(), error.getDefaultMessage()));
        }
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "입력값 검증에 실패했습니다.", errors));
    }

    @ExceptionHandler(VideoValidationException.class)
    public ResponseEntity<ErrorResponse> handleVideoValidation(VideoValidationException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingFile(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_MISSING", "업로드할 영상 파일이 없습니다."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("file size exceeded. spring multipart limit");
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_SIZE_EXCEEDED", "업로드 가능한 최대 파일 크기를 초과했습니다."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof MaxUploadSizeExceededException) {
                log.warn("file size exceeded. spring multipart limit");
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("FILE_SIZE_EXCEEDED", "업로드 가능한 최대 파일 크기를 초과했습니다."));
            }
            cause = cause.getCause();
        }
        log.warn("invalid file. multipart request failed");
        log.warn(ex.toString());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("FILE_MISSING", "업로드할 영상 파일이 없습니다."));
    }

    @ExceptionHandler(InvalidVideoException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVideo(InvalidVideoException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_VIDEO", ex.getMessage()));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("S3_UPLOAD_FAILED", "영상 업로드에 실패했습니다."));
    }

    @ExceptionHandler(FfprobeExecutionException.class)
    public ResponseEntity<ErrorResponse> handleFfprobe(FfprobeExecutionException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("FFPROBE_FAILED", "영상 정보를 확인할 수 없습니다."));
    }

    @ExceptionHandler(VideoSaveFailedException.class)
    public ResponseEntity<ErrorResponse> handleVideoSaveFailed(VideoSaveFailedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("VIDEO_SAVE_FAILED", "영상 저장에 실패했습니다."));
    }
}
