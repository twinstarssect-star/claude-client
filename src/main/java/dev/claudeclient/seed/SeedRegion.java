package dev.claudeclient.seed;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.WorldGenTickAccess;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A server-free copy of vanilla's WorldGenRegion: the world as a feature sees it while one chunk decorates (that chunk
 * plus its 8 neighbours). It lets vanilla's own feature code (ores, geodes, lakes, dungeons...) run unmodified on
 * chunks generated from the seed, so the results are exactly what a server would have generated.
 */
public final class SeedRegion implements WorldGenLevel {
	private static final Identifier WORLDGEN_REGION_RANDOM = Identifier.withDefaultNamespace("worldgen_region_random");

	private final SeedWorld world;
	private final Map<Long, ? extends ChunkAccess> chunks;
	private final ChunkAccess center;
	private final ChunkPos centerPos;
	private final int writeRadius;
	private final RandomSource random;
	private final BiomeManager biomeManager;
	private final WorldGenTickAccess<Block> blockTicks = new WorldGenTickAccess<>(pos -> getChunk(pos).getBlockTicks());
	private final WorldGenTickAccess<Fluid> fluidTicks = new WorldGenTickAccess<>(pos -> getChunk(pos).getFluidTicks());
	private final AtomicLong subTicks = new AtomicLong();
	private final LevelData levelData = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
	private final WorldBorder border = new WorldBorder();
	private final LevelLightEngine light;
	private @Nullable Supplier<String> currentlyGenerating;

	public SeedRegion(SeedWorld world, Map<Long, ? extends ChunkAccess> chunks, ChunkAccess center, int writeRadius) {
		this.world = world;
		this.chunks = chunks;
		this.center = center;
		this.centerPos = center.getPos();
		this.writeRadius = writeRadius;
		this.random = world.randomState().getOrCreateRandomFactory(WORLDGEN_REGION_RANDOM).at(centerPos.getWorldPosition());
		this.biomeManager = new BiomeManager(this, BiomeManager.obfuscateSeed(world.seed()));
		SeedRegion self = this;
		this.light = new LevelLightEngine(new LightChunkGetter() {
			@Override
			public @Nullable LightChunk getChunkForLighting(int x, int z) {
				return null;
			}

			@Override
			public BlockGetter getLevel() {
				return self;
			}
		}, false, false);
	}

	public ChunkPos getCenter() {
		return centerPos;
	}

	public @Nullable Supplier<String> currentlyGenerating() {
		return currentlyGenerating;
	}

	@Override
	public void setCurrentlyGenerating(@Nullable Supplier<String> supplier) {
		currentlyGenerating = supplier;
	}

	@Override
	public ChunkAccess getChunk(int x, int z) {
		return getChunk(x, z, ChunkStatus.EMPTY, true);
	}

	@Override
	public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
		ChunkAccess c = chunks.get(ChunkPos.asLong(x, z));
		if (c == null && required) throw new IllegalStateException("Seed engine: chunk " + x + "," + z + " is outside the region around " + centerPos);
		return c;
	}

	@Override
	public boolean hasChunk(int x, int z) {
		return chunks.containsKey(ChunkPos.asLong(x, z));
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return getChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())).getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getChunk(pos).getFluidState(pos);
	}

	@Override
	public @Nullable Player getNearestPlayer(double x, double y, double z, double d, @Nullable Predicate<Entity> predicate) {
		return null;
	}

	@Override
	public int getSkyDarken() {
		return 0;
	}

	@Override
	public BiomeManager getBiomeManager() {
		return biomeManager;
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return world.noiseBiome(x, y, z);
	}

	@Override
	public float getShade(Direction direction, boolean shade) {
		return 1.0F;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return light;
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursion) {
		if (getBlockState(pos).isAir()) return false;
		return setBlock(pos, Blocks.AIR.defaultBlockState(), 3, recursion);
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		ChunkAccess chunk = getChunk(pos);
		BlockEntity be = chunk.getBlockEntity(pos);
		if (be != null) return be;
		CompoundTag tag = chunk.getBlockEntityNbt(pos);
		BlockState state = chunk.getBlockState(pos);
		if (tag != null) {
			if ("DUMMY".equals(tag.getStringOr("id", ""))) {
				if (!state.hasBlockEntity()) return null;
				be = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
			} else {
				be = BlockEntity.loadStatic(pos, state, tag, registryAccess());
			}
			if (be != null) {
				chunk.setBlockEntity(be);
				return be;
			}
		}
		return null;
	}

	@Override
	public boolean ensureCanWrite(BlockPos pos) {
		int x = SectionPos.blockToSectionCoord(pos.getX()), z = SectionPos.blockToSectionCoord(pos.getZ());
		return Math.abs(centerPos.x - x) <= writeRadius && Math.abs(centerPos.z - z) <= writeRadius && hasChunk(x, z);
	}

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, @Block.UpdateFlags int flags, int recursion) {
		if (!ensureCanWrite(pos)) return false;
		ChunkAccess chunk = getChunk(pos);
		BlockState old = chunk.setBlockState(pos, state, flags);
		if (state.hasBlockEntity()) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("x", pos.getX());
			tag.putInt("y", pos.getY());
			tag.putInt("z", pos.getZ());
			tag.putString("id", "DUMMY");
			chunk.setBlockEntityNbt(tag);
		} else if (old != null && old.hasBlockEntity()) {
			chunk.removeBlockEntity(pos);
		}
		if (state.hasPostProcess(this, pos) && (flags & 16) == 0) chunk.markPosForPostprocessing(pos);
		return true;
	}

	@Override
	public boolean addFreshEntity(Entity entity) {
		return true;
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean moved) {
		return setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
	}

	@Override
	public WorldBorder getWorldBorder() {
		return border;
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	@Deprecated
	@Override
	public ServerLevel getLevel() {
		return null;
	}

	@Override
	public RegistryAccess registryAccess() {
		return world.registries();
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return FeatureFlags.DEFAULT_FLAGS;
	}

	@Override
	public LevelData getLevelData() {
		return levelData;
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
		return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0f);
	}

	@Override
	public @Nullable MinecraftServer getServer() {
		return null;
	}

	@Override
	public ChunkSource getChunkSource() {
		return null;
	}

	@Override
	public long getSeed() {
		return world.seed();
	}

	@Override
	public LevelTickAccess<Block> getBlockTicks() {
		return blockTicks;
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return fluidTicks;
	}

	@Override
	public int getSeaLevel() {
		return world.generator().getSeaLevel();
	}

	@Override
	public RandomSource getRandom() {
		return random;
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		return getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
	}

	@Override
	public void playSound(@Nullable Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
	}

	@Override
	public void levelEvent(@Nullable Entity entity, int type, BlockPos pos, int data) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
	}

	@Override
	public DimensionType dimensionType() {
		return world.dimensionType();
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(pos));
	}

	@Override
	public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
		return Collections.emptyList();
	}

	@Override
	public List<Entity> getEntities(@Nullable Entity except, AABB box, @Nullable Predicate<? super Entity> predicate) {
		return Collections.emptyList();
	}

	@Override
	public List<Player> players() {
		return Collections.emptyList();
	}

	@Override
	public int getMinY() {
		return world.dimensionType().minY();
	}

	@Override
	public int getHeight() {
		return world.dimensionType().height();
	}

	@Override
	public long nextSubTickCount() {
		return subTicks.getAndIncrement();
	}

	@Override
	public EnvironmentAttributeReader environmentAttributes() {
		return EnvironmentAttributeReader.EMPTY;
	}

	ChunkAccess center() {
		return center;
	}
}
