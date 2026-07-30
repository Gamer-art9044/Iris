package art.arcane.iris.nativegen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class ForcedStructureChunkGenerator extends ChunkGenerator {
    private static final int MIN_SAFE_STRUCTURE_Y = 80;
    private static final int VERTICAL_MARGIN = 32;

    private final ChunkGenerator delegate;
    private final int targetY;

    ForcedStructureChunkGenerator(ChunkGenerator delegate, Holder<Biome> sourceBiome, int targetY) {
        super(new FixedBiomeSource(sourceBiome));
        this.delegate = delegate;
        this.targetY = targetY;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return MapCodec.unit(this);
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk) {
        delegate.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState randomState, ChunkAccess protoChunk) {
        delegate.buildSurface(level, structureManager, randomState, protoChunk);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        delegate.spawnOriginalMobs(worldGenRegion);
    }

    @Override
    public int getGenDepth() {
        return delegate.getGenDepth();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager,
                                                         ChunkAccess centerChunk) {
        return delegate.fillFromNoise(
                blender, randomState, structureManager, centerChunk);
    }

    @Override
    public int getSeaLevel() {
        return delegate.getMinY() + 1;
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor heightAccessor, RandomState randomState) {
        return safeOccupiedY(heightAccessor) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor,
                                     RandomState randomState) {
        int minY = heightAccessor.getMinY();
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        for (int index = 0; index < states.length; index++) {
            int y = minY + index;
            states[index] = (y & 1) == 0
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState,
                                   BlockPos feetPos) {
        delegate.addDebugScreenInfo(result, randomState, feetPos);
    }

    private int safeOccupiedY(LevelHeightAccessor heightAccessor) {
        int minY = heightAccessor.getMinY() + VERTICAL_MARGIN;
        int maxY = heightAccessor.getMaxY() - VERTICAL_MARGIN;
        if (minY > maxY) {
            return heightAccessor.getMinY()
                    + Math.max(0, heightAccessor.getHeight() / 2);
        }
        return Math.max(minY, Math.min(maxY, Math.max(MIN_SAFE_STRUCTURE_Y, targetY)));
    }
}
