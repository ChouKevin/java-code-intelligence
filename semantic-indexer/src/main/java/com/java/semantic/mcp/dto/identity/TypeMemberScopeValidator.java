package com.java.semantic.mcp.dto.identity;

import com.java.semantic.mcp.dto.identity.McpJavaIdentityPayloads.SourceMember;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** 驗證 MCP source member 與 TYPE scope 的相容性 */
public final class TypeMemberScopeValidator implements ConstraintValidator<TypeMemberScope, SourceMember> {

    @Override
    public boolean isValid(SourceMember value, ConstraintValidatorContext context) {
        return value == null || value instanceof SourceMember.TypeMember; // cs-allow
    }
}
