package dev.claudeclient.seed;

import dev.claudeclient.ClaudeClient;
import dev.claudeclient.mixin.ChunkAccessAccessor;
import dev.claudeclient.mixin.NoiseBasedChunkGeneratorAccessor;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One world, generated from its seed with vanilla's own code: the same generator, noise router, biome source,
 * carvers and features a server uses for that seed. It mirrors the server's chunk pipeline step by step
 * (createBiomes, fillFromNoise, buildSurface, applyCarvers, applyBiomeDecoration) without needing a server.
 */
public final class SeedWorld {
	private final long seed;
	private final ResourceKey<Level> dimension;
	private final RegistryAccess registries;
	private final NoiseBasedChunkGenerator generator;
	private final BiomeSource biomeSource;
	private final RandomState randomState;
	private final DimensionType dimensionType;
	private final LevelHeightAccessor height;
	private final PalettedContainerFactory containers;
	private final Registry<Biome> biomes;
	private final List<FeatureSorter.StepFeatureData> steps;
	private final Registry<PlacedFeature> placedFeatures;
	private final WorldOptions options;
	private final long obfuscatedSeed;
	/** Features that threw (e.g. ones that need a real server); logged once each. Their own random stream is separate, so others are unaffected. */
	private final Set<String> failedFeatures = ConcurrentHashMap.newKeySet();

	private SeedWorld(long seed, ResourceKey<Level> dimension, LevelStem stem, Worldgen wg) {
		this.seed = seed;
		this.dimension = dimension;
		this.registries = wg.registries;
		this.generator = (NoiseBasedChunkGenerator) stem.generator();
		this.biomeSource = generator.getBiomeSource();
		this.dimensionType = stem.type().value();
		this.height = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
		this.randomState = RandomState.create(registries, generator.generatorSettings().unwrapKey().orElseThrow(), seed);
		this.containers = PalettedContainerFactory.create(registries);
		this.biomes = registries.lookupOrThrow(Registries.BIOME);
		this.placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE);
		// The same call ChunkGenerator makes: every feature of every biome this dimension can generate, sorted per step.
		this.steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(biomeSource.possibleBiomes()),
				holder -> holder.value().getGenerationSettings().features(), true);
		this.options = new WorldOptions(seed, false, false);
		this.obfuscatedSeed = BiomeManager.obfuscateSeed(seed);
	}

	private static final Map<String, SeedWorld> CACHE = new java.util.LinkedHashMap<>(4, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, SeedWorld> eldest) {
			return size() > 3;
		}
	};

	/** A shared world for this seed and dimension (Seed ESP, Biome Checker and the seed map all use the same one). */
	public static SeedWorld cached(long seed, ResourceKey<Level> dimension) {
		String key = seed + "|" + dimension.identifier();
		synchronized (CACHE) {
			SeedWorld w = CACHE.get(key);
			if (w != null) return w;
		}
		SeedWorld w = create(seed, dimension);
		if (w != null) synchronized (CACHE) {
			CACHE.putIfAbsent(key, w);
			return CACHE.get(key);
		}
		return null;
	}

	/** Null for dimensions the seed engine can't generate (custom ones). */
	public static SeedWorld create(long seed, ResourceKey<Level> dimension) {
		ResourceKey<LevelStem> stemKey = dimension == Level.OVERWORLD ? LevelStem.OVERWORLD
				: dimension == Level.NETHER ? LevelStem.NETHER : dimension == Level.END ? LevelStem.END : null;
		if (stemKey == null) return null;
		Worldgen wg = Worldgen.get();
		LevelStem stem = wg.dimensions.get(stemKey);
		if (stem == null || !(stem.generator() instanceof NoiseBasedChunkGenerator)) return null;
		return new SeedWorld(seed, dimension, stem, wg);
	}

	public long seed() {
		return seed;
	}

	public ResourceKey<Level> dimension() {
		return dimension;
	}

	public RegistryAccess registries() {
		return registries;
	}

	public ChunkGenerator generator() {
		return generator;
	}

	public NoiseBasedChunkGenerator noiseGenerator() {
		return generator;
	}

	public BiomeSource biomeSource() {
		return biomeSource;
	}

	public RandomState randomState() {
		return randomState;
	}

	public DimensionType dimensionType() {
		return dimensionType;
	}

	public LevelHeightAccessor heightAccessor() {
		return height;
	}

	public Registry<Biome> biomes() {
		return biomes;
	}

	/** Biome at quart (4-block) coordinates, straight from the biome source. */
	public Holder<Biome> noiseBiome(int qx, int qy, int qz) {
		return biomeSource.getNoiseBiome(qx, qy, qz, randomState.sampler());
	}

	/** The biome at a block, with vanilla's zoom smoothing (what F3 shows). */
	public Holder<Biome> biomeAt(int x, int y, int z) {
		return new BiomeManager(this::noiseBiome, obfuscatedSeed).getBiome(new BlockPos(x, y, z));
	}

	/** Surface height at a column (top of the terrain, before features like trees). */
	public int surfaceY(int x, int z) {
		return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, randomState);
	}

	/** The last decoration step that places ores in this dimension (overworld: underground ores; nether: its ores sit one step later). */
	public int oreStep() {
		return dimension == Level.NETHER ? GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal() : GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
	}

	// ---------------------------------------------------------------- terrain

	/** Biomes, noise (terrain, aquifers, ore veins), surface and carvers for one chunk: everything before features. */
	public ProtoChunk generateTerrain(int cx, int cz) {
		ProtoChunk chunk = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, height, containers, null);
		SeedRegion single = new SeedRegion(this, Map.of(chunk.getPos().toLong(), chunk), chunk, 0);
		StructureManager structures = new StructureManager(single, options, null);
		// Each step marks the chunk's status like the server does; vanilla code checks it (e.g. no biomes before BIOMES).
		chunk.setPersistedStatus(ChunkStatus.STRUCTURE_REFERENCES);
		generator.createBiomes(randomState, Blender.empty(), structures, chunk).join();
		chunk.setPersistedStatus(ChunkStatus.BIOMES);
		generator.fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
		chunk.setPersistedStatus(ChunkStatus.NOISE);

		BiomeManager biomeManager = new BiomeManager((qx, qy, qz) -> chunkBiome(chunk, qx, qy, qz), obfuscatedSeed);
		generator.buildSurface(chunk, new WorldGenerationContext(generator, height), randomState, structures, biomeManager, biomes, Blender.empty());
		chunk.setPersistedStatus(ChunkStatus.SURFACE);
		applyCarvers(chunk, structures, biomeManager);
		chunk.setPersistedStatus(ChunkStatus.CARVERS);
		// The noise cache is only needed up to here and is by far the biggest thing a chunk holds.
		((ChunkAccessAccessor) chunk).claudeclient$setNoiseChunk(null);
		return chunk;
	}

	private Holder<Biome> chunkBiome(ProtoChunk chunk, int qx, int qy, int qz) {
		ChunkPos p = chunk.getPos();
		if (QuartPos.toSection(qx) == p.x && QuartPos.toSection(qz) == p.z) return chunk.getNoiseBiome(qx, qy, qz);
		return noiseBiome(qx, qy, qz);
	}

	/** NoiseBasedChunkGenerator.applyCarvers, minus the server region it reads neighbour biomes through. */
	private void applyCarvers(ProtoChunk chunk, StructureManager structures, BiomeManager biomeManager) {
		BiomeManager carverBiomes = biomeManager.withDifferentSource((x, y, z) -> biomeSource.getNoiseBiome(x, y, z, randomState.sampler()));
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
		ChunkPos chunkPos = chunk.getPos();
		NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c ->
				((NoiseBasedChunkGeneratorAccessor) (Object) generator).claudeclient$createNoiseChunk(c, structures, Blender.empty(), randomState));
		Aquifer aquifer = noiseChunk.aquifer();
		CarvingContext context = new CarvingContext(generator, registries, chunk.getHeightAccessorForGeneration(), noiseChunk, randomState,
				generator.generatorSettings().value().surfaceRule());
		CarvingMask mask = chunk.getOrCreateCarvingMask();
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				ChunkPos from = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
				Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(from.getMinBlockX()), 0, QuartPos.fromBlock(from.getMinBlockZ()), randomState.sampler());
				int m = 0;
				for (Holder<ConfiguredWorldCarver<?>> holder : biome.value().getGenerationSettings().getCarvers()) {
					ConfiguredWorldCarver<?> carver = holder.value();
					random.setLargeFeatureSeed(seed + m, from.x, from.z);
					if (carver.isStartChunk(random)) carver.carve(context, chunk, carverBiomes::getBiome, random, aquifer, from, mask);
					m++;
				}
			}
		}
	}

	// ---------------------------------------------------------------- features

	/**
	 * ChunkGenerator.applyBiomeDecoration for {@code center}, up to and including {@code lastStep}, with vanilla's
	 * exact random seeding per feature. {@code region} must hold the chunk and its 8 neighbours (features write into
	 * neighbours, like on a server). Structures are skipped; they use their own random streams, so features still
	 * line up exactly.
	 */
	public void decorate(Map<Long, ProtoChunk> region, ProtoChunk center, int lastStep) {
		Heightmap.primeHeightmaps(center, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
		SeedRegion level = new SeedRegion(this, region, center, 1);
		ChunkPos chunkPos = center.getPos();
		SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
		BlockPos origin = sectionPos.origin();
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
		long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());

		Set<Holder<Biome>> present = new ObjectArraySet<>();
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (LevelChunkSection section : level.getChunk(chunkPos.x + dx, chunkPos.z + dz).getSections()) section.getBiomes().getAll(present::add);
		present.retainAll(biomeSource.possibleBiomes());

		int stepCount = Math.min(steps.size(), lastStep + 1);
		for (int k = 0; k < stepCount; k++) {
			IntSet indexes = new IntArraySet();
			FeatureSorter.StepFeatureData step = steps.get(k);
			for (Holder<Biome> biome : present) {
				List<HolderSet<PlacedFeature>> lists = biome.value().getGenerationSettings().features();
				if (k < lists.size()) {
					final int stepIndex = k;
					lists.get(stepIndex).stream().map(Holder::value).forEach(pf -> indexes.add(step.indexMapping().applyAsInt(pf)));
				}
			}
			int[] sorted = indexes.toIntArray();
			Arrays.sort(sorted);
			for (int index : sorted) {
				PlacedFeature feature = step.features().get(index);
				random.setFeatureSeed(decorationSeed, index, k);
				try {
					level.setCurrentlyGenerating(() -> placedFeatures.getResourceKey(feature).map(Object::toString).orElse("?"));
					feature.placeWithBiomeCheck(level, generator, random, origin);
				} catch (Throwable t) {
					String name = placedFeatures.getResourceKey(feature).map(Object::toString).orElse(feature.toString());
					if (failedFeatures.add(name)) ClaudeClient.LOG.warn("Seed engine: feature {} failed (skipped from now on in logs)", name, t);
				}
			}
		}
		level.setCurrentlyGenerating(null);
		center.setPersistedStatus(ChunkStatus.FEATURES);
	}
}
