package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @Mock
    private AuthorizationService authorizationService;

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
        PatientResponse response = patientService.getPatientById(1L);
        assertEquals("John", response.firstName());
        assertEquals("Dr. House MD", response.gpDoctorName());
    }

    /** Verifies that ResourceNotFoundException is thrown for non-existent patient. */
    @Test
    void getPatientById_notFound() {
        when(patientRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> patientService.getPatientById(2L));
    }

    /** Verifies a patient is created with the correct GP association. */
    @Test
    void createPatient_success() {
        PatientRequest request = new PatientRequest(null, "1234567890", "John", "Doe", 1L);
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenReturn(patient);
        
        PatientResponse response = patientService.createPatient(request);
        assertEquals("John", response.firstName());
    }

    /** Verifies that updating a patient changes the persisted fields. */
    @Test
    void updatePatient_success() {
        PatientRequest request = new PatientRequest(null, "1234567890", "Jane", "Smith", 1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse response = patientService.updatePatient(1L, request);
        assertEquals("Jane", response.firstName());
        assertEquals("1234567890", response.egn());
    }

    /** Verifies successful deletion of an existing patient. */
    @Test
    void deletePatient_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertDoesNotThrow(() -> patientService.deletePatient(1L));
        verify(patientRepository, times(1)).delete(any());
    }

    @Test
    void updatePatientGp_success() {
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientResponse res = patientService.updatePatientGp(1L, 1L, null);
        assertNotNull(res);
        verify(patientRepository, times(1)).save(any());
    }

    /** Verifies that EGN cannot be changed on update. */
    @Test
    void updatePatient_egnImmutable() {
        PatientRequest request = new PatientRequest(null, "9999999999", "Jane", "Smith", 1L);
        when(patientRepository.findById(1L)).thenReturn(Optional.of(patient));
        assertThrows(IllegalArgumentException.class, () -> patientService.updatePatient(1L, request));
    }

    /** Verifies that a non-GP doctor cannot be assigned as GP. */
    @Test
    void createPatient_nonGpDoctor() {
        Doctor specialist = new Doctor();
        specialist.setId(2L);
        specialist.setGP(false);
        PatientRequest request = new PatientRequest(null, "1234567890", "John", "Doe", 2L);
        when(doctorRepository.findById(2L)).thenReturn(Optional.of(specialist));
        assertThrows(IllegalArgumentException.class, () -> patientService.createPatient(request));
    }

    /** Verifies retrieval of patients by their assigned GP doctor ID. */
    @Test
    void getPatientsByGp() {
        when(patientRepository.findByGpId(1L)).thenReturn(Arrays.asList(patient));
        assertEquals(1, patientService.getPatientsByGp(1L).size());
    }
}
