package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.*;
import jakarta.validation.Valid;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// CRUD for visits, diagnoses, prescriptions, and sick leaves.
@RestController
@RequestMapping("/api/visits")
@RequiredArgsConstructor
public class VisitController {

    private final VisitService visitService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<List<VisitResponse>> getAllVisits() {
        return ResponseEntity.ok(visitService.getAllVisits());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<VisitResponse> getVisitById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(visitService.getVisitById(id, authentication));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<VisitResponse> createVisit(@Valid @RequestBody VisitRequest request, Authentication authentication) {
        return new ResponseEntity<>(visitService.createVisit(request, authentication), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<VisitResponse> updateVisit(@PathVariable Long id, @Valid @RequestBody VisitRequest request, Authentication authentication) {
        return ResponseEntity.ok(visitService.updateVisit(id, request, authentication));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> deleteVisit(@PathVariable Long id, Authentication authentication) {
        visitService.deleteVisit(id, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<List<VisitResponse>> getVisitsByPatient(@PathVariable Long patientId, Authentication authentication) {
        return ResponseEntity.ok(visitService.getVisitsByPatient(patientId, authentication));
    }

    @PostMapping("/diagnosis")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<DiagnosisResponse> addDiagnosis(@Valid @RequestBody DiagnosisRequest request, Authentication authentication) {
        return new ResponseEntity<>(visitService.createDiagnosis(request, authentication), HttpStatus.CREATED);
    }

    @GetMapping("/diagnosis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<DiagnosisResponse> getDiagnosisById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(visitService.getDiagnosisById(id, authentication));
    }

    @PutMapping("/diagnosis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<DiagnosisResponse> updateDiagnosis(@PathVariable Long id, @Valid @RequestBody DiagnosisRequest request, Authentication authentication) {
        return ResponseEntity.ok(visitService.updateDiagnosis(id, request, authentication));
    }

    @DeleteMapping("/diagnosis/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> deleteDiagnosis(@PathVariable Long id, Authentication authentication) {
        visitService.deleteDiagnosis(id, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/prescription")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<PrescriptionResponse> addPrescription(@Valid @RequestBody PrescriptionRequest request, Authentication authentication) {
        return new ResponseEntity<>(visitService.createPrescription(request, authentication), HttpStatus.CREATED);
    }

    @PostMapping("/sickleave")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<SickLeaveResponse> addSickLeave(@Valid @RequestBody SickLeaveRequest request, Authentication authentication) {
        return new ResponseEntity<>(visitService.createSickLeave(request, authentication), HttpStatus.CREATED);
    }

    @GetMapping("/sickleave/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<SickLeaveResponse> getSickLeaveById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(visitService.getSickLeaveById(id, authentication));
    }

    @PutMapping("/sickleave/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<SickLeaveResponse> updateSickLeave(@PathVariable Long id, @Valid @RequestBody SickLeaveRequest request, Authentication authentication) {
        return ResponseEntity.ok(visitService.updateSickLeave(id, request, authentication));
    }

    @DeleteMapping("/sickleave/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> deleteSickLeave(@PathVariable Long id, Authentication authentication) {
        visitService.deleteSickLeave(id, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/prescription/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR', 'PATIENT')")
    public ResponseEntity<PrescriptionResponse> getPrescriptionById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(visitService.getPrescriptionById(id, authentication));
    }

    @PutMapping("/prescription/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<PrescriptionResponse> updatePrescription(@PathVariable Long id, @Valid @RequestBody PrescriptionRequest request, Authentication authentication) {
        return ResponseEntity.ok(visitService.updatePrescription(id, request, authentication));
    }

    @DeleteMapping("/prescription/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    public ResponseEntity<Void> deletePrescription(@PathVariable Long id, Authentication authentication) {
        visitService.deletePrescription(id, authentication);
        return ResponseEntity.noContent().build();
    }
}
