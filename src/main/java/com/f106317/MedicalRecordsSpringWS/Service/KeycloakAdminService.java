package com.f106317.MedicalRecordsSpringWS.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Service for managing Keycloak realm roles via the Admin REST API.
 * WHAT: Programmatically assigns realm roles to users after self-registration.
 * WHY: Allows patients to be auto-approved (assigned PATIENT role) without
 * requiring manual administrator intervention in the Keycloak Admin Console.
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

    public KeycloakAdminService() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Assigns a realm-level role to a Keycloak user.
     * @param userId The Keycloak user UUID (from JWT 'sub' claim)
     * @param roleName The role name to assign (e.g., "PATIENT")
     */
    public void assignRealmRole(String userId, String roleName) {
        try {
            String adminToken = getAdminToken();

            // Get the role representation
            String roleUrl = serverUrl + "/admin/realms/" + realm + "/roles/" + roleName;
            HttpHeaders roleHeaders = new HttpHeaders();
            roleHeaders.setBearerAuth(adminToken);
            ResponseEntity<String> roleResponse = restTemplate.exchange(
                    roleUrl, HttpMethod.GET, new HttpEntity<>(roleHeaders), String.class);

            if (!roleResponse.getStatusCode().is2xxSuccessful() || roleResponse.getBody() == null) {
                log.error("Failed to fetch role '{}' from Keycloak: {}", roleName, roleResponse.getStatusCode());
                throw new RuntimeException("Failed to fetch Keycloak role: " + roleName);
            }

            // Assign the role to the user
            String assignUrl = serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
            HttpHeaders assignHeaders = new HttpHeaders();
            assignHeaders.setBearerAuth(adminToken);
            assignHeaders.setContentType(MediaType.APPLICATION_JSON);

            // The API expects a JSON array of role representations
            String roleBody = "[" + roleResponse.getBody() + "]";
            HttpEntity<String> assignRequest = new HttpEntity<>(roleBody, assignHeaders);

            ResponseEntity<Void> assignResponse = restTemplate.exchange(
                    assignUrl, HttpMethod.POST, assignRequest, Void.class);

            if (assignResponse.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully assigned role '{}' to user '{}'", roleName, userId);
            } else {
                log.error("Failed to assign role '{}' to user '{}': {}", roleName, userId, assignResponse.getStatusCode());
                throw new RuntimeException("Failed to assign Keycloak role: " + assignResponse.getStatusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error assigning Keycloak role '{}' to user '{}': {}", roleName, userId, e.getMessage());
            throw new RuntimeException("Failed to assign Keycloak role: " + e.getMessage(), e);
        }
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
            throw new RuntimeException("Keycloak admin token request failed: " + response.getStatusCode());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting Keycloak admin token: {}", e.getMessage());
            throw new RuntimeException("Failed to obtain Keycloak admin token", e);
        }
    }
}
