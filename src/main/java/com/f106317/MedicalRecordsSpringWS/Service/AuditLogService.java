package com.f106317.MedicalRecordsSpringWS.Service;

import com.f106317.MedicalRecordsSpringWS.Data.Entity.AuditLog;
import com.f106317.MedicalRecordsSpringWS.Data.Repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    /**
     * Audit action names. Kept as constants because the pending-registration view is built by
     * subtracting one action from another — a typo in either string would silently produce an
     * empty or never-clearing list.
     */
    public static final String ACTION_SELF_REGISTER = "SELF_REGISTER";
    public static final String ACTION_APPROVE_DOCTOR = "APPROVE_DOCTOR";
    public static final String ENTITY_DOCTOR = "Doctor";

    private final AuditLogRepository auditLogRepository;
    private final AuthorizationService authorizationService;

    @Transactional
    public void log(Authentication authentication, String action, String entityType, Long entityId) {
        AuditLog log = new AuditLog();
        String actor = authorizationService.extractSubject(authentication);
        log.setActor(actor == null ? "system" : actor);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
    }

    /**
     * Doctor self-registrations that no administrator has approved yet.
     * WHAT: every SELF_REGISTER entry for a Doctor, minus those with a later APPROVE_DOCTOR entry.
     * WHY: approval has to be derivable from what the system records, otherwise the list can never
     * empty. Subtracting the two actions means approving a doctor removes them from it immediately.
     */
    @Transactional(readOnly = true)
    public List<AuditLog> findPendingDoctorRegistrations() {
        Set<Long> approved =
                auditLogRepository.findEntityIdsByActionAndEntityType(ACTION_APPROVE_DOCTOR, ENTITY_DOCTOR);
        return auditLogRepository
                .findByActionAndEntityTypeOrderByCreatedAtDesc(ACTION_SELF_REGISTER, ENTITY_DOCTOR)
                .stream()
                .filter(log -> !approved.contains(log.getEntityId()))
                .toList();
    }
}
