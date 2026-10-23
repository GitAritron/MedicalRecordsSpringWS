package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.TestAuth;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.*;
import com.f106317.MedicalRecordsSpringWS.DTO.*;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    @Mock
    private VisitRepository visitRepository;
    @Mock
    private DiagnosisRepository diagnosisRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private SickLeaveRepository sickLeaveRepository;
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
    private VisitService visitService;

    private Visit visit;
    private Patient patient;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(1L);
        patient.setFirstName("John");
        patient.setLastName("Doe");

        doctor = new Doctor();
        doctor.setId(1L);
        doctor.setFirstName("House");
        doctor.setLastName("MD");

        visit = new Visit();
        visit.setId(1L);
        visit.setPatient(patient);
        visit.setDoctor(doctor);
        visit.setVisitDate(LocalDate.now());
        visit.setVisitTime(LocalTime.now());
    }

    @Test
    void getAllVisits() {
        when(visitRepository.findAll()).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getAllVisits().size());
    }

    @Test
    void createVisit_success() {
        LocalDate date = LocalDate.now();
        LocalTime time = LocalTime.of(10, 30);
        VisitRequest request = new VisitRequest(1L, 1L, date, time);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VisitResponse response = visitService.createVisit(request, TestAuth.admin());
        assertEquals(1L, response.patientId());
        assertEquals(1L, response.doctorId());
        assertEquals(date, response.visitDate());
        assertEquals(time, response.visitTime());
    }

    @Test
    void createDiagnosis_success() {
        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(diagnosisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiagnosisResponse response = visitService.createDiagnosis(request, TestAuth.admin());
        assertEquals("Flu", response.name());
        assertEquals(1L, response.visitId());
    }

    @Test
    void createPrescription_success() {
        PrescriptionRequest request = new PrescriptionRequest(1L, "Aspirin");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(prescriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PrescriptionResponse response = visitService.createPrescription(request, TestAuth.admin());
        assertEquals("Aspirin", response.note());
        assertEquals(1L, response.visitId());
    }

    @Test
    void createSickLeave_success() {
        SickLeaveRequest request = new SickLeaveRequest(1L, LocalDate.now(), 5);
        SickLeave sickLeave = new SickLeave();
        sickLeave.setId(1L);
        sickLeave.setVisit(visit);
        sickLeave.setValidFrom(LocalDate.now());
        sickLeave.setValidForDays(5);

        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(sickLeaveRepository.save(any())).thenReturn(sickLeave);

        SickLeaveResponse response = visitService.createSickLeave(request, TestAuth.admin());
        assertEquals(5, response.validForDays());
    }

    @Test
    void getVisitsByPatient() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(visitRepository.findByPatientId(1L)).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsByPatient(1L, TestAuth.admin()).size());
    }

    @Test
    void getVisitsInPeriod() {
        when(visitRepository.findVisitsInPeriod(any(), any())).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsInPeriod(LocalDate.now(), LocalDate.now()).size());
    }

    @Test
    void getVisitsByDoctorInPeriod() {
        when(visitRepository.findVisitsByDoctorInPeriod(org.mockito.ArgumentMatchers.eq(1L), any(), any())).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsByDoctorInPeriod(1L, LocalDate.now(), LocalDate.now()).size());
    }

    @Test
    void getPatientsByDiagnosis() {
        when(diagnosisRepository.findPatientsByDiagnosisName("Flu")).thenReturn(Arrays.asList(patient));
        assertEquals(1, visitService.getPatientsByDiagnosis("Flu").size());
    }

    @Test
    void createVisit_patientNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    @Test
    void createVisit_doctorNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    @Test
    void createDiagnosis_visitNotFound() {
        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        when(visitRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createDiagnosis(request, TestAuth.admin()));
    }

    @Test
    void getVisitById_notFound() {
        when(visitRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.getVisitById(1L, TestAuth.admin()));
    }

    @Test
    void deleteVisit_success() {
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        assertDoesNotThrow(() -> visitService.deleteVisit(1L, TestAuth.admin()));
        verify(visitRepository, times(1)).delete(visit);
    }

    @Test
    void createVisit_doctorSlotBooked() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    @Test
    void createVisit_patientSlotBooked() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(false);
        when(visitRepository.existsByPatientIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    @Test
    void updateVisit_success() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalTime time = LocalTime.of(10, 0);
        visit.setVisitDate(date);
        visit.setVisitTime(time);

        VisitRequest request = new VisitRequest(1L, 1L, date, time);
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VisitResponse res = visitService.updateVisit(1L, request, TestAuth.admin());
        assertNotNull(res);
        assertEquals(date, res.visitDate());
    }

    // ──────────────────────────────────────────────
    // Ownership rules
    // ──────────────────────────────────────────────

    // doctors edit "само информация за прегледите на пациентите, които са прегледали".
    @Test
    void createDiagnosis_doctorCanAnnotateOwnVisit() {
        doctor.setKeycloakId("kc-doctor-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(diagnosisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        assertNotNull(visitService.createDiagnosis(request, TestAuth.doctor("kc-doctor-1")));
    }

    @Test
    void createDiagnosis_doctorCannotAnnotateAnotherDoctorsVisit() {
        doctor.setKeycloakId("kc-doctor-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        assertThrows(AccessDeniedException.class,
                () -> visitService.createDiagnosis(request, TestAuth.doctor("kc-doctor-2")));
        verify(diagnosisRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void deleteVisit_doctorCannotDeleteAnotherDoctorsVisit() {
        doctor.setKeycloakId("kc-doctor-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        assertThrows(AccessDeniedException.class,
                () -> visitService.deleteVisit(1L, TestAuth.doctor("kc-doctor-2")));
        verify(visitRepository, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void getVisitById_patientCanReadOwnVisit() {
        patient.setKeycloakId("kc-patient-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        assertNotNull(visitService.getVisitById(1L, TestAuth.patient("kc-patient-1")));
    }

    @Test
    void getVisitById_patientCannotReadAnotherPatientsVisit() {
        patient.setKeycloakId("kc-patient-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        assertThrows(AccessDeniedException.class,
                () -> visitService.getVisitById(1L, TestAuth.patient("kc-patient-2")));
    }

    @Test
    void getVisitsByPatient_patientCannotListAnotherPatientsVisits() {
        patient.setKeycloakId("kc-patient-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));

        assertThrows(AccessDeniedException.class,
                () -> visitService.getVisitsByPatient(1L, TestAuth.patient("kc-patient-2")));
    }

    @Test
    void createVisit_doctorCannotBookOnBehalfOfAnotherDoctor() {
        doctor.setKeycloakId("kc-doctor-1");
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.NOON);
        assertThrows(AccessDeniedException.class,
                () -> visitService.createVisit(request, TestAuth.doctor("kc-doctor-2")));
        verify(visitRepository, org.mockito.Mockito.never()).save(any());
    }

    // ──────────────────────────────────────────────
    // Visit date bounds
    // ──────────────────────────────────────────────

    // visitDate is only @NotNull in the DTO, so the service binds it both ways - visits are backdated and booked ahead.
    @Test
    void createVisit_rejectsAbsurdlyFutureDate() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now().plusYears(500), LocalTime.NOON);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, TestAuth.admin()));
        verify(visitRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void createVisit_rejectsAbsurdlyPastDate() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now().minusYears(500), LocalTime.NOON);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, TestAuth.admin()));
        verify(visitRepository, org.mockito.Mockito.never()).save(any());
    }

    // SickLeaveRequest.validFrom gets the same bound - a sick leave can't start in 9999.
    @Test
    void createSickLeave_rejectsAbsurdStartDate() {
        SickLeaveRequest request = new SickLeaveRequest(1L, LocalDate.now().plusYears(500), 5);
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        assertThrows(IllegalArgumentException.class, () -> visitService.createSickLeave(request, TestAuth.admin()));
        verify(sickLeaveRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void createSickLeave_acceptsPlausibleStartDate() {
        SickLeaveRequest request = new SickLeaveRequest(1L, LocalDate.now(), 5);
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(sickLeaveRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNotNull(visitService.createSickLeave(request, TestAuth.admin()));
    }

    @Test
    void createVisit_acceptsRecentPastDate() {
        LocalDate lastYear = LocalDate.now().minusYears(1);
        VisitRequest request = new VisitRequest(1L, 1L, lastYear, LocalTime.NOON);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(lastYear, visitService.createVisit(request, TestAuth.admin()).visitDate());
    }
}
