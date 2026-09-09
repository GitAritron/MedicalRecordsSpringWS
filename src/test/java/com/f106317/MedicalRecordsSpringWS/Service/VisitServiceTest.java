package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import java.util.List;
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

/**
 * Unit tests for {@link VisitService}.
 * Covers visit creation, diagnosis/prescription/sick-leave creation, and report queries.
 */
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

    // Real collaborator, not a mock. Mocking it away turned every ownership assertion into a
    // no-op, which is why the tests below could not have caught an authorization regression.
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

    /** Verifies all visits are retrieved and mapped to DTOs. */
    @Test
    void getAllVisits() {
        when(visitRepository.findAll()).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getAllVisits().size());
    }

    /** Verifies a visit is created when both patient and doctor exist. */
    @Test
    void createVisit_success() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.save(any())).thenReturn(visit);

        VisitResponse response = visitService.createVisit(request, TestAuth.admin());
        assertEquals(1L, response.patientId());
    }

    /** Verifies a diagnosis record is created and linked to a visit. */
    @Test
    void createDiagnosis_success() {
        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        Diagnosis diagnosis = new Diagnosis();
        diagnosis.setId(1L);
        diagnosis.setVisit(visit);
        diagnosis.setName("Flu");

        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(diagnosisRepository.save(any())).thenReturn(diagnosis);

        DiagnosisResponse response = visitService.createDiagnosis(request, TestAuth.admin());
        assertEquals("Flu", response.name());
    }

    /** Verifies a prescription record is created and linked to a visit. */
    @Test
    void createPrescription_success() {
        PrescriptionRequest request = new PrescriptionRequest(1L, "Aspirin");
        Prescription prescription = new Prescription();
        prescription.setId(1L);
        prescription.setVisit(visit);
        prescription.setNote("Aspirin");

        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(prescriptionRepository.save(any())).thenReturn(prescription);

        PrescriptionResponse response = visitService.createPrescription(request, TestAuth.admin());
        assertEquals("Aspirin", response.note());
    }

    /** Verifies a sick leave record is created with the correct duration. */
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

    /** Verifies visits are filtered by patient ID. */
    @Test
    void getVisitsByPatient() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(visitRepository.findByPatientId(1L)).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsByPatient(1L, TestAuth.admin()).size());
    }

    /** Verifies visits are filtered by a date range. */
    @Test
    void getVisitsInPeriod() {
        when(visitRepository.findVisitsInPeriod(any(), any())).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsInPeriod(LocalDate.now(), LocalDate.now()).size());
    }

    /** Verifies visits are filtered by doctor ID and date range. */
    @Test
    void getVisitsByDoctorInPeriod() {
        when(visitRepository.findVisitsByDoctorInPeriod(org.mockito.ArgumentMatchers.eq(1L), any(), any())).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsByDoctorInPeriod(1L, LocalDate.now(), LocalDate.now()).size());
    }

    /** Verifies the aggregated report of most common diagnoses. */
    @Test
    void getMostCommonDiagnoses() {
        when(diagnosisRepository.findMostCommonDiagnoses())
                .thenReturn(List.of(new ReportDTO.DiagnosisCount("Flu", 10L)));

        List<ReportDTO.DiagnosisCount> result = visitService.getMostCommonDiagnoses();

        assertEquals(1, result.size());
        assertEquals("Flu", result.get(0).diagnosisName());
        assertEquals(10L, result.get(0).count());
    }

    /** Verifies patients can be retrieved by their diagnosis name. */
    @Test
    void getPatientsByDiagnosis() {
        when(diagnosisRepository.findPatientsByDiagnosisName("Flu")).thenReturn(Arrays.asList(patient));
        assertEquals(1, visitService.getPatientsByDiagnosis("Flu").size());
    }

    /** Verifies the report of doctors ranked by sick leaves issued. */
    @Test
    void getDoctorsWithMostSickLeaves() {
        when(sickLeaveRepository.findDoctorsWithMostSickLeaves())
                .thenReturn(List.of(new ReportDTO.DoctorSickLeaveCount(1L, "Dr House", 5L)));

        List<ReportDTO.DoctorSickLeaveCount> result = visitService.getDoctorsWithMostSickLeaves();

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).sickLeaveCount());
    }

    /** Verifies the report of months ranked by sick leave count. */
    @Test
    void getMonthsWithMostSickLeaves() {
        when(sickLeaveRepository.findMonthsWithMostSickLeaves())
                .thenReturn(List.of(new ReportDTO.SickLeaveMonthCount(2026, 1, 10L)));

        List<ReportDTO.SickLeaveMonthCount> result = visitService.getMonthsWithMostSickLeaves();

        assertEquals(1, result.size());
        assertEquals(2026, result.get(0).year());
        assertEquals(1, result.get(0).month());
    }

    /** Verifies ResourceNotFoundException when creating a visit with a non-existent patient. */
    @Test
    void createVisit_patientNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    /** Verifies ResourceNotFoundException when creating a visit with a non-existent doctor. */
    @Test
    void createVisit_doctorNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request, TestAuth.admin()));
    }

    /** Verifies ResourceNotFoundException when adding a diagnosis to a non-existent visit. */
    @Test
    void createDiagnosis_visitNotFound() {
        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        when(visitRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createDiagnosis(request, TestAuth.admin()));
    }

    /** Verifies ResourceNotFoundException when retrieving a non-existent visit by ID. */
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
    // Ownership rules (specification §2)
    // ──────────────────────────────────────────────
    // Previously untestable: AuthorizationService was mocked, so these assertions never ran.

    /**
     * "могат да въвеждат и редактират само информация за прегледите на пациентите, които са
     * прегледали" — a doctor may amend a visit they performed.
     */
    @Test
    void createDiagnosis_doctorCanAnnotateOwnVisit() {
        doctor.setKeycloakId("kc-doctor-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        when(diagnosisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        assertNotNull(visitService.createDiagnosis(request, TestAuth.doctor("kc-doctor-1")));
    }

    /** ...and not one performed by a colleague. */
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

    /** A patient may read a visit of their own... */
    @Test
    void getVisitById_patientCanReadOwnVisit() {
        patient.setKeycloakId("kc-patient-1");
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));

        assertNotNull(visitService.getVisitById(1L, TestAuth.patient("kc-patient-1")));
    }

    /** ...but not someone else's, however they guess the id. */
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

    /** A doctor creating a visit cannot name a colleague as the attending physician. */
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

    /**
     * visitDate is only @NotNull at the DTO level, so the service rejects dates that cannot
     * describe a real appointment. Both directions are checked because visits are legitimately
     * recorded retrospectively as well as booked ahead.
     */
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

    /**
     * SickLeaveRequest.validFrom carries the same @NotNull-only weakness visitDate had, so the
     * same plausibility bound applies — a sick leave cannot start in the year 9999.
     */
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

    /** A visit recorded last year is ordinary history and must still be accepted. */
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
