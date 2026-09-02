package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "visit", uniqueConstraints = {
        @UniqueConstraint(name = "uq_doctor_slot", columnNames = {"doctor_id", "visit_date", "visit_time"}),
        @UniqueConstraint(name = "uq_patient_slot", columnNames = {"patient_id", "visit_date", "visit_time"})
})
@Getter
@Setter
@NoArgsConstructor
public class Visit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "visit_time", nullable = false)
    private LocalTime visitTime;

    // Set, not List: VisitRepository fetches both collections in one entity graph, and Hibernate
    // refuses to fetch two bags (List without @OrderColumn) simultaneously — see MultipleBagFetchException.
    @OneToMany(mappedBy = "visit", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Diagnosis> diagnoses = new LinkedHashSet<>();

    @OneToMany(mappedBy = "visit", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Prescription> prescriptions = new LinkedHashSet<>();

    @OneToOne(mappedBy = "visit", cascade = CascadeType.ALL, orphanRemoval = true)
    private SickLeave sickLeave;
}
