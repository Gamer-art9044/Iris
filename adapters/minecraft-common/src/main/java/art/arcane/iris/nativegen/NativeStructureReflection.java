package art.arcane.iris.nativegen;

import com.mojang.datafixers.util.Either;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.ScatteredFeaturePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class NativeStructureReflection {
    private NativeStructureReflection() {
    }

    static Field resolveScatteredHeightPositionField() {
        Field resolved = null;
        for (Field field : ScatteredFeaturePiece.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || field.getType() != int.class
                    || !Modifier.isProtected(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("ScatteredFeaturePiece has multiple mutable protected int fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("ScatteredFeaturePiece height-position field is missing");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("ScatteredFeaturePiece height-position field is inaccessible");
        }
        return resolved;
    }

    private static Field resolveMonumentChildPiecesField() {
        Field resolved = null;
        for (Field field : OceanMonumentPieces.MonumentBuilding.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != List.class) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("Ocean Monument has multiple instance List fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("Ocean Monument child-pieces List field is missing");
        }
        if (!Modifier.isPrivate(resolved.getModifiers()) || !Modifier.isFinal(resolved.getModifiers())) {
            throw new IllegalStateException("Ocean Monument child-pieces field has an unexpected access contract");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("Ocean Monument child-pieces field is inaccessible");
        }
        return resolved;
    }

    static Field resolveSinglePoolTemplateField() {
        Field resolved = null;
        for (Field field : SinglePoolElement.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != Either.class) {
                continue;
            }
            if (resolved != null) {
                throw new IllegalStateException("SinglePoolElement has multiple instance Either fields");
            }
            resolved = field;
        }
        if (resolved == null) {
            throw new IllegalStateException("SinglePoolElement template Either field is missing");
        }
        if (!Modifier.isProtected(resolved.getModifiers()) || !Modifier.isFinal(resolved.getModifiers())) {
            throw new IllegalStateException("SinglePoolElement template field has an unexpected access contract");
        }
        if (!resolved.trySetAccessible()) {
            throw new IllegalStateException("SinglePoolElement template field is inaccessible");
        }
        return resolved;
    }

    static StructureTemplate resolveTemplate(SinglePoolElement element,
                                             Supplier<StructureTemplateManager> templates) {
        Object value;
        try {
            value = SinglePoolTemplateAccess.FIELD.get(element);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot read native structure pool template", error);
        }
        if (!(value instanceof Either<?, ?> reference)) {
            throw new IllegalStateException("Native structure pool template field is not an Either");
        }
        return resolveTemplateReference(reference, templates);
    }

    static StructureTemplate resolveTemplateReference(Either<?, ?> reference,
                                                       Supplier<StructureTemplateManager> templates) {
        return reference.map(
                location -> resolveNamedTemplate(location, templates),
                NativeStructureReflection::requireRuntimeTemplate);
    }

    private static StructureTemplate resolveNamedTemplate(Object value,
                                                          Supplier<StructureTemplateManager> templates) {
        if (!(value instanceof Identifier identifier)) {
            throw new IllegalStateException("Native structure pool template identifier is "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return Objects.requireNonNull(templates == null ? null : templates.get(),
                "Native structure template manager is unavailable").getOrCreate(identifier);
    }

    private static StructureTemplate requireRuntimeTemplate(Object value) {
        if (!(value instanceof StructureTemplate template)) {
            throw new IllegalStateException("Native runtime structure pool template is "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        return template;
    }

    static final class MonumentChildPiecesAccess {
        static final Field FIELD = resolveMonumentChildPiecesField();

        private MonumentChildPiecesAccess() {
        }
    }

    static final class ScatteredHeightPositionAccess {
        static final Field FIELD = resolveScatteredHeightPositionField();

        private ScatteredHeightPositionAccess() {
        }
    }

    private static final class SinglePoolTemplateAccess {
        private static final Field FIELD = resolveSinglePoolTemplateField();

        private SinglePoolTemplateAccess() {
        }
    }
}
