/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.modded.structure;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.structure.authoring.IrisStructureBundleFactory;
import art.arcane.iris.core.structure.authoring.StructureCapability;
import art.arcane.iris.core.structure.authoring.StructureKey;
import art.arcane.iris.core.structure.authoring.StructureLoss;
import art.arcane.iris.core.structure.authoring.StructureResourceBundle;
import art.arcane.iris.core.structure.authoring.StructureSource;
import art.arcane.iris.core.structure.authoring.StructureTransactionWriter;
import art.arcane.iris.core.structure.authoring.StructureWriteMode;
import art.arcane.iris.core.structure.authoring.StructureWriteOptions;
import art.arcane.iris.core.structure.authoring.StructureWriteResult;
import art.arcane.iris.engine.framework.structure.StructureResourceBundleGraphCompiler;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ModdedStructureImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris");

    private final Supplier<MinecraftServer> server;

    public ModdedStructureImportService(Supplier<MinecraftServer> server) {
        this.server = Objects.requireNonNull(server);
    }

    public List<StructureKey> templateKeys() throws StructureImportException {
        try {
            MinecraftServer activeServer = requireServerThread();
            return activeServer.getStructureManager().listTemplates()
                    .map((Identifier identifier) -> StructureKey.parse(identifier.toString()))
                    .sorted()
                    .toList();
        } catch (RuntimeException failure) {
            throw report("Failed to list native structure templates", failure);
        }
    }

    public List<StructureKey> jigsawStructureKeys() throws StructureImportException {
        try {
            MinecraftServer activeServer = requireServerThread();
            Registry<Structure> registry = activeServer.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            List<StructureKey> keys = new ArrayList<>();
            for (Identifier identifier : registry.keySet()) {
                if (registry.getValue(identifier) instanceof JigsawStructure) {
                    keys.add(StructureKey.parse(identifier.toString()));
                }
            }
            keys.sort(Comparator.naturalOrder());
            return List.copyOf(keys);
        } catch (RuntimeException failure) {
            throw report("Failed to list registered jigsaw structures", failure);
        }
    }

    public PreparedImport prepareTemplate(TemplateImportOptions options) throws StructureImportException {
        Objects.requireNonNull(options);
        try {
            MinecraftServer activeServer = requireServerThread();
            Identifier sourceIdentifier = identifier(options.sourceKey());
            StructureTemplate template = activeServer.getStructureManager().get(sourceIdentifier)
                    .orElseThrow(() -> new IllegalArgumentException("No structure template exists for " + options.sourceKey()));
            ModdedStructureTemplateCapture.Capture capture = ModdedStructureTemplateCapture.capture(
                    options.sourceKey(),
                    template,
                    activeServer.registryAccess().lookupOrThrow(Registries.BLOCK),
                    false
            );
            StructureSource source = StructureSource.identified(
                    options.sourceKind(),
                    options.sourceKey(),
                    SharedConstants.getCurrentVersion().name(),
                    NbtUtils.structureToSnbt(capture.sourceTag()).getBytes(StandardCharsets.UTF_8)
            );
            IrisStructureBundleFactory.SinglePieceOptions bundleOptions = new IrisStructureBundleFactory.SinglePieceOptions(
                    options.targetKey(),
                    source,
                    options.targetKey().path(),
                    capture.object(),
                    Math.max(capture.width(), capture.depth()),
                    options.placeMode(),
                    options.objectOnly(),
                    capture.capabilities(),
                    capture.losses()
            );
            StructureResourceBundle bundle = IrisStructureBundleFactory.singlePiece(bundleOptions);
            if (!options.objectOnly()) {
                StructureResourceBundleGraphCompiler.requireViable(bundle);
            }
            return new PreparedImport(
                    options.packRoot(),
                    options.writeOptions(),
                    ImportKind.TEMPLATE,
                    bundle,
                    capture.blocks(),
                    1,
                    options.objectOnly() ? 0 : 1
            );
        } catch (IOException | RuntimeException failure) {
            throw report("Failed to prepare structure template import " + options.sourceKey(), failure);
        }
    }

    public PreparedImport prepareJigsawStructure(JigsawImportOptions options) throws StructureImportException {
        Objects.requireNonNull(options);
        try {
            MinecraftServer activeServer = requireServerThread();
            ModdedJigsawStructureCapture.Capture capture = ModdedJigsawStructureCapture.capture(
                    activeServer,
                    options.sourceKey(),
                    options.targetKey(),
                    options.sourceKind()
            );
            StructureResourceBundleGraphCompiler.requireViable(capture.bundle());
            return new PreparedImport(
                    options.packRoot(),
                    options.writeOptions(),
                    ImportKind.JIGSAW_STRUCTURE,
                    capture.bundle(),
                    capture.blocks(),
                    capture.pieces(),
                    capture.pools()
            );
        } catch (ModdedJigsawStructureCapture.UnsupportedStructureTypeException failure) {
            StructureLoss loss = StructureLoss.error(
                    StructureCapability.IRIS_PLACEMENT,
                    "unsupported_native_structure_type",
                    failure.getMessage()
            );
            throw report("Cannot import native structure graph " + options.sourceKey(), failure, List.of(loss));
        } catch (IOException | RuntimeException failure) {
            throw report("Failed to prepare jigsaw structure import " + options.sourceKey(), failure);
        }
    }

    public ImportResult write(PreparedImport prepared) {
        Objects.requireNonNull(prepared);
        StructureWriteResult writeResult = new StructureTransactionWriter(prepared.packRoot())
                .write(prepared.bundle(), prepared.writeOptions());
        writeResult.failure().ifPresent((Throwable failure) -> LOGGER.error(
                "Failed to commit modded structure import {} to {}",
                prepared.bundle().key(),
                prepared.packRoot(),
                failure
        ));
        if (writeResult.committed()) {
            IrisData.getLoaded(prepared.packRoot().toFile()).ifPresent(IrisData::invalidateStructureResources);
        }
        String message = writeMessage(prepared, writeResult);
        return new ImportResult(
                writeResult.successful(),
                message,
                prepared.kind(),
                prepared.bundle().key(),
                prepared.blocks(),
                prepared.pieces(),
                prepared.pools(),
                prepared.bundle().capabilities(),
                prepared.bundle().losses(),
                Optional.of(writeResult)
        );
    }

    public ImportResult importTemplate(TemplateImportOptions options) {
        try {
            return write(prepareTemplate(options));
        } catch (StructureImportException failure) {
            return failed(options.targetKey(), ImportKind.TEMPLATE, failure.getMessage(), failure.losses());
        }
    }

    public ImportResult importJigsawStructure(JigsawImportOptions options) {
        try {
            return write(prepareJigsawStructure(options));
        } catch (StructureImportException failure) {
            return failed(options.targetKey(), ImportKind.JIGSAW_STRUCTURE, failure.getMessage(), failure.losses());
        }
    }

    private MinecraftServer requireServerThread() {
        MinecraftServer activeServer = server.get();
        if (activeServer == null) {
            throw new IllegalStateException("Minecraft server is not available");
        }
        if (!activeServer.isSameThread()) {
            throw new IllegalStateException("Structure registry capture must run on the logical server thread");
        }
        return activeServer;
    }

    private StructureImportException report(String context, Exception failure) {
        return report(context, failure, List.of());
    }

    private StructureImportException report(String context, Exception failure, List<StructureLoss> losses) {
        LOGGER.error(context, failure);
        return new StructureImportException(context + ": " + failureDetail(failure), failure, losses);
    }

    private static ImportResult failed(
            StructureKey targetKey,
            ImportKind kind,
            String message,
            List<StructureLoss> losses
    ) {
        return new ImportResult(
                false,
                message,
                kind,
                targetKey,
                0,
                0,
                0,
                Set.of(),
                losses,
                Optional.empty()
        );
    }

    private static String writeMessage(PreparedImport prepared, StructureWriteResult result) {
        if (result.successful()) {
            return switch (result.status()) {
                case DRY_RUN -> "Validated import of '" + prepared.bundle().key() + "' without writing files";
                case ADDED -> "Imported '" + prepared.bundle().key() + "'";
                case OVERWRITTEN -> "Overwrote owned import '" + prepared.bundle().key() + "'";
                case UNCHANGED -> "Import '" + prepared.bundle().key() + "' is already current";
                case COMMITTED_CLEANUP_REQUIRED -> "Imported '" + prepared.bundle().key()
                        + "'; obsolete staging cleanup is still required";
                default -> "Imported '" + prepared.bundle().key() + "'";
            };
        }
        if (!result.conflicts().isEmpty()) {
            StructureWriteResult.Conflict conflict = result.conflicts().getFirst();
            return "Import conflict for '" + prepared.bundle().key() + "': " + conflict.relativePath()
                    + " is " + conflict.reason().name().toLowerCase() + "; existing files were preserved";
        }
        return "Import failed for '" + prepared.bundle().key() + "': "
                + result.failure().map(ModdedStructureImportService::failureDetail).orElse(result.status().name());
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static Identifier identifier(StructureKey key) {
        return Identifier.fromNamespaceAndPath(key.namespace(), key.path());
    }

    public enum ImportKind {
        TEMPLATE,
        JIGSAW_STRUCTURE
    }

    public record TemplateImportOptions(
            Path packRoot,
            StructureKey sourceKey,
            StructureKey targetKey,
            StructureSource.Kind sourceKind,
            StructureWriteOptions writeOptions,
            boolean objectOnly,
            String placeMode
    ) {
        public TemplateImportOptions {
            packRoot = normalizedRoot(packRoot);
            Objects.requireNonNull(sourceKey);
            Objects.requireNonNull(targetKey);
            Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(writeOptions);
            Objects.requireNonNull(placeMode);
            if (placeMode.isBlank()) {
                throw new IllegalArgumentException("Structure place mode cannot be blank");
            }
        }

        public static TemplateImportOptions create(
                Path packRoot,
                StructureKey sourceKey,
                StructureKey targetKey,
                StructureWriteMode mode
        ) {
            return new TemplateImportOptions(
                    packRoot,
                    sourceKey,
                    targetKey,
                    inferredSourceKind(sourceKey),
                    new StructureWriteOptions(mode, false),
                    false,
                    "CENTER_HEIGHT"
            );
        }
    }

    public record JigsawImportOptions(
            Path packRoot,
            StructureKey sourceKey,
            StructureKey targetKey,
            StructureSource.Kind sourceKind,
            StructureWriteOptions writeOptions
    ) {
        public JigsawImportOptions {
            packRoot = normalizedRoot(packRoot);
            Objects.requireNonNull(sourceKey);
            Objects.requireNonNull(targetKey);
            Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(writeOptions);
        }

        public static JigsawImportOptions create(
                Path packRoot,
                StructureKey sourceKey,
                StructureKey targetKey,
                StructureWriteMode mode
        ) {
            return new JigsawImportOptions(
                    packRoot,
                    sourceKey,
                    targetKey,
                    inferredSourceKind(sourceKey),
                    new StructureWriteOptions(mode, false)
            );
        }
    }

    public record PreparedImport(
            Path packRoot,
            StructureWriteOptions writeOptions,
            ImportKind kind,
            StructureResourceBundle bundle,
            int blocks,
            int pieces,
            int pools
    ) {
        public PreparedImport {
            packRoot = normalizedRoot(packRoot);
            Objects.requireNonNull(writeOptions);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(bundle);
            if (blocks < 0 || pieces < 0 || pools < 0) {
                throw new IllegalArgumentException("Prepared import counts cannot be negative");
            }
        }
    }

    public record ImportResult(
            boolean success,
            String message,
            ImportKind kind,
            StructureKey targetKey,
            int blocks,
            int pieces,
            int pools,
            Set<StructureCapability> capabilities,
            List<StructureLoss> losses,
            Optional<StructureWriteResult> writeResult
    ) {
        public ImportResult {
            Objects.requireNonNull(message);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(targetKey);
            capabilities = Set.copyOf(capabilities);
            losses = List.copyOf(losses);
            Objects.requireNonNull(writeResult);
        }
    }

    public static final class StructureImportException extends Exception {
        private final List<StructureLoss> losses;

        public StructureImportException(String message, Throwable cause, List<StructureLoss> losses) {
            super(message, cause);
            this.losses = List.copyOf(losses);
        }

        public List<StructureLoss> losses() {
            return losses;
        }
    }

    private static Path normalizedRoot(Path path) {
        return Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    private static StructureSource.Kind inferredSourceKind(StructureKey sourceKey) {
        return sourceKey.namespace().equals("minecraft")
                ? StructureSource.Kind.VANILLA : StructureSource.Kind.DATAPACK;
    }
}
