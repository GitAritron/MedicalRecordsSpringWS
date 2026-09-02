package com.f106317.MedicalRecordsSpringWS.Controller;

import com.f106317.MedicalRecordsSpringWS.DTO.PatientResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.ReportDTO;
import com.f106317.MedicalRecordsSpringWS.DTO.VisitResponse;
import com.f106317.MedicalRecordsSpringWS.DTO.PendingDoctorResponse;
import com.f106317.MedicalRecordsSpringWS.Service.DoctorService;
import com.f106317.MedicalRecordsSpringWS.Service.VisitService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
@RequiredArgsConstructor
public class ReportController {

    private final VisitService visitService;
    private final DoctorService doctorService;

    @GetMapping("/diagnoses/common")
    public ResponseEntity<List<ReportDTO.DiagnosisCount>> getMostCommonDiagnoses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getMostCommonDiagnoses(), page, size));
    }

    @GetMapping("/diagnoses/names")
    public ResponseEntity<List<String>> getDistinctDiagnosisNames() {
        return ResponseEntity.ok(visitService.getDistinctDiagnosisNames());
    }

    /**
     * Doctors awaiting approval. Delegates to DoctorService: deciding what "pending" means is
     * domain logic, and the previous inline filter matched CREATE — the action an administrator's
     * own doctor creation writes — so it listed the wrong doctors and never emptied.
     */
    @GetMapping("/pending-registrations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PendingDoctorResponse>> getPendingRegistrations() {
        return ResponseEntity.ok(doctorService.getPendingRegistrations());
    }

    @GetMapping("/diagnoses/patients")
    public ResponseEntity<List<PatientResponse>> getPatientsByDiagnosis(
            @RequestParam String diagnosis,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getPatientsByDiagnosis(diagnosis), page, size));
    }

    @GetMapping("/doctors/visits")
    public ResponseEntity<List<ReportDTO.DoctorVisitCount>> getDoctorsWithVisitCount(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(doctorService.getDoctorsWithVisitCount(), page, size));
    }

    @GetMapping("/doctors/gp-patients")
    public ResponseEntity<List<ReportDTO.GpDoctorPatientCount>> getGpDoctorPatientCounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(doctorService.getGpDoctorPatientCounts(), page, size));
    }

    @GetMapping("/sickleaves/months")
    public ResponseEntity<List<ReportDTO.SickLeaveMonthCount>> getMonthsWithMostSickLeaves(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getMonthsWithMostSickLeaves(), page, size));
    }

    @GetMapping("/sickleaves/doctors")
    public ResponseEntity<List<ReportDTO.DoctorSickLeaveCount>> getDoctorsWithMostSickLeaves(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getDoctorsWithMostSickLeaves(), page, size));
    }

    @GetMapping("/visits/period")
    public ResponseEntity<List<VisitResponse>> getVisitsInPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getVisitsInPeriod(start, end), page, size));
    }

    @GetMapping("/visits/doctor/period")
    public ResponseEntity<List<VisitResponse>> getVisitsByDoctorInPeriod(
            @RequestParam Long doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paginate(visitService.getVisitsByDoctorInPeriod(doctorId, start, end), page, size));
    }

    @GetMapping("/visits/patients")
    public ResponseEntity<List<VisitResponse>> getVisitsByAllPatients(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<VisitResponse> sorted = visitService.getAllVisits().stream()
                .sorted((a, b) -> {
                    int patientCmp = a.patientName().compareToIgnoreCase(b.patientName());
                    if (patientCmp != 0) {
                        return patientCmp;
                    }
                    return a.visitDate().compareTo(b.visitDate());
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(paginate(sorted, page, size));
    }

    @GetMapping(value = "/export/diagnoses/common", produces = "text/csv")
    public ResponseEntity<String> exportMostCommonDiagnosesCsv() {
        List<ReportDTO.DiagnosisCount> data = visitService.getMostCommonDiagnoses();
        String csv = toCsv("diagnosisName,count", data, row -> escape(row.diagnosisName()) + "," + row.count());
        return csvResponse("most-common-diagnoses.csv", csv);
    }

    @GetMapping(value = "/export/doctors/visits", produces = "text/csv")
    public ResponseEntity<String> exportDoctorVisitsCsv() {
        List<ReportDTO.DoctorVisitCount> data = doctorService.getDoctorsWithVisitCount();
        String csv = toCsv("doctorId,doctorName,visitCount", data, row -> row.doctorId() + "," + escape(row.doctorName()) + "," + row.visitCount());
        return csvResponse("doctor-visit-count.csv", csv);
    }

    @GetMapping(value = "/export/sickleaves/doctors", produces = "text/csv")
    public ResponseEntity<String> exportSickLeavesByDoctorCsv() {
        List<ReportDTO.DoctorSickLeaveCount> data = visitService.getDoctorsWithMostSickLeaves();
        String csv = toCsv("doctorId,doctorName,sickLeaveCount", data, row -> row.doctorId() + "," + escape(row.doctorName()) + "," + row.sickLeaveCount());
        return csvResponse("doctor-sickleave-count.csv", csv);
    }

    /** Largest page an endpoint will serve, matching the cap AuditLogService already applies. */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Returns one page of an already-materialised list.
     * WHY the long: {@code page * size} was computed as an int and silently overflowed to a
     * negative offset for large page numbers, so the {@code start >= size} guard passed and
     * subList threw IndexOutOfBoundsException — a 500 from a query string alone. Widening the
     * multiplication before the comparison removes the wrap, and clamping size keeps a caller
     * from requesting an unbounded page.
     */
    private <T> List<T> paginate(List<T> data, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        long start = (long) Math.max(0, page) * safeSize;
        if (start >= data.size()) {
            return List.of();
        }
        int from = (int) start;
        return data.subList(from, Math.min(from + safeSize, data.size()));
    }

    private <T> String toCsv(String header, List<T> rows, Function<T, String> mapper) {
        StringBuilder builder = new StringBuilder(header).append("\n");
        for (T row : rows) {
            builder.append(mapper.apply(row)).append("\n");
        }
        return builder.toString();
    }

    private ResponseEntity<String> csvResponse(String fileName, String csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
