package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import java.util.List;

@Repository
public interface DiagnosisRepository extends JpaRepository<Diagnosis, Long> {
    // Finds patients by diagnosis name. Joins through Visit to get Patient.
    @Query("SELECT DISTINCT d.visit.patient FROM Diagnosis d WHERE d.name = :diagnosisName")
    List<Patient> findPatientsByDiagnosisName(@Param("diagnosisName") String diagnosisName);
    
    // Report: most common diagnoses.
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$DiagnosisCount(
                       d.name, COUNT(d))
            FROM Diagnosis d
            GROUP BY d.name
            ORDER BY COUNT(d) DESC
            """)
    List<ReportDTO.DiagnosisCount> findMostCommonDiagnoses();

    @Query("SELECT DISTINCT d.name FROM Diagnosis d ORDER BY d.name")
    List<String> findDistinctDiagnosisNames();
}
