package com.f106317.MedicalRecordsSpringWS.Exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            if (error instanceof FieldError fieldError) {
                errors.put(fieldError.getField(), fieldError.getDefaultMessage());
            } else {
                errors.put(error.getObjectName(), error.getDefaultMessage());
            }
        });
        ApiError apiError = new ApiError(
                "VALIDATION_ERROR",
                "Request validation failed",
                request.getRequestURI(),
                Instant.now(),
                errors
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "ACCESS_DENIED",
                ex.getMessage(),
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "INVALID_ARGUMENT",
                ex.getMessage(),
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUsernameAlreadyExists(UsernameAlreadyExistsException ex,
                                                                HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "USERNAME_TAKEN",
                ex.getMessage(),
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    // 502: Keycloak is down, not our bug. The clinical record itself is fine.
    @ExceptionHandler(KeycloakIntegrationException.class)
    public ResponseEntity<ApiError> handleKeycloakIntegrationFailure(KeycloakIntegrationException ex,
                                                                     HttpServletRequest request) {
        log.error("Keycloak integration failure at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        ApiError apiError = new ApiError(
                "IDENTITY_PROVIDER_UNAVAILABLE",
                "The identity provider could not be reached, so the account operation did not "
                        + "complete. The medical record itself was not affected. Try again shortly.",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("Data integrity violation at {}: {}", request.getRequestURI(), ex.getMessage());
        ApiError apiError = new ApiError(
                "DATA_CONFLICT",
                "The operation conflicts with existing data. A duplicate or constraint violation occurred.",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    // Concurrent edit detected - two transactions modified the same row. User should reload.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
                                                                   HttpServletRequest request) {
        log.warn("Concurrent modification at {}: {}", request.getRequestURI(), ex.getMessage());
        ApiError apiError = new ApiError(
                "CONCURRENT_MODIFICATION",
                "This record was changed by someone else while you were editing it. "
                        + "Reload it and apply your change again.",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "MALFORMED_REQUEST",
                "Request body is missing or malformed",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = String.format("Parameter '%s' should be of type '%s'",
                ex.getName(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        ApiError apiError = new ApiError(
                "TYPE_MISMATCH",
                message,
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    // Missing static file (favicon, JS, CSS) - proper 404 instead of falling to the catch-all 500.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                "RESOURCE_NOT_FOUND",
                "The requested resource does not exist",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        ApiError apiError = new ApiError(
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                request.getRequestURI(),
                Instant.now(),
                Map.of()
        );
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
