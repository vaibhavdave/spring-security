package com.enterprise.security.common.audit;

import com.enterprise.security.common.tenant.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

/**
 * Writes one structured log line per @Audited invocation: who, what, on which tenant, and whether
 * it succeeded or was denied/failed. Kept as a single AOP boundary so every resource service gets
 * the same audit shape for free instead of hand-rolling logging in each controller/service.
 */
@Aspect
public class AuditLogAspect {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        String action = audited.action().isBlank()
                ? ((MethodSignature) joinPoint.getSignature()).getMethod().getName()
                : audited.action();
        String principal = currentPrincipal();
        String tenant = TenantContext.get();

        try {
            MDC.put("auditAction", action);
            MDC.put("auditPrincipal", principal);
            MDC.put("auditTenant", tenant == null ? "-" : tenant);

            Object result = joinPoint.proceed();
            auditLog.info("action={} principal={} tenant={} outcome=SUCCESS timestamp={}",
                    action, principal, tenant, Instant.now());
            return result;
        } catch (Exception ex) {
            auditLog.warn("action={} principal={} tenant={} outcome=FAILURE reason={} timestamp={}",
                    action, principal, tenant, ex.getClass().getSimpleName(), Instant.now());
            throw ex;
        } finally {
            MDC.remove("auditAction");
            MDC.remove("auditPrincipal");
            MDC.remove("auditTenant");
        }
    }

    private String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "anonymous" : authentication.getName();
    }
}
