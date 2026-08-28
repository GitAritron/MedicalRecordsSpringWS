package com.f106317.MedicalRecordsSpringWS.Data.Repo;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"gp"})
    List<Patient> findAll();

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"gp"})
    List<Patient> findByGpId(Long gpId);

    Optional<Patient> findByKeycloakId(String keycloakId);
    Optional<Patient> findByEgn(String egn);
    boolean existsByEgn(String egn);
}
