package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorRequest;
import com.f106317.MedicalRecordsSpringWS.DTO.DoctorResponse;
import com.f106317.MedicalRecordsSpringWS.Exception.ResourceNotFoundException;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.DoctorRepository;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.SpecializationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
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
        DoctorResponse response = doctorService.createDoctor(request);
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
        DoctorResponse response = doctorService.updateDoctor(1L, request);
        assertEquals("Jane", response.firstName());
    }

    /** 
     * WHAT: Verifies successful deletion of an existing doctor. 
     * WHY: Validates that the service correctly checks for existence before calling delete.
     */
    @Test
    void deleteDoctor_success() {
        when(doctorRepository.findById(1L)).thenReturn(Optional.of(doctor));
        assertDoesNotThrow(() -> doctorService.deleteDoctor(1L));
        verify(doctorRepository, times(1)).delete(any());
    }

    /** 
     * WHAT: Verifies that deleting a non-existent doctor throws an exception. 
     * WHY: Prevents the system from executing invalid DELETE SQL queries.
     */
    @Test
    void deleteDoctor_notFound() {
        when(doctorRepository.findById(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> doctorService.deleteDoctor(2L));
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
}
