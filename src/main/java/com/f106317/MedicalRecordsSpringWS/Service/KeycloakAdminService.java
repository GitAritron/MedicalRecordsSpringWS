package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Exception.KeycloakIntegrationException;
import com.f106317.MedicalRecordsSpringWS.Exception.UsernameAlreadyExistsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service for managing Keycloak users and realm roles via the Admin REST API.
 * WHAT: Creates accounts, assigns and revokes realm roles, and resets passwords.
 * WHY: An administrator entering a patient or doctor record needs to give that person portal
 * access without leaving the application for the Keycloak console, and the reverse — revoking a
 * role when a record is deleted or re-linked — is what stops the two systems drifting apart.
 */
@Service
@Slf4j
public class KeycloakAdminService {

    @Value("${keycloak.admin.server-url}")
    private String serverUrl;

    @Value("${keycloak.admin.realm}")
    private String realm;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Characters for generated passwords: unambiguous, and covering Keycloak's default policy. */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public KeycloakAdminService() {
        // JDK HttpClient rather than SimpleClientHttpRequestFactory: revoking a realm role is a
        // DELETE that carries the role representation in its body, and HttpURLConnection refuses to
        // write one ("HTTP method DELETE doesn't support output"). This factory has no such limit
        // and needs no dependency beyond spring-web.
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Assigns a realm-level role to a Keycloak user.
     * @param userId The Keycloak user UUID (from JWT 'sub' claim)
     * @param roleName The role name to assign (e.g., "PATIENT")
     */
    public void assignRealmRole(String userId, String roleName) {
        changeRealmRoleMapping(userId, roleName, HttpMethod.POST, "assign");
    }

    /**
     * Removes a realm-level role from a Keycloak user.
     * WHY it exists: without it the application only ever grants. Deleting a clinical record or
     * pointing it at a different account would leave the previous holder still carrying the role —
     * and a stale DOCTOR role keeps full read access to every patient, visit and report through the
     * API, because the front-end orphan check is presentation, not enforcement.
     */
    public void removeRealmRole(String userId, String roleName) {
        changeRealmRoleMapping(userId, roleName, HttpMethod.DELETE, "remove");
    }

    /**
     * Adds or removes one realm role mapping. Both directions hit the same endpoint with the same
     * body — an array holding the role representation — and differ only in HTTP method.
     */
    private void changeRealmRoleMapping(String userId, String roleName, HttpMethod method, String verb) {
        try {
            String adminToken = getAdminToken();
            String roleRepresentation = fetchRoleRepresentation(adminToken, roleName);

            String mappingUrl = serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // The API expects a JSON array of role representations.
            HttpEntity<String> request = new HttpEntity<>("[" + roleRepresentation + "]", headers);
            ResponseEntity<Void> response = restTemplate.exchange(mappingUrl, method, request, Void.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully {}ed role '{}' for user '{}'", verb, roleName, userId);
            } else {
                log.error("Failed to {} role '{}' for user '{}': {}", verb, roleName, userId, response.getStatusCode());
                throw new KeycloakIntegrationException("Failed to " + verb + " Keycloak role: " + response.getStatusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error trying to {} Keycloak role '{}' for user '{}': {}", verb, roleName, userId, e.getMessage());
            throw new KeycloakIntegrationException("Failed to " + verb + " Keycloak role: " + e.getMessage(), e);
        }
    }

    /** Reads one realm role as raw JSON, for use as the body of a role-mapping call. */
    private String fetchRoleRepresentation(String adminToken, String roleName) {
        String roleUrl = serverUrl + "/admin/realms/" + realm + "/roles/" + roleName;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> response = restTemplate.exchange(
                roleUrl, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("Failed to fetch role '{}' from Keycloak: {}", roleName, response.getStatusCode());
            throw new KeycloakIntegrationException("Failed to fetch Keycloak role: " + roleName);
        }
        return response.getBody();
    }

    /**
     * Creates an enabled Keycloak account whose password must be changed at first sign-in.
     * WHAT: posts a user with a {@code temporary} credential and returns the new user's UUID.
     * WHY temporary: it is what makes Keycloak force UPDATE_PASSWORD on first login, so an
     * administrator can hand over a one-time password and never learns the person's real one.
     * Core Keycloak has no admin endpoint returning a single-use magic link — those are delivered
     * by e-mail and need SMTP — so a temporary credential is the supported way to do this.
     *
     * <p>
     * WHY no email: this realm has no SMTP server, {@code resetPasswordAllowed} is false and
     * {@code verifyEmail} is false, so an address set here could never be read back, mailed to, or
     * used to recover an account. It would be a write-only attribute, and a field that looks like it
     * does something while doing nothing is worse than no field. Administrators who want addresses
     * on accounts can set them in the Keycloak console.
     *
     * @return the UUID Keycloak assigned, read from the {@code Location} response header
     * @throws UsernameAlreadyExistsException if the realm already has that username
     */
    public String createUser(String username, String firstName, String lastName,
                             String temporaryPassword) {
        try {
            String adminToken = getAdminToken();

            // Built through Jackson rather than string concatenation: every value here is
            // administrator input and would otherwise be an injection point into the request body.
            Map<String, Object> credential = Map.of(
                    "type", "password", "value", temporaryPassword, "temporary", true);
            Map<String, Object> user = new java.util.LinkedHashMap<>();
            user.put("username", username);
            user.put("enabled", true);
            user.put("credentials", List.of(credential));
            if (firstName != null && !firstName.isBlank()) {
                user.put("firstName", firstName.trim());
            }
            if (lastName != null && !lastName.isBlank()) {
                user.put("lastName", lastName.trim());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(user), headers);

            ResponseEntity<Void> response = restTemplate.exchange(
                    serverUrl + "/admin/realms/" + realm + "/users",
                    HttpMethod.POST, request, Void.class);

            URI location = response.getHeaders().getLocation();
            if (!response.getStatusCode().is2xxSuccessful() || location == null) {
                log.error("Keycloak did not return a user location for '{}': {}",
                        username, response.getStatusCode());
                throw new KeycloakIntegrationException("Keycloak did not report the created user's id");
            }

            String path = location.getPath();
            String userId = path.substring(path.lastIndexOf('/') + 1);
            log.info("Created Keycloak user '{}' with id '{}'", username, userId);
            return userId;
        } catch (HttpClientErrorException.Conflict e) {
            throw new UsernameAlreadyExistsException(
                    "The username '" + username + "' is already taken in Keycloak");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating Keycloak user '{}': {}", username, e.getMessage());
            throw new KeycloakIntegrationException("Failed to create Keycloak user: " + e.getMessage(), e);
        }
    }

    /**
     * Replaces a user's password with a temporary one they must change at next sign-in.
     * WHY: the generated password is shown to the administrator once and never stored, so losing it
     * before passing it on would otherwise mean a trip to the Keycloak console.
     */
    public void resetPassword(String userId, String temporaryPassword) {
        try {
            String adminToken = getAdminToken();

            Map<String, Object> credential = Map.of(
                    "type", "password", "value", temporaryPassword, "temporary", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(credential), headers);

            ResponseEntity<Void> response = restTemplate.exchange(
                    serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password",
                    HttpMethod.PUT, request, Void.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Failed to reset password for user '{}': {}", userId, response.getStatusCode());
                throw new KeycloakIntegrationException("Failed to reset Keycloak password: " + response.getStatusCode());
            }
            log.info("Reset password for Keycloak user '{}'", userId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resetting password for user '{}': {}", userId, e.getMessage());
            throw new KeycloakIntegrationException("Failed to reset Keycloak password: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the username of an existing account.
     * WHY it must be asked of Keycloak: the username lives there and nowhere else — this
     * application stores only the account's UUID. An administrator re-issuing a password needs the
     * name the person actually types at the sign-in screen, which is neither the UUID nor the name
     * on their medical record.
     */
    public String getUsername(String userId) {
        try {
            String adminToken = getAdminToken();

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(adminToken);
            ResponseEntity<String> response = restTemplate.exchange(
                    serverUrl + "/admin/realms/" + realm + "/users/" + userId,
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Failed to read Keycloak user '{}': {}", userId, response.getStatusCode());
                throw new KeycloakIntegrationException("Failed to read Keycloak user: " + response.getStatusCode());
            }

            JsonNode username = objectMapper.readTree(response.getBody()).get("username");
            if (username == null || username.isNull()) {
                throw new KeycloakIntegrationException("Keycloak user " + userId + " has no username");
            }
            return username.asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error reading Keycloak user '{}': {}", userId, e.getMessage());
            throw new KeycloakIntegrationException("Failed to read Keycloak user: " + e.getMessage(), e);
        }
    }

    /** A random password for a brand-new or reset account; the holder must change it immediately. */
    public String generateTemporaryPassword() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private String getAdminToken() {
        try {
            String tokenUrl = serverUrl + "/realms/master/protocol/openid-connect/token";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "password");
            body.add("client_id", "admin-cli");
            body.add("username", adminUsername);
            body.add("password", adminPassword);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                return json.get("access_token").asText();
            }
            throw new KeycloakIntegrationException("Keycloak admin token request failed: " + response.getStatusCode());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting Keycloak admin token: {}", e.getMessage());
            throw new KeycloakIntegrationException("Failed to obtain Keycloak admin token", e);
        }
    }
}
