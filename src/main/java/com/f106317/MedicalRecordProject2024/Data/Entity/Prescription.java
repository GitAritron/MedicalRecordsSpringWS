package com.f106317.MedicalRecordProject2024.Data.Entity;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Prescription extends BaseEntity {

@ManyToOne
@JoinColumn(name = "visit_id")
Visit visit;

String note;

}
