package com.java.semantic.syntax.adapter.jdt;

import java.io.Serial;

/**
 * 遇到不該出現在欄位、參數或 supertype 位置的型別語法
 * <p>
 * 明確拋出而非退回 toString，避免 flattener 的排版悄悄變成 metadata 的一部分
 */
class UnsupportedTypeFormException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    UnsupportedTypeFormException(String typeForm) {
        super("Unsupported type form in a declaration position: " + typeForm);
    }
}
