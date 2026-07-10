package com.enterprise.security.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a method whose invocation (principal, arguments, outcome) should be written to the audit log. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /** Short name of the business action, e.g. "ORDER_CREATE". Defaults to the method name if blank. */
    String action() default "";
}
