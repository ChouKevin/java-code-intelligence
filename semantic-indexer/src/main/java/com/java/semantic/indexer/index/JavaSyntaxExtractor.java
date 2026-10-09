package com.java.semantic.indexer.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration.ConfigOption;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexContract.CandidateKind;
import com.java.semantic.model.index.IndexContract.Declaration;
import com.java.semantic.model.index.IndexContract.DeclarationKind;
import com.java.semantic.model.index.IndexContract.Diagnostic;
import com.java.semantic.model.index.IndexContract.EntryCandidate;
import com.java.semantic.model.index.IndexContract.Evidence;
import com.java.semantic.model.index.IndexContract.EvidenceKind;
import com.java.semantic.model.index.IndexContract.FileOutcome;
import com.java.semantic.model.index.IndexContract.ParsedFile;
import com.java.semantic.model.index.IndexContract.ParseStatus;
import com.java.semantic.model.index.IndexContract.SourceSpan;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses only the explicit syntax present in one inventory-authorized Java source file. */
public final class JavaSyntaxExtractor implements SemanticIndexParser {
    private static final Map<String, CandidateKind> SUPPORTED_ANNOTATIONS = Map.of(
            "org.springframework.web.bind.annotation.RequestMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.web.bind.annotation.GetMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.web.bind.annotation.PostMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.web.bind.annotation.PutMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.web.bind.annotation.DeleteMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.web.bind.annotation.PatchMapping", CandidateKind.HTTP_MAPPING,
            "org.springframework.scheduling.annotation.Scheduled", CandidateKind.SCHEDULED,
            "org.springframework.amqp.rabbit.annotation.RabbitListener", CandidateKind.RABBIT_LISTENER);
    private static final Map<String, CandidateKind> SUPPORTED_SIMPLE_ANNOTATIONS = supportedSimpleAnnotations();
    // Signatures contain syntax, not neighboring comments; comment placement scans sibling lists per node.
    private static final DefaultPrettyPrinter SYNTAX_PRINTER = new DefaultPrettyPrinter(
            new DefaultPrinterConfiguration()
                    .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_COMMENTS))
                    .removeOption(new DefaultConfigurationOption(ConfigOption.PRINT_JAVADOC)));

    private JavaParser parser;

    public JavaSyntaxExtractor() {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setPreprocessUnicodeEscapes(false)
                .setTabSize(1)
                .setStoreTokens(true);
        parser = new JavaParser(configuration);
    }

    @Override
    public synchronized ParsedFile parse(String generation, SourceInventoryEntry entry, byte[] source) {
        String checkedGeneration = IndexContract.requireGeneration(generation);
        Objects.requireNonNull(entry, "source inventory entry");
        Objects.requireNonNull(source, "exact source bytes");
        if (entry.kind() != EntryKind.FILE) {
            throw new IllegalArgumentException("semantic parsing requires a file inventory entry");
        }
        if (source.length != entry.byteLength()) {
            throw new IllegalArgumentException("source bytes do not match inventory length");
        }

        boolean javaFile = entry.path().endsWith(".java");
        if (!javaFile) {
            return emptyFile(entry, false, ParseStatus.UNSUPPORTED, List.of());
        }
        if (source.length > IndexContract.MAX_FILE_BYTES) {
            return emptyFile(entry, true, ParseStatus.TOO_LARGE, List.of());
        }
        if (entry.status().orElseThrow() != EntryStatus.TEXT) {
            return emptyFile(entry, true, ParseStatus.UNSUPPORTED, List.of());
        }
        requireContentIdentity(entry, source);

        RawJavaSourceMap sourceMap;
        try {
            sourceMap = RawJavaSourceMap.decode(source);
        } catch (CharacterCodingException exception) {
            return failedFile(entry, List.of(new Diagnostic("INVALID_UTF8", Optional.empty())));
        } catch (RawJavaSourceMap.InvalidUnicodeEscapeException exception) {
            return failedFile(entry, List.of(new Diagnostic("JAVA_PARSE_FAILED", Optional.empty())));
        }

        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(ParseStart.COMPILATION_UNIT,
                    Providers.provider(new StringReader(sourceMap.source())));
            if (!parseResult.getProblems().isEmpty()) {
                return failedFile(entry, diagnostics(parseResult.getProblems(), sourceMap));
            }
            Optional<CompilationUnit> compilationUnit = parseResult.getResult();
            if (compilationUnit.isEmpty()) {
                return failedFile(entry, List.of(new Diagnostic("JAVA_PARSE_FAILED", Optional.empty())));
            }
            return materialize(checkedGeneration, entry, sourceMap, compilationUnit.orElseThrow());
        } catch (IndexContract.InvalidUnicodeTextException exception) {
            return emptyFile(entry, true, ParseStatus.UNSUPPORTED,
                    List.of(new Diagnostic("UNSUPPORTED_UNICODE_VALUE", Optional.empty())));
        } catch (InvalidTranslatedEscapeException exception) {
            return failedFile(entry, List.of(new Diagnostic("JAVA_PARSE_FAILED", Optional.empty())));
        } catch (UnmappableSourceRangeException exception) {
            return failedFile(entry, List.of(new Diagnostic("SOURCE_RANGE_UNMAPPABLE", Optional.empty())));
        } catch (StackOverflowError exhaustedParserStack) {
            // Recursive parsing/AST visitors can exhaust the stack on bounded source. Discard all file facts
            // and the partially mutated parser; other VM failures must still propagate.
            parser = new JavaParser(parser.getParserConfiguration());
            return failedFile(entry, List.of(new Diagnostic("PARSER_STACK_EXHAUSTED", Optional.empty())));
        }
    }

    private static ParsedFile materialize(String generation, SourceInventoryEntry entry,
            RawJavaSourceMap sourceMap, CompilationUnit compilationUnit) {
        IdentityHashMap<Node, Draft> draftByNode = new IdentityHashMap<>();
        List<Draft> drafts = collectDrafts(compilationUnit, draftByNode);
        for (Draft draft : drafts) {
            draft.range = requireSpan(sourceMap, draft.rangeNode);
            draft.nameRange = requireSpan(sourceMap, draft.nameNode);
            draft.symbolId = IndexContract.identity(generation, entry.path(), draft.kind.name(),
                    Long.toString(draft.nameRange.startByte()));
        }
        drafts.sort(Comparator.comparingLong((Draft draft) -> draft.nameRange.startByte())
                .thenComparing(draft -> draft.kind.name())
                .thenComparing(draft -> draft.symbolId));

        for (Draft draft : drafts) {
            draft.parent = nearestParent(draft.node, draftByNode);
            draft.owner = lexicalOwner(draft.node, compilationUnit, sourceMap, draftByNode);
        }

        IdentityHashMap<Node, Declaration> declarationByNode = new IdentityHashMap<>();
        List<IndexContract.Fold> folds = new ArrayList<>();
        List<Declaration> declarations = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            Optional<String> parentId = draft.parent.map(parent -> parent.symbolId);
            Declaration declaration = new Declaration(draft.symbolId, draft.name, draft.kind, draft.owner,
                    parentId, entry.path(), entry.blobId().orElseThrow(), entry.contentDigest().orElseThrow(),
                    entry.byteLength(), draft.range, draft.nameRange, draft.signature);
            declarationByNode.put(draft.node, declaration);
            declarations.add(declaration);
            Optional<BlockStmt> body = Optional.empty();
            if (draft.node instanceof MethodDeclaration method) body = method.getBody();
            else if (draft.node instanceof ConstructorDeclaration constructor) body = Optional.of(constructor.getBody());
            else if (draft.node instanceof CompactConstructorDeclaration constructor) body = Optional.of(constructor.getBody());
            body.ifPresent(block -> {
                TokenRange tokens = block.getTokenRange().orElseThrow(UnmappableSourceRangeException::new);
                SourceSpan opening = tokens.getBegin().getRange().flatMap(sourceMap::span).orElseThrow(UnmappableSourceRangeException::new);
                SourceSpan closing = tokens.getEnd().getRange().flatMap(sourceMap::span).orElseThrow(UnmappableSourceRangeException::new);
                if (!tokens.getBegin().getText().equals("{") || !tokens.getEnd().getText().equals("}"))
                    throw new UnmappableSourceRangeException();
                if (closing.startByte() > opening.endByte())
                    folds.add(new IndexContract.Fold(declaration.symbolId(), declaration.kind().name(), sourceMap.interior(opening, closing)));
            });
        }

        List<EntryCandidate> candidates = materializeCandidates(generation, sourceMap,
                compilationUnit, drafts, declarationByNode);
        candidates.sort(Comparator.comparingLong((IndexContract.EntryCandidate candidate) -> candidate.evidence().range().startByte())
                .thenComparing(candidate -> candidate.kind().name())
                .thenComparing(IndexContract.EntryCandidate::candidateId));
        FileOutcome file = new FileOutcome(entry.path(), true, ParseStatus.PARSED, entry.byteLength(),
                entry.blobId(), entry.contentDigest(), List.of());
        return new ParsedFile(file, declarations, candidates, folds);
    }

    private static List<Draft> collectDrafts(CompilationUnit compilationUnit,
            IdentityHashMap<Node, Draft> draftByNode) {
        List<Draft> drafts = new ArrayList<>();
        compilationUnit.walk((Node node) -> {
            rejectUntranslatedEscapes(node);
            if (node instanceof AnnotationDeclaration annotationType) {
                addDraft(drafts, draftByNode, annotationType, annotationType, annotationType.getName(),
                        DeclarationKind.ANNOTATION_TYPE, typeSignature(annotationType));
            } else if (node instanceof RecordDeclaration record) {
                addDraft(drafts, draftByNode, record, record, record.getName(),
                        DeclarationKind.RECORD, recordSignature(record));
                for (Parameter component : record.getParameters()) {
                    addDraft(drafts, draftByNode, component, component, component.getName(),
                            DeclarationKind.RECORD_COMPONENT, SYNTAX_PRINTER.print(component));
                }
            } else if (node instanceof EnumDeclaration enumType) {
                addDraft(drafts, draftByNode, enumType, enumType, enumType.getName(),
                        DeclarationKind.ENUM, enumSignature(enumType));
                for (EnumConstantDeclaration constant : enumType.getEntries()) {
                    addDraft(drafts, draftByNode, constant, constant, constant.getName(),
                            DeclarationKind.ENUM_CONSTANT, enumConstantSignature(constant));
                }
            } else if (node instanceof ClassOrInterfaceDeclaration type) {
                DeclarationKind kind = type.isInterface() ? DeclarationKind.INTERFACE : DeclarationKind.CLASS;
                addDraft(drafts, draftByNode, type, type, type.getName(), kind, classSignature(type));
            } else if (node instanceof FieldDeclaration field) {
                for (VariableDeclarator variable : field.getVariables()) {
                    addDraft(drafts, draftByNode, variable, field, variable.getName(),
                            DeclarationKind.FIELD, fieldSignature(field, variable));
                }
            } else if (node instanceof MethodDeclaration method) {
                addDraft(drafts, draftByNode, method, method, method.getName(),
                        DeclarationKind.METHOD, methodSignature(method));
            } else if (node instanceof ConstructorDeclaration constructor) {
                addDraft(drafts, draftByNode, constructor, constructor, constructor.getName(),
                        DeclarationKind.CONSTRUCTOR, constructorSignature(constructor));
            } else if (node instanceof CompactConstructorDeclaration constructor) {
                addDraft(drafts, draftByNode, constructor, constructor, constructor.getName(),
                        DeclarationKind.CONSTRUCTOR, compactConstructorSignature(constructor));
            } else if (node instanceof AnnotationMemberDeclaration member) {
                addDraft(drafts, draftByNode, member, member, member.getName(),
                        DeclarationKind.ANNOTATION_MEMBER, annotationMemberSignature(member));
            }
        });
        return drafts;
    }

    private static void addDraft(List<Draft> drafts, IdentityHashMap<Node, Draft> draftByNode,
            Node node, Node rangeNode, Node nameNode, DeclarationKind kind, String signature) {
        Draft draft = new Draft(node, rangeNode, nameNode, kind, nameNode.toString(), signature);
        drafts.add(draft);
        draftByNode.put(node, draft);
    }

    private static void rejectUntranslatedEscapes(Node node) {
        // JavaParser's lexer also accepts Unicode escape spellings when preprocessing is disabled.
        // JLS translation is nonrecursive: a remaining escape cannot become an identifier or literal escape.
        if ((node instanceof SimpleName name && name.getIdentifier().indexOf('\\') >= 0)
                || (node instanceof Name qualified && qualified.getIdentifier().indexOf('\\') >= 0)) {
            throw new InvalidTranslatedEscapeException();
        }
        String value = switch (node) {
            case StringLiteralExpr literal -> literal.getValue();
            case CharLiteralExpr literal -> literal.getValue();
            case TextBlockLiteralExpr literal -> literal.getValue();
            default -> "";
        };
        for (int index = 0; index + 1 < value.length(); index++) {
            if (value.charAt(index) == '\\' && value.charAt(++index) == 'u') {
                throw new InvalidTranslatedEscapeException();
            }
        }
    }

    private static List<EntryCandidate> materializeCandidates(String generation,
            RawJavaSourceMap sourceMap, CompilationUnit compilationUnit,
            List<Draft> drafts, IdentityHashMap<Node, Declaration> declarationByNode) {
        Map<String, List<String>> directImports = directImports(compilationUnit);
        IdentityHashMap<TypeDeclaration<?>, Set<String>> localTypeNamesByTopLevel = new IdentityHashMap<>();
        Set<String> fileTypeNames = new HashSet<>();
        for (Draft draft : drafts) {
            if (draft.node instanceof TypeDeclaration<?> type) {
                if (type.isTopLevelType()) {
                    fileTypeNames.add(draft.name);
                }
                topLevelType(draft.node).ifPresent(topLevel -> localTypeNamesByTopLevel
                        .computeIfAbsent(topLevel, ignored -> new HashSet<>()).add(draft.name));
            }
        }

        List<EntryCandidate> candidates = new ArrayList<>();
        for (Draft draft : drafts) {
            Declaration declaration = declarationByNode.get(draft.node);
            Set<String> localTypeNames = topLevelType(draft.node)
                    .map(topLevel -> localTypeNamesByTopLevel.getOrDefault(topLevel, Set.of()))
                    .orElse(Set.of());
            if (draft.node instanceof MethodDeclaration method && isMainMethod(method)) {
                Evidence evidence = new Evidence(EvidenceKind.MAIN_SIGNATURE, Optional.empty(), Map.of(), List.of(), draft.range);
                candidates.add(candidate(generation, declaration, CandidateKind.MAIN, evidence));
            }
            for (AnnotationExpr annotation : directAnnotations(draft.node)) {
                String annotationName = annotation.getName().asString();
                boolean localNameAmbiguous = fileTypeNames.contains(annotationName) || localTypeNames.contains(annotationName);
                Optional<AnnotationMatch> match = annotationMatch(annotation, directImports, localNameAmbiguous);
                if (match.isEmpty()) {
                    continue;
                }
                SourceSpan annotationRange = requireSpan(sourceMap, annotation);
                AnnotationValues values = annotationValues(annotation);
                AnnotationMatch annotationMatch = match.orElseThrow();
                Evidence evidence = new Evidence(annotationMatch.evidenceKind, Optional.of(annotationName),
                        values.literalValues, values.unresolvedMembers, annotationRange);
                candidates.add(candidate(generation, declaration, annotationMatch.candidateKind, evidence));
            }
        }
        return candidates;
    }

    private static EntryCandidate candidate(String generation, Declaration declaration,
            CandidateKind kind, Evidence evidence) {
        String candidateId = IndexContract.identity(generation, declaration.symbolId(), kind.name(),
                Long.toString(evidence.range().startByte()));
        return new EntryCandidate(candidateId, kind, declaration, evidence);
    }

    private static Optional<AnnotationMatch> annotationMatch(AnnotationExpr annotation,
            Map<String, List<String>> directImports, boolean localNameAmbiguous) {
        String spelling = annotation.getName().asString();
        Optional<CandidateKind> fullyQualifiedKind = Optional.ofNullable(SUPPORTED_ANNOTATIONS.get(spelling));
        if (fullyQualifiedKind.isPresent()) {
            return Optional.of(new AnnotationMatch(fullyQualifiedKind.orElseThrow(), EvidenceKind.FULLY_QUALIFIED));
        }
        if (spelling.indexOf('.') >= 0) {
            return Optional.empty();
        }
        Optional<CandidateKind> simpleKind = Optional.ofNullable(SUPPORTED_SIMPLE_ANNOTATIONS.get(spelling));
        if (simpleKind.isEmpty()) {
            return Optional.empty();
        }
        List<String> imports = directImports.getOrDefault(spelling, List.of());
        if (imports.size() == 1 && !localNameAmbiguous) {
            if (simpleKind.orElseThrow() == SUPPORTED_ANNOTATIONS.get(imports.get(0))) {
                return Optional.of(new AnnotationMatch(simpleKind.orElseThrow(), EvidenceKind.DIRECT_IMPORT));
            }
            return Optional.empty();
        }
        return Optional.of(new AnnotationMatch(simpleKind.orElseThrow(), EvidenceKind.AMBIGUOUS_SIMPLE));
    }

    private static Map<String, List<String>> directImports(CompilationUnit compilationUnit) {
        Map<String, List<String>> imports = new HashMap<>();
        compilationUnit.getImports().stream()
                .filter(importDeclaration -> !importDeclaration.isStatic() && !importDeclaration.isAsterisk())
                .forEach(importDeclaration -> {
                    String importedName = importDeclaration.getName().asString();
                    int separator = importedName.lastIndexOf('.');
                    String simpleName = importedName.substring(separator + 1);
                    imports.computeIfAbsent(simpleName, ignored -> new ArrayList<>()).add(importedName);
                });
        return imports;
    }

    private static List<AnnotationExpr> directAnnotations(Node node) {
        if (node instanceof TypeDeclaration<?> type) {
            return type.getAnnotations();
        }
        if (node instanceof MethodDeclaration method) {
            return method.getAnnotations();
        }
        return List.of();
    }

    private static AnnotationValues annotationValues(AnnotationExpr annotation) {
        Map<String, List<String>> literalValues = new LinkedHashMap<>();
        List<String> unresolvedMembers = new ArrayList<>();
        Set<String> seenMembers = new HashSet<>();
        if (annotation instanceof SingleMemberAnnotationExpr singleMember) {
            addAnnotationValue("value", singleMember.getMemberValue(), seenMembers, literalValues, unresolvedMembers);
        } else if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                addAnnotationValue(pair.getNameAsString(), pair.getValue(), seenMembers, literalValues, unresolvedMembers);
            }
        }
        return new AnnotationValues(literalValues, unresolvedMembers);
    }

    private static void addAnnotationValue(String memberName, Expression expression, Set<String> seenMembers,
            Map<String, List<String>> literalValues, List<String> unresolvedMembers) {
        if (!seenMembers.add(memberName)) {
            literalValues.remove(memberName);
            if (!unresolvedMembers.contains(memberName)) {
                unresolvedMembers.add(memberName);
            }
            return;
        }
        Optional<List<String>> values = literalValues(expression);
        if (values.isPresent()) {
            literalValues.put(memberName, values.orElseThrow());
        } else {
            unresolvedMembers.add(memberName);
        }
    }

    private static Optional<List<String>> literalValues(Expression expression) {
        if (expression instanceof ArrayInitializerExpr array) {
            List<String> values = new ArrayList<>(array.getValues().size());
            for (Expression element : array.getValues()) {
                Optional<String> value = literalScalar(element);
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                values.add(value.orElseThrow());
            }
            return Optional.of(List.copyOf(values));
        }
        return literalScalar(expression).map(List::of);
    }

    private static Optional<String> literalScalar(Expression expression) {
        if (expression instanceof StringLiteralExpr stringLiteral) {
            return Optional.of(stringLiteral.asString());
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteral) {
            return Optional.of(textBlockLiteral.asString());
        }
        if (expression instanceof CharLiteralExpr characterLiteral) {
            return Optional.of(Character.toString(characterLiteral.asChar()));
        }
        if (expression instanceof BooleanLiteralExpr booleanLiteral) {
            return Optional.of(Boolean.toString(booleanLiteral.getValue()));
        }
        if (expression instanceof IntegerLiteralExpr || expression instanceof LongLiteralExpr
                || expression instanceof DoubleLiteralExpr) {
            return Optional.of(SYNTAX_PRINTER.print(expression));
        }
        if (expression instanceof NullLiteralExpr) {
            return Optional.of("null");
        }
        if (expression instanceof UnaryExpr unary && unary.isPrefix()
                && (unary.getOperator() == UnaryExpr.Operator.PLUS || unary.getOperator() == UnaryExpr.Operator.MINUS)) {
            Optional<String> value = literalScalar(unary.getExpression());
            if (value.isPresent()) {
                String sign = unary.getOperator() == UnaryExpr.Operator.MINUS ? "-" : "+";
                return Optional.of(sign + value.orElseThrow());
            }
        }
        return Optional.empty();
    }

    private static boolean isMainMethod(MethodDeclaration method) {
        if (!method.getNameAsString().equals("main") || !method.hasModifier(Modifier.Keyword.PUBLIC)
                || !method.hasModifier(Modifier.Keyword.STATIC)
                || !method.getType().isVoidType() || method.getParameters().size() != 1) {
            return false;
        }
        Parameter parameter = method.getParameter(0);
        if (parameter.isVarArgs()) {
            return isStringType(parameter.getType());
        }
        Type parameterType = parameter.getType();
        if (!(parameterType instanceof ArrayType arrayType) || arrayType.getArrayLevel() != 1) {
            return false;
        }
        return isStringType(arrayType.getComponentType());
    }

    private static boolean isStringType(Type type) {
        if (!(type instanceof ClassOrInterfaceType classType)
                || classType.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isPresent()) {
            return false;
        }
        String writtenName = classType.getNameWithScope();
        return writtenName.equals("String") || writtenName.equals("java.lang.String");
    }

    private static Optional<Draft> nearestParent(Node node, IdentityHashMap<Node, Draft> draftByNode) {
        Optional<Node> ancestor = node.getParentNode();
        while (ancestor.isPresent()) {
            Node current = ancestor.orElseThrow();
            Optional<Draft> parent = Optional.ofNullable(draftByNode.get(current));
            if (parent.isPresent()) {
                return parent;
            }
            ancestor = current.getParentNode();
        }
        return Optional.empty();
    }

    private static String lexicalOwner(Node declarationNode, CompilationUnit compilationUnit,
            RawJavaSourceMap sourceMap, IdentityHashMap<Node, Draft> draftByNode) {
        StringBuilder owner = new StringBuilder(compilationUnit.getPackageDeclaration()
                .map(packageDeclaration -> packageDeclaration.getNameAsString()).orElse(""));
        List<Node> ancestors = new ArrayList<>();
        Optional<Node> ancestor = declarationNode.getParentNode();
        while (ancestor.isPresent()) {
            Node current = ancestor.orElseThrow();
            ancestors.add(current);
            ancestor = current.getParentNode();
        }
        for (int index = ancestors.size() - 1; index >= 0; index--) {
            Node scope = ancestors.get(index);
            if (scope instanceof TypeDeclaration<?> type) {
                boolean memberType = type.getParentNode()
                        .filter(parent -> parent instanceof CompilationUnit || parent instanceof TypeDeclaration<?>)
                        .isPresent();
                if (memberType) {
                    appendNamedType(owner, type.getNameAsString());
                } else {
                    appendScopedSegment(owner, "local:" + type.getNameAsString(), type, sourceMap);
                }
            } else if (scope instanceof MethodDeclaration method) {
                appendScopedSegment(owner, "method:" + method.getNameAsString(), method, sourceMap);
            } else if (scope instanceof ConstructorDeclaration constructor) {
                appendScopedSegment(owner, "constructor:" + constructor.getNameAsString(), constructor, sourceMap);
            } else if (scope instanceof CompactConstructorDeclaration constructor) {
                appendScopedSegment(owner, "compact-constructor:" + constructor.getNameAsString(), constructor,
                        sourceMap);
            } else if (scope instanceof InitializerDeclaration initializer) {
                appendScopedSegment(owner, initializer.isStatic() ? "static-initializer" : "initializer",
                        initializer, sourceMap);
            } else if (scope instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                appendScopedSegment(owner, "anonymous", creation, sourceMap);
            } else if (scope instanceof LambdaExpr lambda) {
                appendScopedSegment(owner, "lambda", lambda, sourceMap);
            } else if (scope instanceof EnumConstantDeclaration constant) {
                appendScopedSegment(owner, "enum-constant:" + constant.getNameAsString(), constant,
                        sourceMap);
            } else {
                Optional<Draft> scopeDraft = Optional.ofNullable(draftByNode.get(scope));
                if (scopeDraft.filter(draft -> draft.kind == DeclarationKind.FIELD).isPresent()) {
                    appendScopedSegment(owner, "field:" + scopeDraft.orElseThrow().name, scope,
                            sourceMap);
                }
            }
        }
        return owner.toString();
    }

    private static void appendNamedType(StringBuilder owner, String typeName) {
        if (owner.length() > 0) {
            owner.append('.');
        }
        owner.append(typeName);
    }

    private static void appendScopedSegment(StringBuilder owner, String segment, Node scope,
            RawJavaSourceMap sourceMap) {
        SourceSpan span = requireSpan(sourceMap, scope);
        owner.append('#').append(segment).append('@').append(span.startByte());
    }

    private static SourceSpan requireSpan(RawJavaSourceMap sourceMap, Node node) {
        return node.getRange().flatMap(sourceMap::span)
                .orElseThrow(UnmappableSourceRangeException::new);
    }

    private static List<Diagnostic> diagnostics(List<Problem> problems, RawJavaSourceMap sourceMap) {
        List<Diagnostic> diagnostics = new ArrayList<>(Math.min(problems.size(), IndexContract.MAX_DIAGNOSTICS));
        for (Problem problem : problems) {
            if (diagnostics.size() == IndexContract.MAX_DIAGNOSTICS) {
                break;
            }
            Optional<SourceSpan> location = problem.getLocation()
                    .flatMap(TokenRange::toRange)
                    .flatMap(sourceMap::span);
            diagnostics.add(new Diagnostic("JAVA_PARSE_PROBLEM", location));
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add(new Diagnostic("JAVA_PARSE_FAILED", Optional.empty()));
        }
        return List.copyOf(diagnostics);
    }

    private static ParsedFile emptyFile(SourceInventoryEntry entry, boolean javaFile, ParseStatus status,
            List<Diagnostic> diagnostics) {
        FileOutcome file = new FileOutcome(entry.path(), javaFile, status, entry.byteLength(),
                entry.blobId(), entry.contentDigest(), diagnostics);
        return new ParsedFile(file, List.of(), List.of(), List.of());
    }

    private static ParsedFile failedFile(SourceInventoryEntry entry, List<Diagnostic> diagnostics) {
        return emptyFile(entry, true, ParseStatus.PARSE_FAILED, diagnostics);
    }

    private static void requireContentIdentity(SourceInventoryEntry entry, byte[] source) {
        String actualDigest = sha256(source);
        if (entry.contentDigest().filter(actualDigest::equals).isEmpty()) {
            throw new IllegalArgumentException("source bytes do not match inventory digest");
        }
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required SHA-256 unavailable", exception);
        }
    }

    private static Map<String, CandidateKind> supportedSimpleAnnotations() {
        Map<String, CandidateKind> simpleNames = new HashMap<>();
        SUPPORTED_ANNOTATIONS.forEach((fullyQualifiedName, kind) -> {
            int separator = fullyQualifiedName.lastIndexOf('.');
            simpleNames.put(fullyQualifiedName.substring(separator + 1), kind);
        });
        return Map.copyOf(simpleNames);
    }

    private static Optional<TypeDeclaration<?>> topLevelType(Node node) {
        Optional<Node> current = Optional.of(node);
        while (current.isPresent()) {
            Node scope = current.orElseThrow();
            if (scope instanceof TypeDeclaration<?> type && type.isTopLevelType()) {
                return Optional.of(type);
            }
            current = scope.getParentNode();
        }
        return Optional.empty();

    }
    private static String typeSignature(AnnotationDeclaration declaration) {
        return withModifiers(declaration.getModifiers(), "@interface " + declaration.getNameAsString());
    }

    private static String classSignature(ClassOrInterfaceDeclaration declaration) {
        String typeKind = declaration.isInterface() ? "interface " : "class ";
        String signature = typeKind + declaration.getNameAsString()
                + typeParameters(declaration.getTypeParameters());
        signature = appendClause(signature, "extends", declaration.getExtendedTypes());
        signature = appendClause(signature, "implements", declaration.getImplementedTypes());
        return withModifiers(declaration.getModifiers(), signature);
    }

    private static String recordSignature(RecordDeclaration declaration) {
        String parameters = declaration.getParameters().stream().map(SYNTAX_PRINTER::print).collect(Collectors.joining(", "));
        String signature = "record " + declaration.getNameAsString() + typeParameters(declaration.getTypeParameters())
                + "(" + parameters + ")";
        return withModifiers(declaration.getModifiers(),
                appendClause(signature, "implements", declaration.getImplementedTypes()));
    }

    private static String enumSignature(EnumDeclaration declaration) {
        String signature = appendClause("enum " + declaration.getNameAsString(), "implements",
                declaration.getImplementedTypes());
        return withModifiers(declaration.getModifiers(), signature);
    }

    private static String methodSignature(MethodDeclaration method) {
        String signature = typeParameters(method.getTypeParameters());
        if (!signature.isEmpty()) {
            signature += " ";
        }
        signature += SYNTAX_PRINTER.print(method.getType()) + " " + method.getNameAsString() + "("
                + method.getParameters().stream().map(SYNTAX_PRINTER::print).collect(Collectors.joining(", ")) + ")";
        if (!method.getThrownExceptions().isEmpty()) {
            signature += " throws " + method.getThrownExceptions().stream()
                    .map(SYNTAX_PRINTER::print).collect(Collectors.joining(", "));
        }
        return withModifiers(method.getModifiers(), signature);
    }

    private static String constructorSignature(ConstructorDeclaration constructor) {
        String signature = typeParameters(constructor.getTypeParameters());
        if (!signature.isEmpty()) {
            signature += " ";
        }
        signature += constructor.getNameAsString() + "("
                + constructor.getParameters().stream().map(SYNTAX_PRINTER::print).collect(Collectors.joining(", ")) + ")";
        if (!constructor.getThrownExceptions().isEmpty()) {
            signature += " throws " + constructor.getThrownExceptions().stream()
                    .map(SYNTAX_PRINTER::print).collect(Collectors.joining(", "));
        }
        return withModifiers(constructor.getModifiers(), signature);
    }

    private static String compactConstructorSignature(CompactConstructorDeclaration constructor) {
        String signature = "compact constructor " + constructor.getNameAsString();
        if (!constructor.getThrownExceptions().isEmpty()) {
            signature += " throws " + constructor.getThrownExceptions().stream()
                    .map(SYNTAX_PRINTER::print).collect(Collectors.joining(", "));
        }
        return withModifiers(constructor.getModifiers(), signature);
    }

    private static String fieldSignature(FieldDeclaration field, VariableDeclarator variable) {
        return withModifiers(field.getModifiers(), SYNTAX_PRINTER.print(variable.getType()) + " " + variable.getNameAsString());
    }

    private static String enumConstantSignature(EnumConstantDeclaration constant) {
        String signature = constant.getNameAsString();
        if (!constant.getArguments().isEmpty()) {
            signature += "(" + constant.getArguments().stream().map(SYNTAX_PRINTER::print)
                    .collect(Collectors.joining(", ")) + ")";
        }
        return signature;
    }

    private static String annotationMemberSignature(AnnotationMemberDeclaration member) {
        String signature = SYNTAX_PRINTER.print(member.getType()) + " " + member.getNameAsString() + "()";
        if (member.getDefaultValue().isPresent()) {
            signature += " default " + SYNTAX_PRINTER.print(member.getDefaultValue().orElseThrow());
        }
        return withModifiers(member.getModifiers(), signature);
    }

    private static String typeParameters(NodeList<TypeParameter> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        return "<" + typeParameters.stream().map(SYNTAX_PRINTER::print).collect(Collectors.joining(", ")) + ">";
    }

    private static String appendClause(String signature, String keyword, Iterable<? extends Node> types) {
        List<String> values = new ArrayList<>();
        for (Node type : types) {
            values.add(SYNTAX_PRINTER.print(type));
        }
        return values.isEmpty() ? signature : signature + " " + keyword + " " + String.join(", ", values);
    }

    private static String withModifiers(NodeList<Modifier> modifiers, String signature) {
        String prefix = modifiers.stream().map(modifier -> modifier.getKeyword().asString())
                .collect(Collectors.joining(" "));
        return prefix.isEmpty() ? signature : prefix + " " + signature;
    }


    private static final class Draft {
        private final Node node;
        private final Node rangeNode;
        private final Node nameNode;
        private final DeclarationKind kind;
        private final String name;
        private final String signature;
        private SourceSpan range;
        private SourceSpan nameRange;
        private String symbolId;
        private String owner;
        private Optional<Draft> parent = Optional.empty();

        private Draft(Node node, Node rangeNode, Node nameNode, DeclarationKind kind, String name,
                String signature) {
            this.node = node;
            this.rangeNode = rangeNode;
            this.nameNode = nameNode;
            this.kind = kind;
            this.name = name;
            this.signature = signature;
        }
    }

    private record AnnotationMatch(CandidateKind candidateKind, EvidenceKind evidenceKind) { }

    private record AnnotationValues(Map<String, List<String>> literalValues, List<String> unresolvedMembers) { }

    private static final class UnmappableSourceRangeException extends RuntimeException { }
    private static final class InvalidTranslatedEscapeException extends RuntimeException { }

}
