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

    /**
     * A Keycloak username collision. Distinct from the generic Keycloak failure because it is the
     * one an administrator can act on — they pick a different username and retry.
     */
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

    /**
     * Keycloak could not be reached, or answered with something unusable.
     * WHY 502 rather than the catch-all's 500: nothing is wrong with this service — an upstream
     * dependency it does not control is unavailable. Reporting it as 500 told the caller "we have
     * a bug" when the accurate message is "identity provider is down, the record itself is fine",
     * and it buried a whole class of operational failure in the generic handler's log line.
     *
     * A taken username is deliberately not routed here — see {@link UsernameAlreadyExistsException},
     * which is a healthy Keycloak giving an answer the administrator can act on.
     */
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

    /**
     * Two transactions edited the same row at once and the @Version check on BaseEntity rejected
     * the later one.
     * WHY this needs its own handler: without it the exception reaches the catch-all below and a
     * legitimate concurrency outcome is reported as 500 "An unexpected error occurred" — telling
     * the user nothing and logging it as a server fault. It is neither: the write was correctly
     * refused to stop it silently erasing the other edit, and reloading resolves it.
     *
     * Note this fires only when the two transactions genuinely interleave. A user who leaves an
     * edit form open while someone else saves is NOT caught, because the version is not part of
     * the request DTOs and so never round-trips through the browser.
     */
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

    /**
     * A request for a static file that does not exist.
     * WHY this needs its own handler: without it the exception falls through to the catch-all
     * below and a missing file is reported as 500 with a stack trace in the log. Spring raises
     * this for anything the resource handler cannot resolve — including /favicon.ico and any
     * mistyped .js or .css path — so the wrong status was returned on ordinary 404s.
     */
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
