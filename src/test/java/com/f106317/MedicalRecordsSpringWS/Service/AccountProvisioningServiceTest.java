package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.DTO.AccountCredentialsResponse;
import com.f106317.MedicalRecordsSpringWS.Service.AccountProvisioningService.ProvisionedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules that decide when a clinical record gets a Keycloak account, and what happens to the
 * role when it does not.
 */
@ExtendWith(MockitoExtension.class)
class AccountProvisioningServiceTest {

    @Mock
    private KeycloakAdminService keycloakAdminService;

    @InjectMocks
    private AccountProvisioningService accountProvisioningService;

    @BeforeEach
    void setUp() {
        // Not stubbed here: several tests must prove no account was created at all.
    }

    // ── provisionIfRequested ───────────────────────────────────────────────

    @Test
    @DisplayName("no username means no account: a records-only patient is a normal outcome")
    void provision_withoutUsername_doesNothing() {
        ProvisionedAccount account = accountProvisioningService.provisionIfRequested(
                null, "Ivan", "Petrov", null, null);

        assertFalse(account.created());
        assertNull(account.keycloakId());
        assertNull(account.temporaryPassword());
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    void provision_withBlankUsername_doesNothing() {
        ProvisionedAccount account = accountProvisioningService.provisionIfRequested(
                "   ", "Ivan", "Petrov", null, null);

        assertFalse(account.created());
        verifyNoInteractions(keycloakAdminService);
    }

    @Test
    @DisplayName("a username on an unlinked record creates the account and returns its credentials")
    void provision_withUsername_createsAccount() {
        when(keycloakAdminService.generateTemporaryPassword()).thenReturn("Tmp12345678");
        when(keycloakAdminService.createUser("ipetrov", "Ivan", "Petrov", "Tmp12345678"))
                .thenReturn("kc-new");

        ProvisionedAccount account = accountProvisioningService.provisionIfRequested(
                "ipetrov", "Ivan", "Petrov", null, null);

        assertTrue(account.created());
        assertEquals("kc-new", account.keycloakId());
        assertEquals("Tmp12345678", account.temporaryPassword());
    }

    /** The username is trimmed before it reaches Keycloak, so a stray space cannot create a twin. */
    @Test
    void provision_trimsUsername() {
        when(keycloakAdminService.generateTemporaryPassword()).thenReturn("Tmp12345678");
        when(keycloakAdminService.createUser(any(), any(), any(), any())).thenReturn("kc-new");

        accountProvisioningService.provisionIfRequested(
                "  ipetrov  ", "Ivan", "Petrov", null, null);

        verify(keycloakAdminService).createUser("ipetrov", "Ivan", "Petrov", "Tmp12345678");
    }

    /**
     * WHY this is refused: a second account silently strands the first, leaving the previous one
     * holding a realm role with no record behind it.
     */
    @Test
    @DisplayName("a username on an already-linked record is refused, and creates nothing")
    void provision_onLinkedRecord_isRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> accountProvisioningService.provisionIfRequested(
                        "ipetrov", "Ivan", "Petrov", "kc-existing", null));

        assertTrue(error.getMessage().contains("already linked"));
        verify(keycloakAdminService, never()).createUser(any(), any(), any(), any());
    }

    /** Asking to link an existing account and create a new one in one request is ambiguous. */
    @Test
    void provision_whileAlsoLinking_isRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> accountProvisioningService.provisionIfRequested(
                        "ipetrov", "Ivan", "Petrov", null, "kc-other"));

        assertTrue(error.getMessage().contains("not both"));
        verify(keycloakAdminService, never()).createUser(any(), any(), any(), any());
    }

    /**
     * Clearing the link and supplying a username in the same request is deliberately refused
     * rather than treated as "replace the account" — see the note on provisionIfRequested. The
     * supported route is two saves: clear the link, then provision.
     */
    @Test
    void provision_whileClearingAnExistingLink_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> accountProvisioningService.provisionIfRequested(
                        "ipetrov", "Ivan", "Petrov", "kc-existing", ""));

        verify(keycloakAdminService, never()).createUser(any(), any(), any(), any());
    }

    // ── role changes are best-effort ───────────────────────────────────────

    /**
     * WHY swallowed: the clinical record is already written by the time these run. Failing here
     * would report an error for a write that did succeed, and could not undo it either.
     */
    @Test
    void grantRole_swallowsKeycloakFailure() {
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).assignRealmRole(anyString(), anyString());

        accountProvisioningService.grantRoleQuietly("kc-1", "PATIENT");

        verify(keycloakAdminService).assignRealmRole("kc-1", "PATIENT");
    }

    @Test
    void revokeRole_swallowsKeycloakFailure() {
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).removeRealmRole(anyString(), anyString());

        accountProvisioningService.revokeRoleQuietly("kc-1", "DOCTOR");

        verify(keycloakAdminService).removeRealmRole("kc-1", "DOCTOR");
    }

    /** A record with no account behind it has no role to change, so Keycloak is never called. */
    @Test
    void roleChangesOnUnlinkedRecordsAreSkipped() {
        accountProvisioningService.grantRoleQuietly(null, "PATIENT");
        accountProvisioningService.grantRoleQuietly("  ", "PATIENT");
        accountProvisioningService.revokeRoleQuietly(null, "DOCTOR");
        accountProvisioningService.revokeRoleQuietly("  ", "DOCTOR");

        verifyNoInteractions(keycloakAdminService);
    }

    // ── password re-issue ──────────────────────────────────────────────────

    /**
     * Unlike the role calls this propagates: the administrator asked for a password to hand over,
     * so reporting success without one would have them pass on a value that does not work.
     */
    @Test
    void reissuePassword_propagatesKeycloakFailure() {
        when(keycloakAdminService.generateTemporaryPassword()).thenReturn("Tmp12345678");
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).resetPassword(anyString(), anyString());

        assertThrows(RuntimeException.class,
                () -> accountProvisioningService.reissuePassword("kc-1"));
    }

    /**
     * The administrator is handed the name the person signs in with, not their name on the record
     * and not the account UUID — neither of which works at the sign-in screen. The username exists
     * only in Keycloak, so it is read back from there.
     */
    @Test
    void reissuePassword_returnsTheKeycloakUsernameAndNewPassword() {
        when(keycloakAdminService.getUsername("kc-1")).thenReturn("ipetrov");
        when(keycloakAdminService.generateTemporaryPassword()).thenReturn("Tmp12345678");

        AccountCredentialsResponse credentials = accountProvisioningService.reissuePassword("kc-1");

        assertEquals("ipetrov", credentials.username());
        assertEquals("Tmp12345678", credentials.temporaryPassword());
        verify(keycloakAdminService).resetPassword("kc-1", "Tmp12345678");
    }

    /**
     * The username is read before the password is reset, so an account that cannot be read leaves
     * the existing password working rather than resetting one we then cannot report.
     */
    @Test
    void reissuePassword_whenUsernameCannotBeRead_doesNotResetThePassword() {
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).getUsername(anyString());

        assertThrows(RuntimeException.class,
                () -> accountProvisioningService.reissuePassword("kc-1"));

        verify(keycloakAdminService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    void reissuePassword_onUnlinkedRecord_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> accountProvisioningService.reissuePassword(null));
        verifyNoInteractions(keycloakAdminService);
    }
}
