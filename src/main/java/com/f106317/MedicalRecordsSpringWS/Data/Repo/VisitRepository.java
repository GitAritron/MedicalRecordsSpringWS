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

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    List<Visit> findByPatientId(Long patientId);

    // Used to refuse deleting a doctor who has visits.
    long countByDoctorId(Long doctorId);

    boolean existsByDoctorIdAndVisitDateAndVisitTime(Long doctorId, LocalDate visitDate, java.time.LocalTime visitTime);

    boolean existsByPatientIdAndVisitDateAndVisitTime(Long patientId, LocalDate visitDate, java.time.LocalTime visitTime);

    // Report: all visits in a date range.
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    @Query("SELECT v FROM Visit v WHERE v.visitDate BETWEEN :startDate AND :endDate ORDER BY v.visitDate, v.visitTime")
    List<Visit> findVisitsInPeriod(@Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);

    // Report: visits for one specific doctor in a date range.
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    @Query("SELECT v FROM Visit v WHERE v.doctor.id = :doctorId AND v.visitDate BETWEEN :startDate AND :endDate ORDER BY v.visitDate, v.visitTime")
    List<Visit> findVisitsByDoctorInPeriod(@Param("doctorId") Long doctorId,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "doctor", "diagnoses", "prescriptions", "sickLeave"})
    @Query("SELECT v FROM Visit v ORDER BY v.patient.firstName, v.patient.lastName, v.visitDate, v.visitTime")
    List<Visit> findAllOrderByPatientNameAndDate();
}
