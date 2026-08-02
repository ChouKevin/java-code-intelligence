package com.java.semantic.syntax.domain;

import java.nio.file.Path;
import java.util.Optional;

/** 由 canonical target 定位唯一來源宣告的框架中立邊界 */
public interface ExactSourceDeclarationResolver {

    Optional<ExactSourceDeclaration> resolve(Path repositoryRoot, ExactSourceDeclarationTarget target);
}
