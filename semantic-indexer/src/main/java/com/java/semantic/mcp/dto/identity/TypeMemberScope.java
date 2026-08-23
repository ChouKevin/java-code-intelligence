package com.java.semantic.mcp.dto.identity;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 限制 MCP source member 為型別直接成員 */
@Documented
@Constraint(validatedBy = TypeMemberScopeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeMemberScope {

    String message() default "source member must use TYPE scope";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
