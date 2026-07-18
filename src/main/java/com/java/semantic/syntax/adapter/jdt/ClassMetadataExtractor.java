package com.java.semantic.syntax.adapter.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.syntax.domain.ClassMetadata;
import com.java.semantic.syntax.domain.ClassMetadata.FieldInfo;
import com.java.semantic.syntax.domain.ClassMetadata.MethodSignature;
import com.java.semantic.syntax.domain.ClassMetadata.SqlSource;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
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

/** 從型別宣告抽取輕量 metadata */
final class ClassMetadataExtractor {

    /** MyBatis annotation SQL，名稱與 XML 標籤一一對應 */
    private static final List<String> SQL_ANNOTATIONS = List.of("Select", "Insert", "Update", "Delete");

    private static final String PROFILE = "Profile";

    private static final String ACCESSORS = "Accessors";

    private ClassMetadataExtractor() {
    }

    static ClassMetadata extract(ParsedSource parsed, AbstractTypeDeclaration type,
            MapperXmlSqlExtractor.SqlIndex sqlIndex) {
        String packageName = PackageNames.of(parsed);
        String className = SourceTypes.nestedName(type);
        String fullyQualifiedName = PackageNames.qualify(packageName, className);

        return new ClassMetadata(
                className,
                packageName,
                fullyQualifiedName,
                parsed.source().relativePath(),
                kindOf(type),
                isAbstract(type),
                implementedTypesOf(type),
                extendedTypesOf(type),
                annotationNamesOf(type),
                importsOf(parsed.unit()),
                fieldsOf(type),
                methodsOf(parsed.unit(), type, fullyQualifiedName, sqlIndex),
                hasFluentAccessors(type),
                profilesOf(type));
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
            return simpleNamesOf(typeDeclaration.superInterfaceTypes());
        }
        return List.of();
    }

    private static List<String> extendedTypesOf(AbstractTypeDeclaration type) {
        if (type instanceof TypeDeclaration typeDeclaration && !(type instanceof RecordDeclaration)) {
            Type superclass = typeDeclaration.getSuperclassType();
            return Objects.isNull(superclass) ? List.of() : List.of(TypeNames.simpleNameOf(superclass));
        }
        return List.of();
    }

    private static List<String> simpleNamesOf(List<?> types) {
        List<String> names = new ArrayList<>();
        for (Object type : types) {
            names.add(TypeNames.simpleNameOf((Type) type));
        }
        return List.copyOf(names);
    }

    // --- 成員 ---

    private static List<FieldInfo> fieldsOf(AbstractTypeDeclaration type) {
        List<FieldInfo> fields = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            for (Object component : record.recordComponents()) {
                SingleVariableDeclaration parameter = (SingleVariableDeclaration) component;
                fields.add(new FieldInfo(
                        parameter.getName().getIdentifier(),
                        TypeNames.simpleNameOf(parameter.getType())));
            }
        }
        for (Object member : SourceTypes.declaredMembersOf(type)) {
            if (!(member instanceof FieldDeclaration field)) {
                continue;
            }
            String typeName = TypeNames.simpleNameOf(field.getType());
            for (Object variable : field.fragments()) {
                fields.add(new FieldInfo(
                        ((VariableDeclarationFragment) variable).getName().getIdentifier(), typeName));
            }
        }
        return List.copyOf(fields);
    }

    private static List<MethodSignature> methodsOf(CompilationUnit unit, AbstractTypeDeclaration type,
            String fullyQualifiedName, MapperXmlSqlExtractor.SqlIndex sqlIndex) {
        List<MethodSignature> methods = new ArrayList<>();
        for (MethodDeclaration method : SourceTypes.declaredMethodsOf(type)) {
            String name = method.getName().getIdentifier();

            // annotation SQL 優先於 XML，判準是「有沒有 @Select 之類的註解」而非解析出的字串是否為空
            Optional<Annotation> sqlAnnotation = AnnotationReader.findAny(method, SQL_ANNOTATIONS);
            String sql = sqlAnnotation
                    .map(annotation -> String.join(" ", AnnotationReader.stringValues(annotation, "value")))
                    .orElseGet(() -> sqlIndex.find(fullyQualifiedName, name).orElse(null));
            SqlSource sqlSource = sqlSourceOf(sqlAnnotation.isPresent(), sql);

            methods.add(new MethodSignature(
                    name,
                    parameterTypesOf(method),
                    annotationNamesOf(method),
                    sql,
                    sqlSource,
                    unit.getLineNumber(method.getStartPosition()),
                    unit.getLineNumber(method.getStartPosition() + method.getLength() - 1)));
        }
        return List.copyOf(methods);
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
            types.add(TypeNames.simpleNameOf(((SingleVariableDeclaration) parameter).getType()));
        }
        return List.copyOf(types);
    }

    // --- 註解衍生資訊 ---

    /** 保留原始碼寫法，@org.springframework.stereotype.Service 不會被縮短 */
    private static List<String> annotationNamesOf(BodyDeclaration declaration) {
        return AnnotationReader.annotationsOf(declaration).stream()
                .map(AnnotationReader::writtenNameOf)
                .toList();
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
