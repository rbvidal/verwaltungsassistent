package reasoning.audit.infrastructure;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditEventPage;
import reasoning.audit.api.AuditEventRepository;
import reasoning.common.audit.AuditEventType;
import reasoning.audit.api.AuditQuery;
import reasoning.audit.api.AuditRequestContext;
import reasoning.audit.api.AuditService;
import reasoning.audit.api.AuditSource;
import reasoning.common.audit.AuditSubject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Default implementation of {@link AuditService} that persists events through
 * an {@link AuditEventRepository}.
 *
 * <p>Emit methods run in their own transaction (REQUIRES_NEW) so that audit
 * events survive a rollback in the caller — e.g. a failed login rolls back
 * the authentication transaction, but the USER_LOGIN_FAILED audit event
 * must still be persisted.</p>
 */
@Service
public class PersistentAuditService implements AuditService {

    private final AuditEventRepository repository;

    public PersistentAuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(AuditEvent event) {
        repository.append(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(AuditEventType eventType, AuditSubject subject, AuditSource source) {
        AuditRequestContext context = AuditRequestContext.current();
        repository.append(new AuditEvent(
                UUID.randomUUID(),
                Instant.now(),
                subject.actorId(),
                subject.tenantId(),
                eventType,
                subject.entityType(),
                subject.entityId(),
                source.module(),
                context.correlationId(),
                context.requestId(),
                context.requestPath(),
                context.httpMethod(),
                context.clientIp(),
                source.metadata()));
    }

    @Override
    public AuditEventPage query(AuditQuery query) {
        return repository.find(query);
    }
}
