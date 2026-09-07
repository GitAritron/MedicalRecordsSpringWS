package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.TestAuth;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorSelfRegistrationRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PendingDoctorResponse;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.SpecializationRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DoctorService}.
 * WHAT: Uses Mockito to isolate the Service layer from the database by mocking repositories.
 * WHY: We test the Service layer in isolation to ensure the business logic (mapping entities, 
 * throwing exceptions, calculating reports) works perfectly without relying on an actual MySQL connection.
 */
@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private KeycloakAdminService keycloakAdminService;

    @Mock
    private VisitRepository visitRepository;

    // Real collaborator, not a mock: extractSubject() has no dependencies, and self-registration
    // tests are meaningless if the identity extraction they rely on is stubbed out.
    @Spy
    private AuthorizationService authorizationService = new AuthorizationService();

    @InjectMocks
    private DoctorService doctorService;

    private Doctor doctor;

    @BeforeEach
    void setUp() {
        doctor = new Doctor();
        doctor.setId(1L);
        doctor.setFirstName("John");
        doctor.setLastName("Doe");
        doctor.setGP(true);
        doctor.setSpecializations(new HashSet<>());
    }

    /** 
     * WHAT: Verifies that all doctors are retrieved and mapped to DTOs. 
     * WHY: Ensures the API doesn't leak raw database entities to the frontend.
     */
    @Test
    void getAllDoctors() {
        when(doctorRepository.findAll()).thenReturn(Arrays.asList(doctor));
        assertEquals(1, doctorService.getAllDoctors().size());
    }

    /** 
     * WHAT: Verifies successful retrieval of a doctor by ID. 
     * WHY: Ensures the `fromEntity` mapping works correctly for a valid database record.
     */
    @Test
    void getDoctorById_success() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        DoctorResponse response = doctorService.getDoctorById(1L);
        assertEquals("John", response.firstName());
    }

    /** 
     * WHAT: Verifies that ResourceNotFoundException is thrown for a non-existent doctor. 
     * WHY: We must guarantee that invalid IDs are caught and handled by our global exception 
     * handler instead of causing an internal server error (500).
     */
    @Test
    void getDoctorById_notFound() {
        when(doctorRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> doctorService.getDoctorById(2L));
    }

    /** 
     * WHAT: Verifies that a doctor is successfully created from a DTO request. 
     * WHY: Ensures the bidirectional data binding (setting the doctor on the entity) correctly 
     * calls `save()` on the mock repository.
     */
    @Test
    void createDoctor() {
        DoctorRequest request = new DoctorRequest(null, "John", "Doe", true, Set.of());
        when(doctorRepository.save(any())).thenReturn(doctor);
        DoctorResponse response = doctorService.createDoctor(request, TestAuth.admin());
        assertNotNull(response);
        assertEquals("John", response.firstName());
    }

    /** 
     * WHAT: Verifies that updating a doctor correctly applies the new fields. 
     * WHY: Tests that the method fetches the existing doctor, mutates it, and saves it back 
     * without creating a duplicate record.
     */
    @Test
    void updateDoctor() {
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", false, Set.of());
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DoctorResponse response = doctorService.updateDoctor(1L, request, TestAuth.admin());
        assertEquals("Jane", response.firstName());
    }

    /** 
     * WHAT: Verifies successful deletion of an existing doctor. 
     * WHY: Validates that the service correctly checks for existence before calling delete.
     */
    @Test
    void deleteDoctor_success() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.countByDoctorId(1L)).thenReturn(0L);
        assertDoesNotThrow(() -> doctorService.deleteDoctor(1L, TestAuth.admin()));
        verify(doctorRepository, times(1)).delete(any());
    }

    /** 
     * WHAT: Verifies that deleting a non-existent doctor throws an exception. 
     * WHY: Prevents the system from executing invalid DELETE SQL queries.
     */
    @Test
    void deleteDoctor_notFound() {
        when(doctorRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> doctorService.deleteDoctor(2L, TestAuth.admin()));
    }

    /**
     * WHAT: a doctor with recorded visits cannot be deleted.
     * WHY: visit.doctor_id is ON DELETE CASCADE, so the database would remove their visits — and
     * the diagnoses, prescriptions and sick leaves beneath them — as a side effect. That erases
     * patient medical history, which belongs to the patient rather than the practitioner.
     */
    @Test
    void deleteDoctor_refusedWhenDoctorHasVisits() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.countByDoctorId(1L)).thenReturn(3L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> doctorService.deleteDoctor(1L, TestAuth.admin()));

        assertTrue(ex.getMessage().contains("3 recorded visit"), ex.getMessage());
        verify(doctorRepository, never()).delete(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    /** A doctor who has never seen a patient carries no history, so deletion is allowed. */
    @Test
    void deleteDoctor_allowedWhenDoctorHasNoVisits() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.countByDoctorId(1L)).thenReturn(0L);

        doctorService.deleteDoctor(1L, TestAuth.admin());

        verify(doctorRepository).delete(doctor);
        verify(auditLogService).log(any(), eq("DELETE"), eq("Doctor"), eq(1L), eq("Dr. John Doe"));
    }

    /** 
     * WHAT: Verifies the custom JPQL query execution for the visit count report. 
     * WHY: Ensures the Object[] array returned by Spring Data JPA is successfully 
     * cast and mapped into a typed `DoctorVisitCount` DTO record.
     */
    @Test
    void getDoctorsWithVisitCount() {
        when(doctorRepository.findDoctorsWithVisitCount()).thenReturn(java.util.Collections.singletonList(new Object[]{doctor, 10L}));
        assertEquals(1, doctorService.getDoctorsWithVisitCount().size());
    }

    // ──────────────────────────────────────────────
    // Approval of self-registered doctors
    // ──────────────────────────────────────────────

    /**
     * WHAT: Approval grants the DOCTOR realm role and records that it happened.
     * WHY: the APPROVE_DOCTOR audit entry is what removes the doctor from the pending list, so
     * without it the administrator's banner would never clear.
     */
    @Test
    void approveDoctor_grantsRoleAndRecordsApproval() {
        doctor.setKeycloakId("kc-doctor-1");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        doctorService.approveDoctor(1L, TestAuth.admin());

        verify(keycloakAdminService).assignRealmRole("kc-doctor-1", "DOCTOR");
        verify(auditLogService).log(any(), eq(AuditLogService.ACTION_APPROVE_DOCTOR),
                eq(AuditLogService.ENTITY_DOCTOR), eq(1L), eq("Dr. John Doe"));
    }

    /** A record with no Keycloak account behind it cannot be approved. */
    @Test
    void approveDoctor_rejectsRecordWithoutKeycloakAccount() {
        doctor.setKeycloakId(null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class, () -> doctorService.approveDoctor(1L, TestAuth.admin()));
        verify(keycloakAdminService, never()).assignRealmRole(any(), any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    /**
     * WHAT: a Keycloak failure must surface rather than be swallowed.
     * WHY: the administrator explicitly asked for the role to be granted. Reporting success while
     * the role was never assigned would leave the doctor unable to sign in with no indication why.
     */
    @Test
    void approveDoctor_propagatesKeycloakFailure() {
        doctor.setKeycloakId("kc-doctor-1");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).assignRealmRole(any(), any());

        assertThrows(RuntimeException.class, () -> doctorService.approveDoctor(1L, TestAuth.admin()));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    /** Pending list resolves each audit entry to the doctor it names. */
    @Test
    void getPendingRegistrations_resolvesDoctorNames() {
        AuditLog entry = new AuditLog();
        entry.setEntityId(1L);
        entry.setAction(AuditLogService.ACTION_SELF_REGISTER);
        entry.setEntityType(AuditLogService.ENTITY_DOCTOR);
        entry.setCreatedAt(LocalDateTime.now());
        when(auditLogService.findPendingDoctorRegistrations()).thenReturn(List.of(entry));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        List<PendingDoctorResponse> pending = doctorService.getPendingRegistrations();

        assertEquals(1, pending.size());
        assertEquals("John Doe", pending.get(0).doctorName());
        assertEquals(1L, pending.get(0).doctorId());
    }

    /** An entry whose doctor has since been deleted is skipped, not surfaced as a dangling row. */
    @Test
    void getPendingRegistrations_skipsDeletedDoctors() {
        AuditLog entry = new AuditLog();
        entry.setEntityId(99L);
        entry.setCreatedAt(LocalDateTime.now());
        when(auditLogService.findPendingDoctorRegistrations()).thenReturn(List.of(entry));
        when(doctorRepository.findById(99L)).thenReturn(Optional.empty());

        assertTrue(doctorService.getPendingRegistrations().isEmpty());
    }

    // ──────────────────────────────────────────────
    // Self-registration
    // ──────────────────────────────────────────────

    /**
     * WHAT: Verifies self-registration binds the record to the caller's JWT subject.
     * WHY: The identity must come from the verified token, never from the request body,
     * so a user cannot create a doctor profile attached to someone else's account.
     */
    @Test
    void selfRegister_bindsToTokenSubject() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov", new HashSet<>());
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        doctorService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        ArgumentCaptor<Doctor> saved = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(saved.capture());
        assertEquals("kc-sub-123", saved.getValue().getKeycloakId());
    }

    /**
     * WHAT: Verifies an applicant cannot register themselves as a General Practitioner.
     * WHY: GP status decides who appears in the directory patients pick their GP from,
     * so it is an administrator's decision, not the applicant's.
     */
    @Test
    void selfRegister_cannotClaimGpStatus() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov", new HashSet<>());
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorResponse response = doctorService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        assertFalse(response.isGP(), "self-registered doctors must not be GPs until an admin grants it");
    }

    /** WHAT: Verifies a second self-registration for the same account is rejected. */
    @Test
    void selfRegister_rejectsDuplicateProfile() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov", new HashSet<>());
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class,
                () -> doctorService.selfRegister(request, TestAuth.patient("kc-sub-123")));
        verify(doctorRepository, never()).save(any());
    }

    /** WHAT: Verifies self-registration refuses to proceed without an authenticated identity. */
    @Test
    void selfRegister_requiresAuthentication() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov", new HashSet<>());

        assertThrows(AccessDeniedException.class, () -> doctorService.selfRegister(request, null));
        verify(doctorRepository, never()).save(any());
    }
}
