package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.java.semantic.model.codefact.JavaIdentityNormalizer;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.syntax.domain.ArrayTypeReference;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.CompilationUnitContext;
import com.java.semantic.syntax.domain.CompositeTypeReference;
import com.java.semantic.syntax.domain.CompositeTypeReference.CompositeKind;
import com.java.semantic.syntax.domain.InferredTypeReference;
import com.java.semantic.syntax.domain.NamedTypeReference;
import com.java.semantic.syntax.domain.NominalTypeReference;
import com.java.semantic.syntax.domain.ParameterizedTypeReference;
import com.java.semantic.syntax.domain.PrimitiveTypeReference;
import com.java.semantic.syntax.domain.FrameworkTypeFacts;
import com.java.semantic.syntax.domain.SourceFieldMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeDeclaration;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMembers;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceTypeRelationships;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.syntax.domain.SqlSourceKind;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.TypeVariableReference;
import com.java.semantic.syntax.domain.WildcardTypeReference;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.model.codefact.SourceTypeIdentity;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IntersectionType;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.WildcardType;
import org.springframework.util.StringUtils;

/** 從型別宣告抽取輕量 metadata */
final class SourceTypeMetadataExtractor {

    /** MyBatis annotation SQL，名稱與 XML 標籤一一對應 */
    private static final List<String> SQL_ANNOTATIONS = List.of("Select", "Insert", "Update", "Delete");

    private static final String PROFILE = "Profile";

    private static final String ACCESSORS = "Accessors";

    private static final String PRIMARY = "Primary";

    private static final String QUALIFIER = "Qualifier";

    private SourceTypeMetadataExtractor() {
    }

    static SourceTypeMetadata extract(ParsedSource parsed, AbstractTypeDeclaration type,
            MapperXmlSqlExtractor.SqlIndex sqlIndex,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        String packageName = PackageNames.of(parsed);
        String className = SourceTypes.nestedName(type);
        String fullyQualifiedName = PackageNames.qualify(packageName, className);
        CompilationUnit unit = parsed.unit();
        String sourceFile = parsed.source().repositoryRelativePath();

        return new SourceTypeMetadata(
                new SourceTypeDeclaration(
                        new SourceTypeIdentity(
                                new JavaTypeIdentity(packageName, className),
                                parsed.source().repositoryRelativePath()),
                        kindOf(type),
                        isAbstract(type),
                        new SourceRange(sourceFile, AstSourceRanges.declarationRange(unit, type))),
                new SourceTypeRelationships(
                        extendedTypeReferencesOf(type),
                        implementedTypeReferencesOf(type)),
                new SourceTypeMembers(
                        fieldsOf(type),
                        methodsOf(parsed, type, fullyQualifiedName, sqlIndex, analysisTargetOf),
                        hasFluentAccessors(type),
                        hasChainedAccessors(type)),
                new FrameworkTypeFacts(
                        annotationEvidenceOf(type),
                        profilesOf(type),
                        AnnotationReader.isPresent(type, PRIMARY),
                        qualifierValuesOf(type)),
                new CompilationUnitContext(importsOf(parsed.unit())));
    }

    // --- 型別形狀 ---

    private static SourceTypeKind kindOf(AbstractTypeDeclaration type) {
        if (type instanceof RecordDeclaration) {
            return SourceTypeKind.RECORD;
        }
        if (SourceTypes.isEnum(type)) {
            return SourceTypeKind.ENUM;
        }
        return SourceTypes.isInterface(type) ? SourceTypeKind.INTERFACE : SourceTypeKind.CLASS;
    }

    private static boolean isAbstract(AbstractTypeDeclaration type) {
        for (Object modifier : type.modifiers()) {
            if (modifier instanceof Modifier keyword && keyword.isAbstract()) {
                return true;
            }
        }
        return false;
    }

    private static List<NominalTypeReference> implementedTypeReferencesOf(
            AbstractTypeDeclaration type) {
        List<NominalTypeReference> references = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            addTypeReferences(references, record.superInterfaceTypes());
        } else if (type instanceof EnumDeclaration enumDeclaration) {
            addTypeReferences(references, enumDeclaration.superInterfaceTypes());
        } else if (type instanceof TypeDeclaration typeDeclaration) {
            if (!typeDeclaration.isInterface()) {
                addTypeReferences(references, typeDeclaration.superInterfaceTypes());
            }
        }
        return List.copyOf(references);
    }

    private static List<NominalTypeReference> extendedTypeReferencesOf(
            AbstractTypeDeclaration type) {
        if (type instanceof TypeDeclaration typeDeclaration && !(type instanceof RecordDeclaration)) {
            if (typeDeclaration.isInterface()) {
                List<NominalTypeReference> references = new ArrayList<>();
                addTypeReferences(references, typeDeclaration.superInterfaceTypes());
                return List.copyOf(references);
            }
            Type superclass = typeDeclaration.getSuperclassType();
            return Objects.nonNull(superclass) ? List.of(nominalTypeReferenceOf(superclass)) : List.of();
        }
        return List.of();
    }

    private static void addTypeReferences(List<NominalTypeReference> references, List<?> types) {
        for (Object type : types) {
            references.add(nominalTypeReferenceOf((Type) type));
        }
    }

    // --- 成員 ---

    private static List<SourceFieldMetadata> fieldsOf(AbstractTypeDeclaration type) {
        List<SourceFieldMetadata> fields = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            for (Object component : record.recordComponents()) {
                SingleVariableDeclaration parameter = (SingleVariableDeclaration) component;
                fields.add(new SourceFieldMetadata(
                        parameter.getName().getIdentifier(),
                        TypeNames.simpleNameOf(parameter.getType()),
                        qualifierValueOf(parameter),
                        typeReferenceOf(parameter.getType()),
                        annotationEvidenceOf(parameter)));
            }
        }
        for (Object member : SourceTypes.declaredMembersOf(type)) {
            if (!(member instanceof FieldDeclaration field)) {
                continue;
            }
            String typeName = TypeNames.simpleNameOf(field.getType());
            for (Object variable : field.fragments()) {
                fields.add(new SourceFieldMetadata(
                        ((VariableDeclarationFragment) variable).getName().getIdentifier(),
                        typeName,
                        qualifierValueOf(field),
                        typeReferenceOf(field.getType()),
                        annotationEvidenceOf(field)));
            }
        }
        return List.copyOf(fields);
    }

    private static List<SourceMethodMetadata> methodsOf(ParsedSource parsed, AbstractTypeDeclaration type,
            String fullyQualifiedName,
            MapperXmlSqlExtractor.SqlIndex sqlIndex,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        CompilationUnit unit = parsed.unit();
        List<SourceMethodMetadata> methods = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            String name = method.getName().getIdentifier();

            // annotation SQL 優先於 XML，判準是「有沒有 @Select 之類的註解」而非解析出的字串是否為空
            Optional<Annotation> sqlAnnotation = AnnotationReader.findAny(method, SQL_ANNOTATIONS);
            boolean annotationSql = sqlAnnotation
                    .map(annotation -> String.join(" ", AnnotationReader.stringValues(annotation, "value")))
                    .filter(value -> !value.isBlank())
                    .isPresent();
            boolean mapperSql = !annotationSql && sqlIndex.find(fullyQualifiedName, name).isPresent();
            SqlSourceKind sqlSource = sqlSourceOf(annotationSql, mapperSql);
            Optional<SourceRange> annotationSqlLocation = annotationSql
                    ? sqlAnnotation.map(annotation -> new SourceRange(
                            parsed.source().repositoryRelativePath(), AstSourceRanges.range(unit, annotation)))
                    : Optional.empty();

            methods.add(new SourceMethodMetadata(
                    name,
                    parameterTypesOf(method),
                    sqlSource,
                    annotationSqlLocation,
                    new SourceRange(
                            parsed.source().repositoryRelativePath(), AstSourceRanges.declarationRange(unit, method)),
                    parameterTypeReferencesOf(method),
                    returnTypeReferenceOf(method),
                    InvocationExtractor.extract(unit, method, parsed.text()),
                    annotationEvidenceOf(method),
                    BodyTypeReferenceExtractor.extract(method),
                    AstSourceRanges.range(unit, method.getName()).start(),
                    analysisTargetOf.apply(method),
                    Objects.nonNull(method.getBody()),
                    isAbstractDeclaration(type, method),
                    isOverridableDeclaration(type, method)));
        }
        return List.copyOf(methods);
    }

    private static boolean isOverridableDeclaration(AbstractTypeDeclaration enclosingType, MethodDeclaration method) {
        if (method.isConstructor() || enclosingType instanceof RecordDeclaration
                || isExplicitlyFinalClass(enclosingType)) {
            return false;
        }
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Modifier keyword
                    && (keyword.isPrivate() || keyword.isStatic() || keyword.isFinal())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAbstractDeclaration(AbstractTypeDeclaration enclosingType, MethodDeclaration method) {
        if (Modifier.isNative(method.getModifiers())) {
            return false;
        }
        return Modifier.isAbstract(method.getModifiers())
                || (SourceTypes.isInterface(enclosingType)
                && Objects.isNull(method.getBody())
                && isOverridableDeclaration(enclosingType, method));
    }

    private static boolean isExplicitlyFinalClass(AbstractTypeDeclaration type) {
        if (!(type instanceof TypeDeclaration typeDeclaration) || typeDeclaration.isInterface()) {
            return false;
        }
        for (Object modifier : type.modifiers()) {
            if (modifier instanceof Modifier keyword && keyword.isFinal()) {
                return true;
            }
        }
        return false;
    }

    private static SqlSourceKind sqlSourceOf(boolean annotationSql, boolean mapperSql) {
        if (!annotationSql && !mapperSql) {
            return null;
        }
        return annotationSql ? SqlSourceKind.ANNOTATION : SqlSourceKind.MAPPER_XML;
    }

    private static List<String> parameterTypesOf(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            SingleVariableDeclaration declaration = (SingleVariableDeclaration) parameter;
            String parameterType = TypeNames.simpleNameOf(declaration.getType());
            types.add(JavaIdentityNormalizer.parameterType(
                    declaration.isVarargs() ? parameterType + "[]" : parameterType));
        }
        return List.copyOf(types);
    }

    private static List<TypeReference> parameterTypeReferencesOf(MethodDeclaration method) {
        List<TypeReference> types = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            types.add(typeReferenceOf(((SingleVariableDeclaration) parameter).getType()));
        }
        return List.copyOf(types);
    }

    private static Optional<TypeReference> returnTypeReferenceOf(MethodDeclaration method) {
        Type returnType = method.getReturnType2();
        return Objects.isNull(returnType) ? Optional.empty() : Optional.of(typeReferenceOf(returnType));
    }

    static TypeReference typeReferenceOf(Type type) {
        return typeReferenceOf(type, new HashSet<>());
    }

    private static TypeReference typeReferenceOf(
            Type type,
            Set<String> visitingBindings) {
        ITypeBinding binding = type.resolveBinding();
        String writtenType = type.toString();
        if (type instanceof SimpleType simple && simple.isVar()) {
            return new InferredTypeReference(writtenType, sourceDefinedOf(binding));
        }
        if (type instanceof PrimitiveType) {
            return new PrimitiveTypeReference(writtenType);
        }
        if (isProvenTypeVariable(binding)) {
            return new TypeVariableReference(
                    writtenType,
                    binding.getName(),
                    bindingBoundReferencesOf(binding, visitingBindings),
                    sourceDefinedOf(binding));
        }
        if (type instanceof ParameterizedType parameterized) {
            NamedTypeReference rawType = namedTypeReferenceOf(parameterized.getType());
            List<TypeReference> arguments = new ArrayList<>();
            for (Object argument : parameterized.typeArguments()) {
                arguments.add(typeReferenceOf((Type) argument, visitingBindings));
            }
            return new ParameterizedTypeReference(writtenType, rawType, arguments);
        }
        if (type instanceof ArrayType array) {
            return new ArrayTypeReference(
                    writtenType,
                    typeReferenceOf(array.getElementType(), visitingBindings),
                    array.getDimensions());
        }
        if (type instanceof WildcardType wildcard) {
            Optional<TypeReference> bound = Objects.nonNull(wildcard.getBound())
                    ? Optional.of(typeReferenceOf(wildcard.getBound(), visitingBindings))
                    : Optional.empty();
            if (wildcard.isUpperBound()) {
                return new WildcardTypeReference(
                        writtenType, bound, Optional.empty(), sourceDefinedOf(binding));
            }
            return new WildcardTypeReference(
                    writtenType, Optional.empty(), bound, sourceDefinedOf(binding));
        }
        if (type instanceof UnionType union) {
            return compositeTypeReferenceOf(writtenType, CompositeKind.UNION, union.types(), binding,
                    visitingBindings);
        }
        if (type instanceof IntersectionType intersection) {
            return compositeTypeReferenceOf(writtenType, CompositeKind.INTERSECTION, intersection.types(),
                    binding, visitingBindings);
        }
        return nominalTypeReferenceOf(type);
    }

    static NominalTypeReference nominalTypeReferenceOf(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            NamedTypeReference rawType = namedTypeReferenceOf(parameterized.getType());
            List<TypeReference> arguments = new ArrayList<>();
            for (Object argument : parameterized.typeArguments()) {
                arguments.add(typeReferenceOf((Type) argument));
            }
            return new ParameterizedTypeReference(type.toString(), rawType, arguments);
        }
        return namedTypeReferenceOf(type);
    }

    private static NamedTypeReference namedTypeReferenceOf(Type type) {
        if (!(type instanceof SimpleType || type instanceof QualifiedType || type instanceof NameQualifiedType)) {
            throw new UnsupportedTypeFormException(type.getClass().getSimpleName());
        }
        ITypeBinding binding = type.resolveBinding();
        if (isProvenTypeVariable(binding)) {
            throw new UnsupportedTypeFormException(type.getClass().getSimpleName());
        }
        return new NamedTypeReference(
                type.toString(),
                TypeNames.simpleNameOf(type),
                resolvedNamedTypeOf(binding),
                sourceDefinedOf(binding));
    }

    private static CompositeTypeReference compositeTypeReferenceOf(
            String writtenType,
            CompositeKind kind,
            List<?> types,
            ITypeBinding binding,
            Set<String> visitingBindings) {
        List<TypeReference> alternatives = new ArrayList<>();
        for (Object type : types) {
            alternatives.add(typeReferenceOf((Type) type, visitingBindings));
        }
        return new CompositeTypeReference(writtenType, kind, alternatives, sourceDefinedOf(binding));
    }

    private static List<TypeReference> bindingBoundReferencesOf(
            ITypeBinding binding,
            Set<String> visitingBindings) {
        if (!bindingCarriesBounds(binding)) {
            return List.of();
        }
        String bindingIdentity = binding.getKey();
        if (!StringUtils.hasText(bindingIdentity) || !visitingBindings.add(bindingIdentity)) {
            return List.of();
        }
        try {
            List<TypeReference> bounds = new ArrayList<>();
            for (ITypeBinding bound : binding.getTypeBounds()) {
                bindingReferenceOf(bound, visitingBindings).ifPresent(bounds::add);
            }
            return List.copyOf(bounds);
        } finally {
            visitingBindings.remove(bindingIdentity);
        }
    }

    private static boolean bindingCarriesBounds(ITypeBinding binding) {
        return isProvenTypeVariable(binding);
    }

    private static Optional<TypeReference> bindingReferenceOf(
            ITypeBinding binding,
            Set<String> visitingBindings) {
        ITypeBinding sourceType = sourceTypeOf(binding);
        if (sourceType.isRecovered() || !StringUtils.hasText(binding.getName())) {
            return Optional.empty();
        }
        if (isProvenTypeVariable(binding)) {
            return Optional.of(new TypeVariableReference(
                    binding.getName(),
                    binding.getName(),
                    bindingBoundReferencesOf(binding, visitingBindings),
                    sourceDefinedOf(binding)));
        }
        if (binding.isArray()) {
            Optional<TypeReference> element = bindingReferenceOf(binding.getElementType(), visitingBindings);
            return element.map(reference -> new ArrayTypeReference(
                    binding.getName(), reference, binding.getDimensions()));
        }
        if (binding.isWildcardType() && Objects.nonNull(binding.getBound())) {
            Optional<TypeReference> bound = bindingReferenceOf(binding.getBound(), visitingBindings);
            if (binding.isUpperbound()) {
                return Optional.of(new WildcardTypeReference(
                        binding.getName(), bound, Optional.empty(), sourceDefinedOf(binding)));
            }
            return Optional.of(new WildcardTypeReference(
                    binding.getName(), Optional.empty(), bound, sourceDefinedOf(binding)));
        }
        if (binding.isPrimitive()) {
            return Optional.of(new PrimitiveTypeReference(binding.getName()));
        }
        Optional<JavaTypeIdentity> identity = resolvedNamedTypeOf(binding);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        ITypeBinding declaration = sourceTypeOf(binding);
        NamedTypeReference rawType = new NamedTypeReference(
                declaration.getName(), declaration.getName(), identity, sourceDefinedOf(binding));
        List<TypeReference> arguments = new ArrayList<>();
        for (ITypeBinding argument : binding.getTypeArguments()) {
            bindingReferenceOf(argument, visitingBindings).ifPresent(arguments::add);
        }
        return arguments.isEmpty()
                ? Optional.of(rawType)
                : Optional.of(new ParameterizedTypeReference(binding.getName(), rawType, arguments));
    }

    private static boolean isProvenTypeVariable(ITypeBinding binding) {
        return Objects.nonNull(binding) && !binding.isRecovered() && binding.isTypeVariable();
    }

    private static Optional<JavaTypeIdentity> resolvedNamedTypeOf(ITypeBinding binding) {
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return Optional.empty();
        }
        ITypeBinding sourceType = sourceTypeOf(binding);
        if (sourceType.isRecovered() || !StringUtils.hasText(sourceType.getQualifiedName())) {
            return Optional.empty();
        }
        IPackageBinding packageBinding = sourceType.getPackage();
        if (Objects.isNull(packageBinding)) {
            return Optional.empty();
        }
        String packageName = packageBinding.getName();
        return Optional.of(new JavaTypeIdentity(packageName, sourceType.getQualifiedName()));
    }

    private static boolean sourceDefinedOf(ITypeBinding binding) {
        return Objects.nonNull(binding) && !binding.isRecovered() && sourceTypeOf(binding).isFromSource();
    }

    private static ITypeBinding sourceTypeOf(ITypeBinding binding) {
        ITypeBinding declaration = binding.getTypeDeclaration();
        if (Objects.isNull(declaration)) {
            return binding;
        }
        if (!declaration.isArray()) {
            return declaration;
        }
        ITypeBinding elementType = declaration.getElementType();
        if (Objects.isNull(elementType)) {
            return declaration;
        }
        ITypeBinding elementDeclaration = elementType.getTypeDeclaration();
        return Objects.nonNull(elementDeclaration) ? elementDeclaration : elementType;
    }

    // --- 註解衍生資訊 ---

    /** 保留原始碼寫法，@org.springframework.stereotype.Service 不會被縮短 */
    private static List<String> annotationNamesOf(BodyDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(AnnotationReader::writtenNameOf)
                .toList();
    }

    private static List<AnnotationEvidence> annotationEvidenceOf(BodyDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(SourceTypeMetadataExtractor::annotationEvidenceOf)
                .toList();
    }

    private static List<String> annotationNamesOf(SingleVariableDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(AnnotationReader::writtenNameOf)
                .toList();
    }

    private static List<AnnotationEvidence> annotationEvidenceOf(SingleVariableDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(SourceTypeMetadataExtractor::annotationEvidenceOf)
                .toList();
    }

    private static AnnotationEvidence annotationEvidenceOf(Annotation annotation) {
        ITypeBinding binding = annotation.resolveTypeBinding();
        if (Objects.isNull(binding) || binding.isRecovered()) {
            return new AnnotationEvidence(AnnotationReader.writtenNameOf(annotation), Optional.empty());
        }
        ITypeBinding declaration = binding.getTypeDeclaration();
        String qualifiedName = declaration.getQualifiedName();
        if (declaration.isRecovered() || !StringUtils.hasText(qualifiedName)) {
            return new AnnotationEvidence(AnnotationReader.writtenNameOf(annotation), Optional.empty());
        }
        String packageName = declaration.getPackage().getName();
        return new AnnotationEvidence(
                AnnotationReader.writtenNameOf(annotation),
                Optional.of(new JavaTypeIdentity(
                        packageName, JavaIdentityNormalizer.className(packageName, qualifiedName))));
    }

    private static String qualifierValueOf(BodyDeclaration declaration) {
        return AnnotationReader.find(declaration, QUALIFIER)
                .flatMap(annotation -> AnnotationReader.stringValue(annotation, "value"))
                .orElse("");
    }

    private static String qualifierValueOf(SingleVariableDeclaration declaration) {
        return AnnotationReader.find(declaration, QUALIFIER)
                .flatMap(annotation -> AnnotationReader.stringValue(annotation, "value"))
                .orElse("");
    }

    private static List<String> qualifierValuesOf(AbstractTypeDeclaration type) {
        return AnnotationReader.find(type, QUALIFIER)
                .map(annotation -> AnnotationReader.stringValues(annotation, "value"))
                .orElseGet(List::of);
    }

    private static List<String> importsOf(CompilationUnit unit) {
        List<String> imports = new ArrayList<>();
        for (Object declaration : unit.imports()) {
            imports.add(((ImportDeclaration) declaration).getName().getFullyQualifiedName());
        }
        return List.copyOf(imports);
    }

    private static boolean hasFluentAccessors(AbstractTypeDeclaration type) {
        return AnnotationReader.find(type, ACCESSORS)
                .flatMap(annotation -> AnnotationReader.booleanValue(annotation, "fluent"))
                .orElse(false);
    }

    private static boolean hasChainedAccessors(AbstractTypeDeclaration type) {
        Optional<Annotation> accessors = AnnotationReader.find(type, ACCESSORS);
        Optional<Boolean> explicitChain = accessors
                .flatMap(annotation -> AnnotationReader.booleanValue(annotation, "chain"));
        return explicitChain.orElseGet(() -> accessors
                .flatMap(annotation -> AnnotationReader.booleanValue(annotation, "fluent"))
                .orElse(false));
    }

    private static List<String> profilesOf(AbstractTypeDeclaration type) {
        return AnnotationReader.find(type, PROFILE)
                .map(annotation -> AnnotationReader.stringValues(annotation, "value"))
                .orElseGet(List::of);
    }

    /** 型別名稱化簡：去泛型、只留最後一段 */
    static final class TypeNames {

        private TypeNames() {
        }

        static String simpleNameOf(Type type) {
            if (type instanceof ParameterizedType parameterized) {
                return simpleNameOf(parameterized.getType());
            }
            if (type instanceof ArrayType array) {
                return simpleNameOf(array.getElementType()) + "[]";
            }
            if (type instanceof SimpleType simple) {
                return lastSegmentOf(simple.getName().getFullyQualifiedName());
            }
            if (type instanceof QualifiedType qualified) {
                return qualified.getName().getIdentifier();
            }
            if (type instanceof NameQualifiedType nameQualified) {
                return nameQualified.getName().getIdentifier();
            }
            if (type instanceof PrimitiveType primitive) {
                return primitive.getPrimitiveTypeCode().toString();
            }
            // 欄位、參數與 supertype 位置只會出現上列型別；其餘種類僅存在於 catch、cast 與型別引數內
            throw new UnsupportedTypeFormException(type.getClass().getSimpleName());
        }

        private static String lastSegmentOf(String name) {
            int lastDot = name.lastIndexOf('.');
            return lastDot < 0 ? name : name.substring(lastDot + 1);
        }
    }
}
