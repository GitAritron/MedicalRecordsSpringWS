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

// Manages Keycloak users and roles via the Admin REST API.
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

    // Unambiguous chars covering Keycloak's default password policy.
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public KeycloakAdminService() {
        // JDK HttpClient needed because HttpURLConnection can't send a body with DELETE.
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
    }

    public void assignRealmRole(String userId, String roleName) {
        changeRealmRoleMapping(userId, roleName, HttpMethod.POST, "assign");
    }

    // Without this, deleting a record leaves the user with a stale role that still has API access.
    public void removeRealmRole(String userId, String roleName) {
        changeRealmRoleMapping(userId, roleName, HttpMethod.DELETE, "remove");
    }

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

    // Creates a Keycloak user with a temporary password (must change on first login). Returns the new user's UUID.
    public String createUser(String username, String firstName, String lastName,
                             String temporaryPassword) {
        try {
            String adminToken = getAdminToken();

            // Built through Jackson to avoid injection in the JSON body.
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

    // Username lives only in Keycloak - we store only the UUID.
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
