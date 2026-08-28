package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"specializations"})
    List<Doctor> findAll();

    java.util.Optional<Doctor> findByKeycloakId(String keycloakId);

    /**
     * WHAT: A JPQL query that groups visits by doctor and counts them.
     * WHY: Fulfills the requirement "Number of visits per doctor" by using a LEFT JOIN
     * to ensure doctors with 0 visits still appear in the report. Ordered descending.
     */
    @Query("SELECT d, COUNT(v) as visitCount FROM Doctor d LEFT JOIN Visit v ON v.doctor = d GROUP BY d ORDER BY visitCount DESC")
    List<Object[]> findDoctorsWithVisitCount();

    /**
     * WHAT: A JPQL query that joins Doctors and Patients to count registered patients per GP.
     * WHY: Fulfills the requirement "Number of patients registered with each GP".
     * Filters by `isGP = true` to ignore specialized doctors who aren't GPs.
     */
    @Query("SELECT d, COUNT(p) as patientCount FROM Doctor d LEFT JOIN Patient p ON p.gp = d WHERE d.isGP = true GROUP BY d ORDER BY patientCount DESC")
    List<Object[]> findGpDoctorsWithPatientCount();
}
