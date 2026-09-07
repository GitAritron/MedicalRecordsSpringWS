package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.TestAuth;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PatientService}.
 * Verifies CRUD operations and GP-based queries with mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private DoctorRepository doctorRepository;

    // Real collaborator, not a mock. Mocking it away turned every ownership assertion into a
    // no-op, which is why the tests below could not have caught an authorization regression.
    @Spy
    private AuthorizationService authorizationService = new AuthorizationService();

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PatientService patientService;

    private Patient patient;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
        doctor.setId(1L);
        doctor.setFirstName("House");
        doctor.setLastName("MD");
        doctor.setGP(true);

        patient = new Patient();
        patient.setId(1L);
        patient.setEgn("1234567890");
        patient.setFirstName("John");
        patient.setLastName("Doe");
        patient.setGp(doctor);
    }

    /** Verifies that all patients are retrieved and mapped to DTOs. */
    @Test
    void getAllPatients() {
        when(patientRepository.findAll()).thenReturn(Arrays.asList(patient));
        assertEquals(1, patientService.getAllPatients().size());
    }

    /** Verifies successful retrieval of a patient by ID, including GP doctor name. */
    @Test
    void getPatientById_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        PatientResponse response = patientService.getPatientById(1L, TestAuth.admin());
        assertEquals("John", response.firstName());
        assertEquals("Dr. House MD", response.gpDoctorName());
    }

    /** Verifies that ResourceNotFoundException is thrown for non-existent patient. */
    @Test
    void getPatientById_notFound() {
        when(patientRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> patientService.getPatientById(2L, TestAuth.admin()));
    }

    /** Verifies a patient is created with the correct GP association. */
    @Test
    void createPatient_success() {
        PatientRequest request = new PatientRequest(null, "1234567890", "John", "Doe", 1L, null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenReturn(patient);
        
        PatientResponse response = patientService.createPatient(request, TestAuth.admin());
        assertEquals("John", response.firstName());
    }

    /** Verifies that updating a patient changes the persisted fields. */
    @Test
    void updatePatient_success() {
        PatientRequest request = new PatientRequest(null, "1234567890", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.updatePatient(1L, request, TestAuth.admin());
        assertEquals("Jane", response.firstName());
        assertEquals("1234567890", response.egn());
    }

    /** Verifies successful deletion of an existing patient. */
    @Test
    void deletePatient_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertDoesNotThrow(() -> patientService.deletePatient(1L, TestAuth.admin()));
        verify(patientRepository, times(1)).delete(any());
    }

    @Test
    void updatePatientGp_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse res = patientService.updatePatientGp(1L, 1L, TestAuth.admin());
        assertNotNull(res);
        verify(patientRepository, times(1)).save(any());
    }

    /** Verifies that EGN cannot be changed on update. */
    @Test
    void updatePatient_egnImmutable() {
        PatientRequest request = new PatientRequest(null, "9999999999", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertThrows(IllegalArgumentException.class, () -> patientService.updatePatient(1L, request, TestAuth.admin()));
    }

    /** Verifies that a non-GP doctor cannot be assigned as GP. */
    @Test
    void createPatient_nonGpDoctor() {
        Doctor specialist = new Doctor();
        specialist.setId(2L);
        specialist.setGP(false);
        PatientRequest request = new PatientRequest(null, "1234567890", "John", "Doe", 2L, null);
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(specialist));
        assertThrows(IllegalArgumentException.class, () -> patientService.createPatient(request, TestAuth.admin()));
    }

    /** Verifies retrieval of patients by their assigned GP doctor ID. */
    @Test
    void getPatientsByGp() {
        when(patientRepository.findByGpId(1L)).thenReturn(Arrays.asList(patient));
        assertEquals(1, patientService.getPatientsByGp(1L).size());
    }

    // ──────────────────────────────────────────────
    // Ownership rules (specification §2)
    // ──────────────────────────────────────────────
    // These could not previously be tested: AuthorizationService was mocked, so every assertion
    // it makes was a no-op and the tests passed regardless of the rules.

    /** "Всеки пациент може да вижда собствената си история" — a patient may read their own record. */
    @Test
    void getPatientById_patientCanReadOwnRecord() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.getPatientById(1L, TestAuth.patient("kc-patient-1"));
        assertEquals("John", response.firstName());
    }

    /** ...and only their own: a different patient guessing the id must be refused. */
    @Test
    void getPatientById_patientCannotReadAnotherPatientsRecord() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientById(1L, TestAuth.patient("kc-patient-2")));
    }

    /** "Лекарите трябва да могат да виждат информацията за всички пациенти" — doctors read all. */
    @Test
    void getPatientById_doctorCanReadAnyPatient() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertEquals("John",
                patientService.getPatientById(1L, TestAuth.doctor("kc-doctor-9")).firstName());
    }

    /** A patient may choose their own general practitioner. */
    @Test
    void updatePatientGp_patientCanChangeTheirOwn() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNotNull(patientService.updatePatientGp(1L, 1L, TestAuth.patient("kc-patient-1")));
    }

    @Test
    void updatePatientGp_patientCannotChangeAnotherPatientsGp() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> patientService.updatePatientGp(1L, 1L, TestAuth.patient("kc-patient-2")));
        verify(patientRepository, never()).save(any());
    }

    /**
     * Registering a patient with a GP is administrative, not clinical, so doctors are excluded —
     * matching the endpoint's own rule. The general patient-access check permits doctors, which is
     * why this operation uses a narrower one; without it the service would quietly allow more than
     * the controller does.
     */
    @Test
    void updatePatientGp_doctorCannotChangeGp() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> patientService.updatePatientGp(1L, 1L, TestAuth.doctor("kc-doctor-1")));
        verify(patientRepository, never()).save(any());
    }

    @Test
    void getPatientByKeycloakId_patientCannotReadAnotherProfile() {
        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientByKeycloakId("kc-patient-1", TestAuth.patient("kc-patient-2")));
        verify(patientRepository, never()).findByKeycloakId(any());
    }

    /**
     * Regression guard for the fail-open guard this method used to carry: it keyed on
     * isPatient(...), which is false for an absent token, so the ownership check was skipped
     * entirely rather than refusing.
     */
    @Test
    void getPatientByKeycloakId_refusesWithoutAuthentication() {
        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientByKeycloakId("kc-patient-1", null));
        verify(patientRepository, never()).findByKeycloakId(any());
    }

    /** A token carrying no realm roles must not be treated as staff either. */
    @Test
    void getPatientByKeycloakId_refusesRolelessToken() {
        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientByKeycloakId("kc-patient-1", TestAuth.roleless("kc-nobody")));
    }

    // ──────────────────────────────────────────────
    // Self-registration
    // ──────────────────────────────────────────────

    /**
     * Verifies self-registration binds the record to the caller's token subject.
     * WHY: PatientSelfRegistrationRequest carries no keycloakId at all, so the only possible
     * source is the verified token — a user cannot attach a profile to another account.
     */
    @Test
    void selfRegister_bindsToTokenSubject() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);
        when(patientRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

        patientService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        ArgumentCaptor<Patient> saved = ArgumentCaptor.forClass(Patient.class);
        verify(patientRepository).save(saved.capture());
        assertEquals("kc-sub-123", saved.getValue().getKeycloakId());
    }

    /** Verifies a second self-registration for the same account is rejected. */
    @Test
    void selfRegister_rejectsDuplicateProfile() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);
        when(patientRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.of(patient));

        assertThrows(IllegalArgumentException.class,
                () -> patientService.selfRegister(request, TestAuth.patient("kc-sub-123")));
        verify(patientRepository, never()).save(any());
    }

    /** Verifies self-registration refuses to proceed without an authenticated identity. */
    @Test
    void selfRegister_requiresAuthentication() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);

        assertThrows(AccessDeniedException.class, () -> patientService.selfRegister(request, null));
        verify(patientRepository, never()).save(any());
    }

    /**
     * Someone whose record an administrator already entered must be told what to do about it.
     * WHY not left to the EGN unique constraint: that surfaces as a generic "duplicate or
     * constraint violation" 409, which leaves them stuck with no role and no next step — the way
     * out is an administrator linking the existing record, not a second one.
     */
    @Test
    void selfRegister_whenEgnAlreadyRecorded_explainsHowToProceed() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);
        when(patientRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(patientRepository.existsByEgn("1234567890")).thenReturn(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> patientService.selfRegister(request, TestAuth.patient("kc-sub-123")));

        assertTrue(error.getMessage().contains("administrator"));
        verify(patientRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────
    // Which Keycloak account a record belongs to
    // ──────────────────────────────────────────────
    // Three states, because an absent field and an empty one mean different things. The
    // administrator's form always sends the field, prefilled, so an empty box there is a
    // deliberate unlink — while an API caller sending a partial body omits it entirely and must
    // not have the link torn out from under them.

    @Test
    void updatePatient_absentKeycloakId_leavesTheLinkAlone() {
        patient.setKeycloakId("kc-existing");
        PatientRequest request = new PatientRequest(null, "1234567890", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.updatePatient(1L, request, TestAuth.admin());

        assertEquals("kc-existing", response.keycloakId());
    }

    @Test
    void updatePatient_emptyKeycloakId_unlinksTheAccount() {
        patient.setKeycloakId("kc-existing");
        PatientRequest request = new PatientRequest("", "1234567890", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.updatePatient(1L, request, TestAuth.admin());

        assertNull(response.keycloakId());
    }

    @Test
    void updatePatient_newKeycloakId_relinksTheAccount() {
        patient.setKeycloakId("kc-existing");
        PatientRequest request = new PatientRequest("  kc-other  ", "1234567890", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.updatePatient(1L, request, TestAuth.admin());

        assertEquals("kc-other", response.keycloakId());
    }
}
