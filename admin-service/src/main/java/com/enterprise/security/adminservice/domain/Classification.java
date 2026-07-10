package com.enterprise.security.adminservice.domain;

/**
 * Ordinal doubles as the required clearance level (PUBLIC=0 .. RESTRICTED=3), compared against
 * the caller's "clearance" JWT claim in DocumentPermissionEvaluator.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
