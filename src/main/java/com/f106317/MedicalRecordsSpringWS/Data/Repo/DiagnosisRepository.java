package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Diagnosis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import java.util.List;

@Repository
public interface DiagnosisRepository extends JpaRepository<Diagnosis, Long> {
    /**
     * WHAT: A JPQL query that fetches all Patients associated with a specific diagnosis name.
     * WHY: Fulfills the requirement "List of patients with a given diagnosis". It implicitly joins
     * through the Visit entity to find the associated patient.
     */
    @Query("SELECT DISTINCT d.visit.patient FROM Diagnosis d WHERE d.name = :diagnosisName")
    List<Patient> findPatientsByDiagnosisName(@Param("diagnosisName") String diagnosisName);
    
    /**
     * WHAT: A JPQL query that counts the occurrences of each diagnosis name.
     * WHY: Fulfills the requirement "Information on the most commonly diagnosed diseases".
     * Groups by the diagnosis string and orders descending to find the top diseases.
     */
    @Query("SELECT d.name, COUNT(d) as c FROM Diagnosis d GROUP BY d.name ORDER BY c DESC")
    List<Object[]> findMostCommonDiagnoses();

    @Query("SELECT DISTINCT d.name FROM Diagnosis d ORDER BY d.name")
    List<String> findDistinctDiagnosisNames();
}
