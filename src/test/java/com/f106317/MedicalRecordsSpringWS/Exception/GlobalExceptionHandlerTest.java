package com.f106317.MedicalRecordsSpringWS.Exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleResourceNotFoundException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Not found");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        ResponseEntity<ApiError> response = exceptionHandler.handleResourceNotFoundException(ex, request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().code());
    }

    @Test
    void handleValidationExceptions() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("object", "field", "message");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(Collections.singletonList(fieldError));

        ResponseEntity<ApiError> response = exceptionHandler.handleValidationExceptions(ex, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().code());
        assertEquals("message", response.getBody().details().get("field"));
    }

    @Test
    void handleAccessDeniedException() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        ResponseEntity<ApiError> response = exceptionHandler.handleAccessDeniedException(new AccessDeniedException("Denied"), request);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACCESS_DENIED", response.getBody().code());
    }

    @Test
    void handleGenericException() {
        Exception ex = new Exception("Internal error");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        ResponseEntity<ApiError> response = exceptionHandler.handleGenericException(ex, request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_SERVER_ERROR", response.getBody().code());
    }

    @Test
    void handleDataIntegrityViolation() {
        org.springframework.dao.DataIntegrityViolationException ex = 
            new org.springframework.dao.DataIntegrityViolationException("Duplicate entry");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        ResponseEntity<ApiError> response = exceptionHandler.handleDataIntegrityViolation(ex, request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_CONFLICT", response.getBody().code());
    }

    @Test
    void handleMessageNotReadable() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
            new org.springframework.http.converter.HttpMessageNotReadableException("Bad body", (org.springframework.http.HttpInputMessage) null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        ResponseEntity<ApiError> response = exceptionHandler.handleMessageNotReadable(ex, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MALFORMED_REQUEST", response.getBody().code());
    }

    /**
     * A missing static file must be a 404, not a 500.
     * WHY: Spring raises NoResourceFoundException for anything the resource handler cannot
     * resolve — /favicon.ico, a mistyped .js or .css path. Without a dedicated handler it fell
     * through to the catch-all and every ordinary 404 was reported as an internal server error
     * with a stack trace in the log.
     */
    @Test
    void handleNoResourceFound_returns404() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/missing.js");
        NoResourceFoundException ex =
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/missing.js");

        ResponseEntity<ApiError> response = exceptionHandler.handleNoResourceFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().code());
    }

    /**
     * A lost-update rejection must be a 409, not a 500.
     * WHY: @Version on BaseEntity makes Hibernate refuse the second of two interleaved writes, so
     * one edit cannot silently erase the other. That refusal is the mechanism working, not a
     * server fault — reported as 500 it told the user nothing and logged as an internal error.
     */
    @Test
    void handleOptimisticLockingFailure_returns409() {
        OptimisticLockingFailureException ex =
                new OptimisticLockingFailureException("Row was updated by another transaction");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/doctors/5");

        ResponseEntity<ApiError> response = exceptionHandler.handleOptimisticLockingFailure(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().code());
        assertEquals("/api/doctors/5", response.getBody().path());
        assertTrue(response.getBody().message().toLowerCase().contains("reload"),
                "the message must tell the user what to do about it");
    }

    /**
     * Spring wraps Hibernate's OptimisticLockException as ObjectOptimisticLockingFailureException,
     * which is what actually reaches the handler at runtime. Catching the broader Spring type is
     * what makes that work — this pins it so a narrower catch cannot creep back in.
     */
    @Test
    void handleOptimisticLockingFailure_acceptsTheHibernateBackedSubclass() {
        ObjectOptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException("Doctor", 5L);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/doctors/5");

        ResponseEntity<ApiError> response = exceptionHandler.handleOptimisticLockingFailure(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONCURRENT_MODIFICATION", response.getBody().code());
    }

    /**
     * An unreachable Keycloak must be a 502, not a 500.
     * WHY: the failure is in an upstream dependency, not in this service. As a bare
     * RuntimeException it reached the catch-all and told the caller "an unexpected error occurred",
     * which is both wrong about whose fault it is and useless to act on.
     */
    @Test
    void handleKeycloakIntegrationFailure_returns502() {
        KeycloakIntegrationException ex =
                new KeycloakIntegrationException("Failed to obtain Keycloak admin token");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/doctors");

        ResponseEntity<ApiError> response = exceptionHandler.handleKeycloakIntegrationFailure(ex, request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("IDENTITY_PROVIDER_UNAVAILABLE", response.getBody().code());
        assertEquals("/api/doctors", response.getBody().path());
    }

    /**
     * The two Keycloak outcomes must stay on separate handlers: a taken username is a healthy
     * Keycloak refusing for a reason the administrator can fix (409), while an unreachable one is
     * an infrastructure failure (502). Folding them together was the original defect.
     */
    @Test
    void takenUsernameStaysA409AndDoesNotBecomeAGatewayError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/doctors");

        ResponseEntity<ApiError> response = exceptionHandler.handleUsernameAlreadyExists(
                new UsernameAlreadyExistsException("The username 'house' is already taken in Keycloak"),
                request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("USERNAME_TAKEN", response.getBody().code());
    }
}
