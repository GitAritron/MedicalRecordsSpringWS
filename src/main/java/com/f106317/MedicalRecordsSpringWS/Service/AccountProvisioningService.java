package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Bridges clinical records and Keycloak accounts. NOT @Transactional - Keycloak writes can't be rolled back by the DB.
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountProvisioningService {

    private final KeycloakAdminService keycloakAdminService;

    public record ProvisionedAccount(String keycloakId, String temporaryPassword) {
        public boolean created() {
            return keycloakId != null;
        }

        public static ProvisionedAccount none() {
            return new ProvisionedAccount(null, null);
        }
    }

    // Creates a Keycloak account if a username is provided and no account already exists.
    public ProvisionedAccount provisionIfRequested(String username,
                                                   String firstName, String lastName,
                                                   String existingKeycloakId,
                                                   String requestedKeycloakId) {
        if (username == null || username.isBlank()) {
            return ProvisionedAccount.none();
        }
        if (existingKeycloakId != null && !existingKeycloakId.isBlank()) {
            throw new IllegalArgumentException(
                    "This record is already linked to an account. Use Re-issue password, "
                    + "or clear the account UUID and save before creating a new account.");
        }
        if (requestedKeycloakId != null && !requestedKeycloakId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Supply either a username to create a new account or an existing account UUID "
                    + "to link, not both.");
        }

        String password = keycloakAdminService.generateTemporaryPassword();
        String keycloakId = keycloakAdminService.createUser(
                username.trim(), firstName, lastName, password);
        return new ProvisionedAccount(keycloakId, password);
    }

    // Best-effort: logs failures instead of failing the request. Record is already saved by this point.
    public void grantRoleQuietly(String keycloakId, String role) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return;
        }
        try {
            keycloakAdminService.assignRealmRole(keycloakId, role);
        } catch (Exception e) {
            log.warn("Could not assign {} role in Keycloak to '{}': {}", role, keycloakId, e.getMessage());
        }
    }

    // Best-effort revoke. A stale role still grants full API access, so this matters.
    public void revokeRoleQuietly(String keycloakId, String role) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return;
        }
        try {
            keycloakAdminService.removeRealmRole(keycloakId, role);
        } catch (Exception e) {
            log.warn("Could not revoke {} role in Keycloak from '{}': {}", role, keycloakId, e.getMessage());
        }
    }

    // Propagates failure here - unlike grants, reporting a password that doesn't work is unacceptable.
    public AccountCredentialsResponse reissuePassword(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            throw new IllegalArgumentException(
                    "This record is not linked to an account, so it has no password to re-issue.");
        }
        // Read username first - if Keycloak is down, nothing gets changed.
        String username = keycloakAdminService.getUsername(keycloakId);
        String password = keycloakAdminService.generateTemporaryPassword();
        keycloakAdminService.resetPassword(keycloakId, password);
        return new AccountCredentialsResponse(username, password);
    }
}
