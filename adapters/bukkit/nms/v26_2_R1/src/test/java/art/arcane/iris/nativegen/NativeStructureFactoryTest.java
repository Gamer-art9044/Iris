package art.arcane.iris.nativegen;

import art.arcane.iris.engine.object.IrisJigsawConfiguration;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutStructure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class NativeStructureFactoryTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void genericSourceDoesNotRequireJigsawType() {
        Structure source = new SwampHutStructure(
                new Structure.StructureSettings(HolderSet.empty()));

        Structure configured = NativeStructureFactory.configure(
                null, source, null, false, 80);

        assertSame(source, configured);
    }

    @Test
    public void jigsawOptionsFailClosedForNonJigsawSource() {
        Structure source = new SwampHutStructure(
                new Structure.StructureSettings(HolderSet.empty()));

        assertThrows(IllegalStateException.class, () -> NativeStructureFactory.configure(
                null, source, new IrisJigsawConfiguration(), false, 80));
    }

    @Test
    public void forcedGeneratorSuppliesDeterministicPermissiveEnvironment() {
        ChunkGenerator delegate = new TestChunkGenerator();
        Holder<Biome> biome = Holder.direct((Biome) null);
        LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
        ForcedStructureChunkGenerator generator = new ForcedStructureChunkGenerator(
                delegate, biome, -20);

        assertEquals(-63, generator.getSeaLevel());
        assertEquals(81, generator.getBaseHeight(
                0, 0, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, null));
        NoiseColumn column = generator.getBaseColumn(
                0, 0, heightAccessor, null);
        assertSame(Blocks.STONE, column.getBlock(80).getBlock());
        assertSame(Blocks.AIR, column.getBlock(81).getBlock());
    }

    @Test
    public void undergroundRelocationMovesGenericPiecesAndReturnsFreshStart() {
        Structure source = new SwampHutStructure(
                new Structure.StructureSettings(HolderSet.empty()));
        SwampHutPiece piece = new SwampHutPiece(RandomSource.create(17L), 0, 0);
        StructureStart start = new StructureStart(
                source,
                new ChunkPos(0, 0),
                0,
                new PiecesContainer(List.of(piece))
        );

        StructureStart relocated = NativeStructureVerticalPlacer.relocateToMinY(
                start, source, -20, LevelHeightAccessor.create(-64, 384));

        assertNotSame(start, relocated);
        assertEquals(-20, relocated.getPieces().getFirst().getBoundingBox().minY());
        assertEquals(-20, relocated.getBoundingBox().minY());
    }

    private static final class TestChunkGenerator extends ChunkGenerator {
        private TestChunkGenerator() {
            super(new FixedBiomeSource(Holder.direct((Biome) null)));
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            return MapCodec.unit(this);
        }

        @Override
        public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                 BiomeManager biomeManager, StructureManager structureManager,
                                 ChunkAccess chunk) {
        }

        @Override
        public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                                 RandomState randomState, ChunkAccess protoChunk) {
        }

        @Override
        public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        }

        @Override
        public int getGenDepth() {
            return 384;
        }

        @Override
        public CompletableFuture<ChunkAccess> fillFromNoise(
                Blender blender, RandomState randomState,
                StructureManager structureManager, ChunkAccess centerChunk) {
            return CompletableFuture.completedFuture(centerChunk);
        }

        @Override
        public int getSeaLevel() {
            return 63;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getBaseHeight(int x, int z, Heightmap.Types type,
                                 LevelHeightAccessor heightAccessor, RandomState randomState) {
            return 64;
        }

        @Override
        public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor,
                                         RandomState randomState) {
            BlockState[] states = new BlockState[heightAccessor.getHeight()];
            for (int index = 0; index < states.length; index++) {
                states[index] = Blocks.STONE.defaultBlockState();
            }
            return new NoiseColumn(heightAccessor.getMinY(), states);
        }

        @Override
        public void addDebugScreenInfo(List<String> result, RandomState randomState,
                                       BlockPos feetPos) {
        }
    }
}
