package dev.claudeclient.seed;

import dev.claudeclient.ClaudeClient;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.List;
import java.util.Map;

/**
 * Vanilla's worldgen registries, loaded the same way a server loads them (WorldLoader): from the vanilla data pack
 * inside the game jar, so the noise, biomes, carvers, features and structures are exactly a vanilla server's.
 * Servers never send these to clients, which is why they're rebuilt here. Loaded once, on first use.
 */
public final class Worldgen {
	private static volatile Worldgen instance;

	public final MultiPackResourceManager resources;
	public final RegistryAccess.Frozen registries;
	public final Map<ResourceKey<LevelStem>, LevelStem> dimensions;

	private Worldgen() {
		long t = System.currentTimeMillis();
		resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
		LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen forLoading = layers.getAccessForLoading(RegistryLayer.WORLDGEN);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(forLoading, staticTags);
		RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES);
		registries = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
		dimensions = worldgen.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
		ClaudeClient.LOG.info("Seed engine: vanilla worldgen loaded in {} ms ({} dimensions)", System.currentTimeMillis() - t, dimensions.size());
	}

	public static Worldgen get() {
		Worldgen w = instance;
		if (w == null) {
			synchronized (Worldgen.class) {
				w = instance;
				if (w == null) instance = w = new Worldgen();
			}
		}
		return w;
	}

	public static boolean loaded() {
		return instance != null;
	}
}
