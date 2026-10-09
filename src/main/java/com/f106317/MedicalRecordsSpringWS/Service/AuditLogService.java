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

    // Constants to avoid typos - pending status is derived by subtracting these two actions.
    public static final String ACTION_SELF_REGISTER = "SELF_REGISTER";
    public static final String ACTION_APPROVE_DOCTOR = "APPROVE_DOCTOR";
    public static final String ENTITY_DOCTOR = "Doctor";

    private final AuditLogRepository auditLogRepository;
    private final AuthorizationService authorizationService;

    // entityLabel is stored at write time because the record may be deleted later.
    @Transactional
    public void log(Authentication authentication, String action, String entityType,
                    Long entityId, String entityLabel) {
        AuditLog log = new AuditLog();
        log.setActor(resolveActor(authentication));
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setEntityLabel(truncate(entityLabel));
        auditLogRepository.save(log);
    }

    // Uses the readable username (from JWT preferred_username), not the Keycloak UUID.
    private String resolveActor(Authentication authentication) {
        if (authentication == null) {
            return "system";
        }
        String username = authentication.getName();
        if (username != null && !username.isBlank()) {
            return username;
        }
        String subject = authorizationService.extractSubject(authentication);
        return subject == null ? "system" : subject;
    }

    private String truncate(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.length() <= 150 ? trimmed : trimmed.substring(0, 147) + "...";
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
    }

    // Quick lookup: which doctor IDs are still pending approval?
    @Transactional(readOnly = true)
    public Set<Long> findPendingDoctorIds() {
        return findPendingDoctorRegistrations().stream()
                .map(AuditLog::getEntityId)
                .collect(java.util.stream.Collectors.toSet());
    }

    // SELF_REGISTER entries minus those with a matching APPROVE_DOCTOR entry.
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
