package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Bridges a clinical record to the Keycloak account behind it.
 * <p>
 * WHAT: creates accounts, issues one-time passwords, and grants or revokes realm roles.
 * <p>
 * WHY it is a separate service and why nothing here is {@code @Transactional}: these are writes to a
 * second system that no database transaction can roll back. Calling them from inside a transactional
 * service method would mean a later rollback leaves a real account created, or a live user de-roled,
 * with the database showing no trace of either. Keeping the cross-system calls out here — invoked by
 * the controllers before and after the transactional work — makes the ordering explicit and the
 * failure modes survivable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountProvisioningService {

    private final KeycloakAdminService keycloakAdminService;

    /**
     * Outcome of a provisioning attempt.
     * @param keycloakId the new account's id, or null when no account was asked for
     * @param temporaryPassword the one-time password to hand over, or null
     */
    public record ProvisionedAccount(String keycloakId, String temporaryPassword) {
        public boolean created() {
            return keycloakId != null;
        }

        public static ProvisionedAccount none() {
            return new ProvisionedAccount(null, null);
        }
    }

    /**
     * Creates a Keycloak account when a username was supplied, otherwise does nothing.
     * <p>
     * A username is honoured only when the record has no account and the request is not linking one.
     * Creating a second account for someone who already has one silently strands the first, and the
     * two things an administrator actually wants in that situation — a new password, or a different
     * account — are re-issue and re-link, so the message names them.
     * <p>
     * WHY clearing the link and supplying a username in one request is refused rather than treated
     * as "replace the account": no workflow needs it. A lost password is a re-issue, a wrong account
     * is a re-link, and an account deleted outside this application is two obvious saves — clear,
     * then provision. Allowing it would make one request a compound revoke, create, link and grant
     * with four places to fail half-way, to serve nobody.
     *
     * @param existingKeycloakId what the record is linked to now (null when creating)
     * @param requestedKeycloakId the link field from the request, under the three-state rule
     */
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

    /**
     * Grants a realm role, logging rather than failing when Keycloak is unreachable.
     * WHY best-effort: the clinical record is already saved by this point, and an administrator can
     * grant the role by hand, so an identity-provider outage should not fail an otherwise
     * successful write — nor undo it.
     */
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

    /**
     * Revokes a realm role, logging rather than failing.
     * WHY it matters: without it, deleting a record or pointing it at a different account leaves the
     * previous holder still carrying the role. A stale DOCTOR role is not cosmetic — it keeps full
     * read access to every patient, visit and report through the API, because the front-end orphan
     * check is presentation and not enforcement.
     */
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

    /**
     * Issues a fresh one-time password for an existing account.
     * Unlike the role calls this propagates failure: the administrator asked for a password to hand
     * over, so reporting success without one would leave them passing on a value that does not work.
     */
    public AccountCredentialsResponse reissuePassword(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            throw new IllegalArgumentException(
                    "This record is not linked to an account, so it has no password to re-issue.");
        }
        // Read the username before touching the password: if the account cannot be read, nothing
        // has been changed yet, rather than a password reset that we then cannot report properly.
        String username = keycloakAdminService.getUsername(keycloakId);
        String password = keycloakAdminService.generateTemporaryPassword();
        keycloakAdminService.resetPassword(keycloakId, password);
        return new AccountCredentialsResponse(username, password);
    }
}
