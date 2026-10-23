package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.TestAuth;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Specialization;
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

    // Real, not mocked - self-registration tests are pointless if extractSubject() is stubbed out.
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

    @Test
    void getAllDoctors() {
        when(doctorRepository.findAll()).thenReturn(Arrays.asList(doctor));
        assertEquals(1, doctorService.getAllDoctors().size());
    }

    @Test
    void getDoctorById_success() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        DoctorResponse response = doctorService.getDoctorById(1L);
        assertEquals("John", response.firstName());
    }

    // Must reach the global handler as a 404, not a 500.
    @Test
    void getDoctorById_notFound() {
        when(doctorRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> doctorService.getDoctorById(2L));
    }

    @Test
    void createDoctor() {
        DoctorRequest request = new DoctorRequest(null, "Gregory", "House", true, Set.of(), null);
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DoctorResponse response = doctorService.createDoctor(request, TestAuth.admin());
        assertEquals("Gregory", response.firstName());
        assertEquals("House", response.lastName());
        assertTrue(response.isGP());
    }

    @Test
    void updateDoctor() {
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", false, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DoctorResponse response = doctorService.updateDoctor(1L, request, TestAuth.admin());
        assertEquals("Jane", response.firstName());
    }

    @Test
    void deleteDoctor_success() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.countByDoctorId(1L)).thenReturn(0L);
        assertDoesNotThrow(() -> doctorService.deleteDoctor(1L, TestAuth.admin()));
        verify(doctorRepository, times(1)).delete(any());
    }

    @Test
    void deleteDoctor_notFound() {
        when(doctorRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> doctorService.deleteDoctor(2L, TestAuth.admin()));
    }

    // visit.doctor_id is ON DELETE CASCADE - deleting would wipe the patients' medical history.
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

    @Test
    void deleteDoctor_allowedWhenDoctorHasNoVisits() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.countByDoctorId(1L)).thenReturn(0L);

        doctorService.deleteDoctor(1L, TestAuth.admin());

        verify(doctorRepository).delete(doctor);
        verify(auditLogService).log(any(), eq("DELETE"), eq("Doctor"), eq(1L), eq("Dr. John Doe"));
    }

    @Test
    void getDoctorsWithVisitCount() {
        when(doctorRepository.findDoctorsWithVisitCount())
                .thenReturn(java.util.List.of(new ReportDTO.DoctorVisitCount(1L, "Dr House", 10L)));

        java.util.List<ReportDTO.DoctorVisitCount> result = doctorService.getDoctorsWithVisitCount();

        assertEquals(1, result.size());
        assertEquals("Dr House", result.get(0).doctorName());
        assertEquals(10L, result.get(0).visitCount());
    }

    // ──────────────────────────────────────────────
    // Approval of self-registered doctors
    // ──────────────────────────────────────────────

    // The APPROVE_DOCTOR entry is what clears the doctor from the pending banner.
    @Test
    void approveDoctor_grantsRoleAndRecordsApproval() {
        doctor.setKeycloakId("kc-doctor-1");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        doctorService.approveDoctor(1L, TestAuth.admin());

        verify(keycloakAdminService).assignRealmRole("kc-doctor-1", "DOCTOR");
        verify(auditLogService).log(any(), eq(AuditLogService.ACTION_APPROVE_DOCTOR),
                eq(AuditLogService.ENTITY_DOCTOR), eq(1L), eq("Dr. John Doe"));
    }

    @Test
    void approveDoctor_rejectsRecordWithoutKeycloakAccount() {
        doctor.setKeycloakId(null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class, () -> doctorService.approveDoctor(1L, TestAuth.admin()));
        verify(keycloakAdminService, never()).assignRealmRole(any(), any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    // Must surface - otherwise the admin sees success while the doctor still can't sign in.
    @Test
    void approveDoctor_propagatesKeycloakFailure() {
        doctor.setKeycloakId("kc-doctor-1");
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        doThrow(new RuntimeException("Keycloak unreachable"))
                .when(keycloakAdminService).assignRealmRole(any(), any());

        assertThrows(RuntimeException.class, () -> doctorService.approveDoctor(1L, TestAuth.admin()));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

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

    // Identity comes from the token, never the body - otherwise you could claim someone else's account.
    @Test
    void selfRegister_bindsToTokenSubject() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov");
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        doctorService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        ArgumentCaptor<Doctor> saved = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(saved.capture());
        assertEquals("kc-sub-123", saved.getValue().getKeycloakId());
    }

    // GP status decides who shows up in the GP directory, so only an admin can grant it.
    @Test
    void selfRegister_cannotClaimGpStatus() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov");
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.empty());
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorResponse response = doctorService.selfRegister(request, TestAuth.patient("kc-sub-123"));

        assertFalse(response.isGP(), "self-registered doctors must not be GPs until an admin grants it");
    }

    @Test
    void selfRegister_rejectsDuplicateProfile() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov");
        when(doctorRepository.findByKeycloakId("kc-sub-123")).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class,
                () -> doctorService.selfRegister(request, TestAuth.patient("kc-sub-123")));
        verify(doctorRepository, never()).save(any());
    }

    @Test
    void selfRegister_requiresAuthentication() {
        DoctorSelfRegistrationRequest request =
                new DoctorSelfRegistrationRequest("Ivan", "Petrov");

        assertThrows(AccessDeniedException.class, () -> doctorService.selfRegister(request, null));
        verify(doctorRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────
    // Which Keycloak account a record belongs to
    // ──────────────────────────────────────────────
    // An absent field leaves the link, an empty one clears it, a value sets it.

    @Test
    void updateDoctor_absentKeycloakId_leavesTheLinkAlone() {
        doctor.setKeycloakId("kc-existing");
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", true, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("kc-existing", doctorService.updateDoctor(1L, request, TestAuth.admin()).keycloakId());
    }

    // Approving by Edit -> Save must also record APPROVE_DOCTOR, or the pending banner keeps them.
    @Test
    void updateDoctor_whenApplicantIsPending_recordsTheApproval() {
        doctor.setKeycloakId("kc-applicant");
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", false, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogService.findPendingDoctorIds()).thenReturn(Set.of(1L));

        DoctorResponse response = doctorService.updateDoctor(1L, request, TestAuth.admin());

        verify(auditLogService).log(any(), eq(AuditLogService.ACTION_APPROVE_DOCTOR),
                eq(AuditLogService.ENTITY_DOCTOR), eq(1L), any());
        assertFalse(response.pending(), "the applicant is approved by this save");
    }

    @Test
    void updateDoctor_whenAlreadyApproved_recordsOnlyTheUpdate() {
        doctor.setKeycloakId("kc-existing");
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", false, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogService.findPendingDoctorIds()).thenReturn(Set.of());

        doctorService.updateDoctor(1L, request, TestAuth.admin());

        verify(auditLogService, never()).log(any(), eq(AuditLogService.ACTION_APPROVE_DOCTOR),
                any(), any(), any());
    }

    // No Keycloak account means nothing to grant the role to - same rule as approveDoctor.
    @Test
    void updateDoctor_pendingWithNoAccount_staysPending() {
        doctor.setKeycloakId(null);
        DoctorRequest request = new DoctorRequest(null, "Jane", "Smith", false, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogService.findPendingDoctorIds()).thenReturn(Set.of(1L));

        assertTrue(doctorService.updateDoctor(1L, request, TestAuth.admin()).pending());
        verify(auditLogService, never()).log(any(), eq(AuditLogService.ACTION_APPROVE_DOCTOR),
                any(), any(), any());
    }

    // Removes it from holders instead of refusing - the one to delete is usually a misspelt one already assigned.
    @Test
    void deleteSpecialization_removesItFromTheDoctorsHoldingIt() {
        Specialization cardiology = new Specialization();
        cardiology.setId(5L);
        cardiology.setName("Cardiolgy");          // the misspelling being corrected
        Specialization neurology = new Specialization();
        neurology.setId(6L);
        neurology.setName("Neurology");

        Doctor holder = new Doctor();
        holder.setId(1L);
        holder.setSpecializations(new java.util.HashSet<>(Set.of(cardiology, neurology)));
        Doctor bystander = new Doctor();
        bystander.setId(2L);
        bystander.setSpecializations(new java.util.HashSet<>(Set.of(neurology)));

        when(specializationRepository.findById(5L)).thenReturn(Optional.of(cardiology));
        when(doctorRepository.findAll()).thenReturn(List.of(holder, bystander));

        doctorService.deleteSpecialization(5L, TestAuth.admin());

        assertEquals(Set.of(neurology), holder.getSpecializations(), "only the deleted one comes off");
        assertEquals(Set.of(neurology), bystander.getSpecializations(), "a doctor who never held it is untouched");
        verify(specializationRepository).delete(cardiology);
        verify(doctorRepository).saveAll(List.of(holder));
        verify(auditLogService).log(any(), eq("DELETE"), eq("Specialization"), eq(5L), any());
    }

    @Test
    void getAllDoctors_marksPendingApplicants() {
        when(doctorRepository.findAll()).thenReturn(List.of(doctor));
        when(auditLogService.findPendingDoctorIds()).thenReturn(Set.of(1L));

        assertTrue(doctorService.getAllDoctors().get(0).pending());
    }

    // Report E lists staff only. Applicants hold no DOCTOR role.
    @Test
    void getDoctorsWithVisitCount_leavesOutPendingApplicants() {
        when(doctorRepository.findDoctorsWithVisitCount()).thenReturn(List.of(
                new ReportDTO.DoctorVisitCount(1L, "Jane Smith", 4L),
                new ReportDTO.DoctorVisitCount(2L, "Pending Applicant", 0L)));
        when(auditLogService.findPendingDoctorIds()).thenReturn(Set.of(2L));

        List<ReportDTO.DoctorVisitCount> rows = doctorService.getDoctorsWithVisitCount();

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).doctorId());
    }

    @Test
    void updateDoctor_emptyKeycloakId_unlinksTheAccount() {
        doctor.setKeycloakId("kc-existing");
        DoctorRequest request = new DoctorRequest("", "Jane", "Smith", true, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNull(doctorService.updateDoctor(1L, request, TestAuth.admin()).keycloakId());
    }

    @Test
    void updateDoctor_newKeycloakId_relinksTheAccount() {
        doctor.setKeycloakId("kc-existing");
        DoctorRequest request = new DoctorRequest("  kc-other  ", "Jane", "Smith", true, Set.of(), null);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("kc-other", doctorService.updateDoctor(1L, request, TestAuth.admin()).keycloakId());
    }
}
