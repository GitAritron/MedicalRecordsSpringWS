package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Doctor;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
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
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$DoctorVisitCount(
                       d.id, CONCAT(d.firstName, ' ', d.lastName), COUNT(v))
            FROM Doctor d LEFT JOIN Visit v ON v.doctor = d
            GROUP BY d.id, d.firstName, d.lastName
            ORDER BY COUNT(v) DESC
            """)
    List<ReportDTO.DoctorVisitCount> findDoctorsWithVisitCount();

    /**
     * WHAT: A JPQL query that joins Doctors and Patients to count registered patients per GP.
     * WHY: Fulfills the requirement "Number of patients registered with each GP".
     * Filters by `isGP = true` to ignore specialized doctors who aren't GPs.
     */
    @Query("""
            SELECT new com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO$GpDoctorPatientCount(
                       d.id, CONCAT(d.firstName, ' ', d.lastName), COUNT(p))
            FROM Doctor d LEFT JOIN Patient p ON p.gp = d
            WHERE d.isGP = true
            GROUP BY d.id, d.firstName, d.lastName
            ORDER BY COUNT(p) DESC
            """)
    List<ReportDTO.GpDoctorPatientCount> findGpDoctorsWithPatientCount();
}
