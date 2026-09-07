package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    List<Visit> findAll();

    /**
     * WHAT: Automatically generated query by Spring Data JPA to find visits by patient ID.
     * WHY: Fulfills the requirement "List of visits of each patient".
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    List<Visit> findByPatientId(Long patientId);

    List<Visit> findByDoctorId(Long doctorId);

    /** Used to refuse deleting a doctor whose visits would be cascade-removed with them. */
    long countByDoctorId(Long doctorId);

    boolean existsByDoctorIdAndVisitDateAndVisitTime(Long doctorId, LocalDate visitDate, java.time.LocalTime visitTime);

    boolean existsByPatientIdAndVisitDateAndVisitTime(Long patientId, LocalDate visitDate, java.time.LocalTime visitTime);

    /**
     * WHAT: A JPQL query that filters all visits between two dates.
     * WHY: Fulfills the requirement "List of visits to all doctors in a given period".
     */
    @Query("SELECT v FROM Visit v WHERE v.visitDate BETWEEN :startDate AND :endDate")
    List<Visit> findVisitsInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * WHAT: A JPQL query that filters visits by a specific doctor ID and within a date range.
     * WHY: Fulfills the requirement "List of visits to a specific doctor for a given period".
     */
    @Query("SELECT v FROM Visit v WHERE v.doctor.id = :doctorId AND v.visitDate BETWEEN :startDate AND :endDate")
    List<Visit> findVisitsByDoctorInPeriod(@Param("doctorId") Long doctorId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
