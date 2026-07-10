package com.enterprise.security.adminservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;

@Configuration
public class MethodSecurityConfig {

    /**
     * Declared static per Spring Security's own guidance for @EnableMethodSecurity beans: method
     * interceptors are created very early as BeanPostProcessors, and a non-static @Bean method here
     * would trigger "bean X is currently in creation" errors during context startup.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(DocumentPermissionEvaluator permissionEvaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }
}
