package com.f106317.MedicalRecordsSpringWS.DTO;

import java.util.List;

// Verdict + which months are still owed, so the patient knows what to pay.
public record InsuranceStatusResponse(boolean insured, List<ReportDTO.UnpaidMonth> unpaidMonths) {}
