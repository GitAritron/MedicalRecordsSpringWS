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

    /**
     * Records one completed change.
     * @param entityLabel a short human-readable description of the affected record — a patient's
     *        name, a diagnosis, and so on. Stored rather than resolved when the log is read,
     *        because entries routinely describe records that have since been deleted, and an id on
     *        its own then names something nobody can look up. May be null where no sensible label
     *        exists; the entry is still written.
     */
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

    /**
     * Who performed the action, named the way a person reads it.
     * <p>
     * WHY the username rather than the token subject: an audit trail exists to be read, and the
     * subject is a Keycloak UUID — a column of them tells an administrator nothing about who did
     * what. {@code SecurityConfig} builds the authentication with {@code preferred_username} as its
     * name, so that is available here at no cost. The subject remains the fallback for a token that
     * carries no username, and "system" for actions with no authenticated actor at all.
     */
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

    /** Keeps the label inside the column width; the audit entry must never fail on its own field. */
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
