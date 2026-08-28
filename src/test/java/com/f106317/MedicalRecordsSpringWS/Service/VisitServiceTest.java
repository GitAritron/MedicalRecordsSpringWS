package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.*;
import com.f106317.MedicalRecordsSpringWS.DTO.*;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @Mock
    private AuthorizationService authorizationService;

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

        VisitResponse response = visitService.createVisit(request);
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

        DiagnosisResponse response = visitService.createDiagnosis(request);
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

        PrescriptionResponse response = visitService.createPrescription(request);
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

        SickLeaveResponse response = visitService.createSickLeave(request);
        assertEquals(5, response.validForDays());
    }

    /** Verifies visits are filtered by patient ID. */
    @Test
    void getVisitsByPatient() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(visitRepository.findByPatientId(1L)).thenReturn(Arrays.asList(visit));
        assertEquals(1, visitService.getVisitsByPatient(1L).size());
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
        when(diagnosisRepository.findMostCommonDiagnoses()).thenReturn(java.util.Collections.singletonList(new Object[]{"Flu", 10L}));
        assertEquals(1, visitService.getMostCommonDiagnoses().size());
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
        when(sickLeaveRepository.findDoctorsWithMostSickLeaves()).thenReturn(java.util.Collections.singletonList(new Object[]{doctor, 5L}));
        assertEquals(1, visitService.getDoctorsWithMostSickLeaves().size());
    }

    /** Verifies the report of months ranked by sick leave count. */
    @Test
    void getMonthsWithMostSickLeaves() {
        when(sickLeaveRepository.findMonthsWithMostSickLeaves()).thenReturn(java.util.Collections.singletonList(new Object[]{2026, 1, 10L}));
        assertEquals(1, visitService.getMonthsWithMostSickLeaves().size());
    }

    /** Verifies ResourceNotFoundException when creating a visit with a non-existent patient. */
    @Test
    void createVisit_patientNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request));
    }

    /** Verifies ResourceNotFoundException when creating a visit with a non-existent doctor. */
    @Test
    void createVisit_doctorNotFound() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createVisit(request));
    }

    /** Verifies ResourceNotFoundException when adding a diagnosis to a non-existent visit. */
    @Test
    void createDiagnosis_visitNotFound() {
        DiagnosisRequest request = new DiagnosisRequest(1L, "Flu");
        when(visitRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.createDiagnosis(request));
    }

    /** Verifies ResourceNotFoundException when retrieving a non-existent visit by ID. */
    @Test
    void getVisitById_notFound() {
        when(visitRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException.class, () -> visitService.getVisitById(1L));
    }

    @Test
    void deleteVisit_success() {
        when(visitRepository.findById(1L)).thenReturn(Optional.of(visit));
        assertDoesNotThrow(() -> visitService.deleteVisit(1L, null));
        verify(visitRepository, times(1)).delete(visit);
    }

    @Test
    void createVisit_doctorSlotBooked() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, null));
    }

    @Test
    void createVisit_patientSlotBooked() {
        VisitRequest request = new VisitRequest(1L, 1L, LocalDate.now(), LocalTime.now());
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(visitRepository.existsByDoctorIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(false);
        when(visitRepository.existsByPatientIdAndVisitDateAndVisitTime(eq(1L), any(), any())).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> visitService.createVisit(request, null));
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

        VisitResponse res = visitService.updateVisit(1L, request, null);
        assertNotNull(res);
        assertEquals(date, res.visitDate());
    }
}
