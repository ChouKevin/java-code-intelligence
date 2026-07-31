package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.ResolvedTypeIdentity;
import com.java.semantic.syntax.domain.TypeReference;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.identity.PolicyIdentity;

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
import org.eclipse.jdt.core.dom.WildcardType;
import org.springframework.util.StringUtils;

/** 從型別宣告抽取輕量 metadata */
final class ClassMetadataExtractor {

    /** MyBatis annotation SQL，名稱與 XML 標籤一一對應 */
    private static final List<String> SQL_ANNOTATIONS = List.of("Select", "Insert", "Update", "Delete");

    private static final String PROFILE = "Profile";

    private static final String ACCESSORS = "Accessors";

    private static final String PRIMARY = "Primary";

    private static final String QUALIFIER = "Qualifier";

    private ClassMetadataExtractor() {
    }

    static ClassMetadata extract(ParsedSource parsed, AbstractTypeDeclaration type,
            MapperXmlSqlExtractor.SqlIndex sqlIndex,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        String packageName = PackageNames.of(parsed);
        String className = SourceTypes.nestedName(type);
        String fullyQualifiedName = PackageNames.qualify(packageName, className);
        SourceSlices slices = new SourceSlices(parsed.unit(), parsed.text());

        return new ClassMetadata(
                className,
                packageName,
                fullyQualifiedName,
                parsed.source().repositoryRelativePath(),
                kindOf(type),
                isAbstract(type),
                implementedTypesOf(type),
                extendedTypesOf(type),
                annotationNamesOf(type),
                importsOf(parsed.unit()),
                fieldsOf(type, slices),
                methodsOf(parsed, type, packageName, className, fullyQualifiedName, sqlIndex, slices,
                        analysisTargetOf),
                hasFluentAccessors(type),
                hasChainedAccessors(type),
                profilesOf(type),
                slices.range(type),
                slices.slice(type),
                AnnotationReader.isPresent(type, PRIMARY),
                qualifierValuesOf(type),
                annotationEvidenceOf(type),
                implementedTypeReferencesOf(type, slices),
                extendedTypeReferencesOf(type, slices));
    }

    // --- 型別形狀 ---

    private static TypeKind kindOf(AbstractTypeDeclaration type) {
        if (type instanceof RecordDeclaration) {
            return TypeKind.RECORD;
        }
        if (SourceTypes.isEnum(type)) {
            return TypeKind.ENUM;
        }
        return SourceTypes.isInterface(type) ? TypeKind.INTERFACE : TypeKind.CLASS;
    }

    private static boolean isAbstract(AbstractTypeDeclaration type) {
        for (Object modifier : type.modifiers()) {
            if (modifier instanceof Modifier keyword && keyword.isAbstract()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> implementedTypesOf(AbstractTypeDeclaration type) {
        if (type instanceof RecordDeclaration record) {
            return simpleNamesOf(record.superInterfaceTypes());
        }
        if (type instanceof EnumDeclaration enumDeclaration) {
            return simpleNamesOf(enumDeclaration.superInterfaceTypes());
        }
        if (type instanceof TypeDeclaration typeDeclaration) {
            if (typeDeclaration.isInterface()) {
                return List.of();
            }
            return simpleNamesOf(typeDeclaration.superInterfaceTypes());
        }
        return List.of();
    }

    private static List<String> extendedTypesOf(AbstractTypeDeclaration type) {
        if (type instanceof TypeDeclaration typeDeclaration && !(type instanceof RecordDeclaration)) {
            if (typeDeclaration.isInterface()) {
                return simpleNamesOf(typeDeclaration.superInterfaceTypes());
            }
            Type superclass = typeDeclaration.getSuperclassType();
            return Objects.isNull(superclass) ? List.of() : List.of(TypeNames.simpleNameOf(superclass));
        }
        return List.of();
    }

    private static List<TypeReference> implementedTypeReferencesOf(
            AbstractTypeDeclaration type,
            SourceSlices slices) {
        List<TypeReference> references = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            addTypeReferences(references, record.superInterfaceTypes(), slices);
        } else if (type instanceof EnumDeclaration enumDeclaration) {
            addTypeReferences(references, enumDeclaration.superInterfaceTypes(), slices);
        } else if (type instanceof TypeDeclaration typeDeclaration) {
            if (!typeDeclaration.isInterface()) {
                addTypeReferences(references, typeDeclaration.superInterfaceTypes(), slices);
            }
        }
        return List.copyOf(references);
    }

    private static List<TypeReference> extendedTypeReferencesOf(
            AbstractTypeDeclaration type,
            SourceSlices slices) {
        if (type instanceof TypeDeclaration typeDeclaration && !(type instanceof RecordDeclaration)) {
            if (typeDeclaration.isInterface()) {
                List<TypeReference> references = new ArrayList<>();
                addTypeReferences(references, typeDeclaration.superInterfaceTypes(), slices);
                return List.copyOf(references);
            }
            Type superclass = typeDeclaration.getSuperclassType();
            return Objects.nonNull(superclass) ? List.of(typeReferenceOf(superclass, slices)) : List.of();
        }
        return List.of();
    }

    private static void addTypeReferences(List<TypeReference> references, List<?> types, SourceSlices slices) {
        for (Object type : types) {
            references.add(typeReferenceOf((Type) type, slices));
        }
    }

    private static List<String> simpleNamesOf(List<?> types) {
        List<String> names = new ArrayList<>();
        for (Object type : types) {
            names.add(TypeNames.simpleNameOf((Type) type));
        }
        return List.copyOf(names);
    }

    // --- 成員 ---

    private static List<FieldInfo> fieldsOf(AbstractTypeDeclaration type, SourceSlices slices) {
        List<FieldInfo> fields = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            for (Object component : record.recordComponents()) {
                SingleVariableDeclaration parameter = (SingleVariableDeclaration) component;
                fields.add(new FieldInfo(
                        parameter.getName().getIdentifier(),
                        TypeNames.simpleNameOf(parameter.getType()),
                        annotationNamesOf(parameter),
                        qualifierValueOf(parameter),
                        typeReferenceOf(parameter.getType(), slices),
                        annotationEvidenceOf(parameter)));
            }
        }
        for (Object member : SourceTypes.declaredMembersOf(type)) {
            if (!(member instanceof FieldDeclaration field)) {
                continue;
            }
            String typeName = TypeNames.simpleNameOf(field.getType());
            for (Object variable : field.fragments()) {
                fields.add(new FieldInfo(
                        ((VariableDeclarationFragment) variable).getName().getIdentifier(),
                        typeName,
                        annotationNamesOf(field),
                        qualifierValueOf(field),
                        typeReferenceOf(field.getType(), slices),
                        annotationEvidenceOf(field)));
            }
        }
        return List.copyOf(fields);
    }

    private static List<MethodSignature> methodsOf(ParsedSource parsed, AbstractTypeDeclaration type,
            String packageName, String className, String fullyQualifiedName,
            MapperXmlSqlExtractor.SqlIndex sqlIndex, SourceSlices slices,
            Function<MethodDeclaration, MethodTargetResolution> analysisTargetOf) {
        CompilationUnit unit = parsed.unit();
        List<MethodSignature> methods = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            String name = method.getName().getIdentifier();

            // annotation SQL 優先於 XML，判準是「有沒有 @Select 之類的註解」而非解析出的字串是否為空
            Optional<Annotation> sqlAnnotation = AnnotationReader.findAny(method, SQL_ANNOTATIONS);
            Optional<String> resolvedAnnotationSql = sqlAnnotation
                    .map(annotation -> String.join(" ", AnnotationReader.stringValues(annotation, "value")))
                    .filter(StringUtils::hasText);
            String sql = sqlAnnotation.isPresent()
                    ? resolvedAnnotationSql.orElse(null)
                    : sqlIndex.find(fullyQualifiedName, name).orElse(null);
            SqlSource sqlSource = sqlSourceOf(sqlAnnotation.isPresent(), sql);

            methods.add(new MethodSignature(
                    name,
                    parameterTypesOf(method),
                    annotationNamesOf(method),
                    sql,
                    sqlSource,
                    unit.getLineNumber(method.getStartPosition()),
                    unit.getLineNumber(method.getStartPosition() + method.getLength() - 1),
                    slices.range(method),
                    slices.slice(method),
                    parameterTypeReferencesOf(method, slices),
                    returnTypeReferenceOf(method, slices),
                    InvocationExtractor.extract(unit, method, slices),
                    annotationEvidenceOf(method),
                    BodyTypeReferenceExtractor.extract(method),
                    slices.range(method.getName()).start(),
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

    private static SqlSource sqlSourceOf(boolean fromAnnotation, String sql) {
        if (Objects.isNull(sql)) {
            return null;
        }
        return fromAnnotation ? SqlSource.ANNOTATION : SqlSource.MAPPER_XML;
    }

    private static List<String> parameterTypesOf(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            SingleVariableDeclaration declaration = (SingleVariableDeclaration) parameter;
            String parameterType = TypeNames.simpleNameOf(declaration.getType());
            types.add(PolicyIdentity.parameterType(
                    declaration.isVarargs() ? parameterType + "[]" : parameterType));
        }
        return List.copyOf(types);
    }

    private static List<TypeReference> parameterTypeReferencesOf(MethodDeclaration method, SourceSlices slices) {
        List<TypeReference> types = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            types.add(typeReferenceOf(((SingleVariableDeclaration) parameter).getType(), slices));
        }
        return List.copyOf(types);
    }

    private static Optional<TypeReference> returnTypeReferenceOf(MethodDeclaration method, SourceSlices slices) {
        Type returnType = method.getReturnType2();
        return Objects.isNull(returnType) ? Optional.empty() : Optional.of(typeReferenceOf(returnType, slices));
    }

    private static TypeReference typeReferenceOf(Type type, SourceSlices slices) {
        return typeReferenceOf(type, slices, new HashSet<>());
    }

    private static TypeReference typeReferenceOf(
            Type type,
            SourceSlices slices,
            Set<String> visitingBindings) {
        ITypeBinding binding = type.resolveBinding();
        boolean resolved = Objects.nonNull(binding) && !sourceTypeOf(binding).isRecovered();
        String resolvedType = resolved ? canonicalNameOf(binding) : "";
        boolean sourceDefined = resolved && sourceTypeOf(binding).isFromSource();
        List<TypeReference> arguments = new ArrayList<>();
        List<TypeReference> upperBounds = new ArrayList<>();
        List<TypeReference> lowerBounds = new ArrayList<>();
        if (type instanceof ParameterizedType parameterized) {
            for (Object argument : parameterized.typeArguments()) {
                arguments.add(typeReferenceOf((Type) argument, slices, visitingBindings));
            }
        }
        if (type instanceof ArrayType array) {
            arguments.add(typeReferenceOf(array.getElementType(), slices, visitingBindings));
        }
        if (type instanceof WildcardType wildcard && Objects.nonNull(wildcard.getBound())) {
            List<TypeReference> bounds = boundReferencesOf(wildcard.getBound(), slices, visitingBindings);
            if (wildcard.isUpperBound()) {
                upperBounds.addAll(bounds);
            } else {
                lowerBounds.addAll(bounds);
            }
        }
        if (type instanceof SimpleType && Objects.nonNull(binding)) {
            upperBounds.addAll(bindingBoundReferencesOf(binding, visitingBindings));
        }
        return new TypeReference(
                slices.slice(type).text(), resolvedType, arguments, upperBounds, lowerBounds, sourceDefined);
    }

    private static List<TypeReference> boundReferencesOf(
            Type bound,
            SourceSlices slices,
            Set<String> visitingBindings) {
        if (bound instanceof IntersectionType intersection) {
            List<TypeReference> references = new ArrayList<>();
            for (Object type : intersection.types()) {
                references.add(typeReferenceOf((Type) type, slices, visitingBindings));
            }
            return List.copyOf(references);
        }
        return List.of(typeReferenceOf(bound, slices, visitingBindings));
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
        return binding.isTypeVariable() || binding.isCapture() || binding.isIntersectionType();
    }

    private static Optional<TypeReference> bindingReferenceOf(
            ITypeBinding binding,
            Set<String> visitingBindings) {
        ITypeBinding sourceType = sourceTypeOf(binding);
        if (sourceType.isRecovered() || !StringUtils.hasText(binding.getName())) {
            return Optional.empty();
        }
        List<TypeReference> arguments = new ArrayList<>();
        for (ITypeBinding argument : binding.getTypeArguments()) {
            bindingReferenceOf(argument, visitingBindings).ifPresent(arguments::add);
        }
        if (binding.isArray()) {
            bindingReferenceOf(binding.getElementType(), visitingBindings).ifPresent(arguments::add);
        }
        List<TypeReference> upperBounds = new ArrayList<>();
        List<TypeReference> lowerBounds = new ArrayList<>();
        if (binding.isWildcardType() && Objects.nonNull(binding.getBound())) {
            Optional<TypeReference> bound = bindingReferenceOf(binding.getBound(), visitingBindings);
            if (binding.isUpperbound()) {
                bound.ifPresent(upperBounds::add);
            } else {
                bound.ifPresent(lowerBounds::add);
            }
        }
        upperBounds.addAll(bindingBoundReferencesOf(binding, visitingBindings));
        return Optional.of(new TypeReference(
                binding.getName(), canonicalNameOf(binding), arguments, upperBounds, lowerBounds,
                sourceType.isFromSource()));
    }

    private static String canonicalNameOf(ITypeBinding binding) {
        String qualifiedName = sourceTypeOf(binding).getQualifiedName();
        return StringUtils.hasText(qualifiedName) ? qualifiedName : "";
    }

    private static ITypeBinding sourceTypeOf(ITypeBinding binding) {
        ITypeBinding declaration = binding.getTypeDeclaration();
        return declaration.isArray() ? declaration.getElementType().getTypeDeclaration() : declaration;
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
                .map(ClassMetadataExtractor::annotationEvidenceOf)
                .toList();
    }

    private static List<String> annotationNamesOf(SingleVariableDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(AnnotationReader::writtenNameOf)
                .toList();
    }

    private static List<AnnotationEvidence> annotationEvidenceOf(SingleVariableDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(ClassMetadataExtractor::annotationEvidenceOf)
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
                Optional.of(new ResolvedTypeIdentity(
                        packageName, PolicyIdentity.className(packageName, qualifiedName))));
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
