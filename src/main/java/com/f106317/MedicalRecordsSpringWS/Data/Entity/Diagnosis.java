package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// Mirrors init.sql: the name index carries the "patients by diagnosis" and "most common diagnoses"
// reports, which both filter or group on this column.
@Table(name = "diagnosis", indexes = @Index(name = "idx_diagnosis_name", columnList = "name"))
@Getter
@Setter
@NoArgsConstructor
public class Diagnosis extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(name = "name", nullable = false)
    private String name;
}
