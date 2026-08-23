package com.java.semantic.syntax.application.concept;

import java.util.List;
import java.util.Objects;

import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/** TYPE_USAGE 在宣告型別結構中的巢狀路徑節點 */
public sealed interface TypeUsagePath permits
        TypeUsagePath.TypeArgument,
        TypeUsagePath.WildcardExtendsBound,
        TypeUsagePath.WildcardSuperBound,
        TypeUsagePath.TypeVariableBound {

    /** 無巢狀型別路徑的唯一不可變值 */
    List<TypeUsagePath> EMPTY = List.of();

    /** 既有平坦型別使用的唯一不可變空路徑 */
    static List<TypeUsagePath> empty() {
        return EMPTY;
    }

    /** 將路徑正規化為不可變序列，空路徑固定為唯一值 */
    static List<TypeUsagePath> copyOf(List<TypeUsagePath> path) {
        List<TypeUsagePath> usagePath = Objects.requireNonNull(path, "path is required");
        return CollectionUtils.isEmpty(usagePath) ? EMPTY : List.copyOf(usagePath);
    }

    /** 參數化型別的第幾個型別參數 */
    record TypeArgument(int index) implements TypeUsagePath {

        public TypeArgument {
            Assert.isTrue(index >= 0, "index must not be negative");
        }

    }

    /** 萬用字元 extends 上界 */
    record WildcardExtendsBound() implements TypeUsagePath {

    }

    /** 萬用字元 super 下界 */
    record WildcardSuperBound() implements TypeUsagePath {

    }

    /** 型別變數的第幾個上界 */
    record TypeVariableBound(int index) implements TypeUsagePath {

        public TypeVariableBound {
            Assert.isTrue(index >= 0, "index must not be negative");
        }

    }
}
