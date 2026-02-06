package com.f106317.MedicalRecordsSpringWS.Data.Entity;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.springframework.lang.Nullable;

@Entity
@Getter
@Setter
public class Patient extends BaseEntity {

//    @UniqueConstraint(name = "uniqueEGN")

    String egn;

    String firstName;

    String lastName;

    @ManyToOne
    @JoinColumn(name = "gp_doctor_id")
    Doctor GP;


}
