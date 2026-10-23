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

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private DoctorRepository doctorRepository;

    // Real, not mocked - a mocked one makes every ownership assertion a no-op.
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

    @Test
    void getAllPatients() {
        when(patientRepository.findAll()).thenReturn(Arrays.asList(patient));
        assertEquals(1, patientService.getAllPatients().size());
    }

    @Test
    void getPatientById_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        PatientResponse response = patientService.getPatientById(1L, TestAuth.admin());
        assertEquals("John", response.firstName());
        assertEquals("Dr. House MD", response.gpDoctorName());
    }

    @Test
    void getPatientById_notFound() {
        when(patientRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> patientService.getPatientById(2L, TestAuth.admin()));
    }

    @Test
    void createPatient_success() {
        PatientRequest request = new PatientRequest(null, "8001156240", "Ivan", "Petrov", 1L, null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.createPatient(request, TestAuth.admin());
        assertEquals("Ivan", response.firstName());
        assertEquals("Petrov", response.lastName());
        assertEquals("8001156240", response.egn());
        assertEquals(1L, response.gpDoctorId());
    }

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

    @Test
    void deletePatient_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertDoesNotThrow(() -> patientService.deletePatient(1L, TestAuth.admin()));
        verify(patientRepository, times(1)).delete(any());
    }

    // The patient starts on doctor 1 (setUp), so moving to doctor 2 proves the GP actually changed.
    @Test
    void updatePatientGp_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(secondGp()));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse res = patientService.updatePatientGp(1L, 2L, TestAuth.admin());
        assertEquals(2L, res.gpDoctorId());
        assertEquals("Dr. Lisa Cuddy", res.gpDoctorName());
        verify(patientRepository, times(1)).save(any());
    }

    private static Doctor secondGp() {
        Doctor gp = new Doctor();
        gp.setId(2L);
        gp.setFirstName("Lisa");
        gp.setLastName("Cuddy");
        gp.setGP(true);
        return gp;
    }

    @Test
    void updatePatient_egnImmutable() {
        PatientRequest request = new PatientRequest(null, "9999999999", "Jane", "Smith", 1L, null);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertThrows(IllegalArgumentException.class, () -> patientService.updatePatient(1L, request, TestAuth.admin()));
    }

    @Test
    void createPatient_nonGpDoctor() {
        Doctor specialist = new Doctor();
        specialist.setId(2L);
        specialist.setGP(false);
        PatientRequest request = new PatientRequest(null, "1234567890", "John", "Doe", 2L, null);
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(specialist));
        assertThrows(IllegalArgumentException.class, () -> patientService.createPatient(request, TestAuth.admin()));
    }

    @Test
    void getPatientsByGp() {
        when(patientRepository.findByGpId(1L)).thenReturn(Arrays.asList(patient));
        assertEquals(1, patientService.getPatientsByGp(1L).size());
    }

    // ──────────────────────────────────────────────
    // Ownership rules
    // ──────────────────────────────────────────────

    // "Всеки пациент може да вижда собствената си история".
    @Test
    void getPatientById_patientCanReadOwnRecord() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        PatientResponse response = patientService.getPatientById(1L, TestAuth.patient("kc-patient-1"));
        assertEquals("John", response.firstName());
    }

    @Test
    void getPatientById_patientCannotReadAnotherPatientsRecord() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientById(1L, TestAuth.patient("kc-patient-2")));
    }

    // "Лекарите трябва да могат да виждат информацията за всички пациенти".
    @Test
    void getPatientById_doctorCanReadAnyPatient() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertEquals("John",
                patientService.getPatientById(1L, TestAuth.doctor("kc-doctor-9")).firstName());
    }

    @Test
    void updatePatientGp_patientCanChangeTheirOwn() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(secondGp()));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(2L, patientService.updatePatientGp(1L, 2L, TestAuth.patient("kc-patient-1")).gpDoctorId());
    }

    @Test
    void updatePatientGp_patientCannotChangeAnotherPatientsGp() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> patientService.updatePatientGp(1L, 1L, TestAuth.patient("kc-patient-2")));
        verify(patientRepository, never()).save(any());
    }

    // Changing GP is administrative, not clinical, so doctors are excluded - same as the endpoint rule.
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

    @Test
    void getPatientByKeycloakId_refusesWithoutAuthentication() {
        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientByKeycloakId("kc-patient-1", null));
        verify(patientRepository, never()).findByKeycloakId(any());
    }

    // A token with no realm roles is refused.
    @Test
    void getPatientByKeycloakId_refusesRolelessToken() {
        assertThrows(AccessDeniedException.class,
                () -> patientService.getPatientByKeycloakId("kc-patient-1", TestAuth.roleless("kc-nobody")));
    }

    // ──────────────────────────────────────────────
    // Self-registration
    // ──────────────────────────────────────────────

    // The request has no keycloakId at all - the token is the only source.
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

    // Repeating returns the existing record (200, nothing created). in case a user PATIENT grant fails.
    @Test
    void selfRegister_repeatReturnsTheCallersOwnRecordWithoutCreatingASecond() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);
        when(patientRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.of(patient));

        PatientService.SelfRegistration outcome =
                patientService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        assertFalse(outcome.created(), "a repeat must not report itself as a creation");
        assertEquals(patient.getId(), outcome.patient().id());
        verify(patientRepository, never()).save(any());
    }

    @Test
    void selfRegister_firstCallReportsItselfAsACreation() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);
        when(patientRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(patientService.selfRegister(request, TestAuth.patient("kc-sub-123")).created());
    }

    @Test
    void selfRegister_requiresAuthentication() {
        PatientSelfRegistrationRequest request =
                new PatientSelfRegistrationRequest("1234567890", "John", "Doe", null);

        assertThrows(AccessDeniedException.class, () -> patientService.selfRegister(request, null));
        verify(patientRepository, never()).save(any());
    }

    // Not left to the EGN unique constraint - a generic 409 leaves them stuck. The fix is an admin linking the existing record.
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
    // An absent field leaves the link, an empty one unlinks, a value sets it.
    // The admin form always sends the field, so empty there is a deliberate unlink. API callers may omit it.

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
