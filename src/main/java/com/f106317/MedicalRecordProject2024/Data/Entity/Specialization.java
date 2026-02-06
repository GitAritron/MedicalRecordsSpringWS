package com.f106317.MedicalRecordProject2024.Data.Entity;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Entity
@Getter
@Setter
public class Specialization extends BaseEntity {

    String name;





    @ManyToMany
    Set<Doctor> doctor;




}





