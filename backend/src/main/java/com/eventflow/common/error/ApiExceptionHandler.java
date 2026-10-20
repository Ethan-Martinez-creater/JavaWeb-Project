package com.eventflow.common.error;

import jakarta.validation.ConstraintViolationException;
import java.time.DateTimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> business(ApiException error) {
    return ResponseEntity.status(error.status())
        .body(ApiError.of(error.code(), error.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    ConstraintViolationException.class,
    DateTimeException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<ApiError> invalid(Exception ignored) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> integrity(DataIntegrityViolationException ignored) {
    return response(
        HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "Resource conflicts with existing data");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> denied(AccessDeniedException ignored) {
    return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ApiError> missing(Exception ignored) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ignored) {
    return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException ignored) {
    return response(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "JSON is required");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception error) {
    // Exception messages can contain SQL parameters or credentials; log only the error type.
    log.error("Unhandled request failure type={}", error.getClass().getName());
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
  }

  private ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(ApiError.of(code, message));
  }
}
