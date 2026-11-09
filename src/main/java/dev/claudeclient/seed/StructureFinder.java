package dev.claudeclient.seed;

import dev.claudeclient.ClaudeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Finds structures from the seed the way a server places them: each structure set's placement rule picks candidate
 * chunks (random spread with its spacing, salt, frequency and exclusion zones, or stronghold rings), a set with several
 * structures picks one with vanilla's weighted roll, and the structure's own findValidGenerationPoint does the biome
 * and terrain checks with the generator for this seed. Jigsaw structures (villages, outposts, bastions, ancient cities,
 * trial chambers...) load their start piece from the vanilla data pack like a server does.
 */
public final class StructureFinder {
	public enum Group {
		VILLAGE("Villages", 0xFFE8A93C),
		TEMPLE("Temples & huts", 0xFFFFD233),
		OCEAN("Ocean structures", 0xFF4FC3F7),
		MANSION("Mansions & outposts", 0xFFB45CF0),
		PORTAL("Ruined portals", 0xFFFF7AC8),
		UNDERGROUND("Underground", 0xFF9AA0A6),
		NETHER("Nether", 0xFFFF5C5C),
		END("End cities", 0xFFE6DFA6),
		OTHER("Other", 0xFFC9C2B8);

		public final String label;
		public final int color;

		Group(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	/** A structure: its start chunk ({@code chunkX}, {@code chunkZ}) and the position the game reports for it. */
	public record Found(String id, String name, Group group, int x, int y, int z, boolean approximate, int chunkX, int chunkZ) {}

	private static final Map<SeedWorld, StructureFinder> CACHE = new WeakHashMap<>();
	private static StructureTemplateManager templates;
	private static boolean templatesTried;

	private final SeedWorld world;
	private final ChunkGeneratorStructureState state;
	private final Registry<Structure> registry;

	private StructureFinder(SeedWorld world) {
		this.world = world;
		this.registry = world.registries().lookupOrThrow(Registries.STRUCTURE);
		this.state = world.generator().createState(world.registries().lookupOrThrow(Registries.STRUCTURE_SET), world.randomState(), world.seed());
		this.state.ensureStructuresGenerated();
	}

	public static synchronized StructureFinder of(SeedWorld world) {
		return CACHE.computeIfAbsent(world, StructureFinder::new);
	}

	/** The template manager reads structure pieces from the vanilla data pack; it wants a world folder, so it gets a private one. */
	private static synchronized StructureTemplateManager templates() {
		if (!templatesTried) {
			templatesTried = true;
			try {
				Path dir = FabricLoader.getInstance().getConfigDir().resolve("claudeclient").resolve("seed-engine");
				LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(dir).createAccess("templates");
				templates = new StructureTemplateManager(Worldgen.get().resources, access, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
			} catch (Throwable t) {
				ClaudeClient.LOG.warn("Seed engine: no structure templates, jigsaw structures will be approximate", t);
			}
		}
		return templates;
	}

	public static Group groupOf(String path) {
		if (path.startsWith("village")) return Group.VILLAGE;
		if (path.equals("desert_pyramid") || path.equals("jungle_pyramid") || path.equals("swamp_hut") || path.equals("igloo")) return Group.TEMPLE;
		if (path.equals("monument") || path.startsWith("ocean_ruin") || path.startsWith("shipwreck") || path.equals("buried_treasure")) return Group.OCEAN;
		if (path.equals("mansion") || path.equals("pillager_outpost")) return Group.MANSION;
		if (path.equals("ruined_portal_nether") || path.equals("fortress") || path.equals("bastion_remnant") || path.equals("nether_fossil")) return Group.NETHER;
		if (path.startsWith("ruined_portal")) return Group.PORTAL;
		if (path.startsWith("mineshaft") || path.equals("stronghold") || path.equals("ancient_city") || path.equals("trial_chambers")
				|| path.equals("trail_ruins")) return Group.UNDERGROUND;
		if (path.equals("end_city")) return Group.END;
		return Group.OTHER;
	}

	public static String prettyName(String path) {
		return switch (path) {
			case "monument" -> "Ocean Monument";
			case "mansion" -> "Woodland Mansion";
			case "desert_pyramid" -> "Desert Temple";
			case "jungle_pyramid" -> "Jungle Temple";
			case "swamp_hut" -> "Witch Hut";
			case "fortress" -> "Nether Fortress";
			case "bastion_remnant" -> "Bastion";
			case "mineshaft_mesa" -> "Mineshaft (badlands)";
			case "ruined_portal" -> "Ruined Portal";
			default -> {
				StringBuilder sb = new StringBuilder();
				String[] words = path.split("_");
				for (int i = 0; i < words.length; i++) {
					if (words[i].isEmpty()) continue;
					if (sb.length() > 0) sb.append(i == 1 && words[0].matches("village|ruined|ocean|shipwreck") ? " (" : " ");
					sb.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
				}
				String s = sb.toString();
				yield s.contains("(") ? s + ")" : s;
			}
		};
	}

	/** Structures with their start within {@code radius} chunks of a chunk, nearest first. */
	public List<Found> find(int cx, int cz, int radius, Predicate<Group> wanted) {
		List<Found> out = new ArrayList<>();
		long seed = world.seed();
		for (Holder<StructureSet> set : state.possibleStructureSets()) {
			List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
			boolean any = false;
			for (StructureSet.StructureSelectionEntry e : entries) if (wanted.test(groupOf(path(e.structure())))) any = true;
			if (!any) continue;
			StructurePlacement placement = set.value().placement();
			List<ChunkPos> candidates = new ArrayList<>();
			if (placement instanceof RandomSpreadStructurePlacement rs) {
				int spacing = rs.spacing();
				for (int rx = Math.floorDiv(cx - radius, spacing); rx <= Math.floorDiv(cx + radius, spacing); rx++)
					for (int rz = Math.floorDiv(cz - radius, spacing); rz <= Math.floorDiv(cz + radius, spacing); rz++) {
						ChunkPos p = rs.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
						if (Math.abs(p.x - cx) > radius || Math.abs(p.z - cz) > radius) continue;
						if (placement.isStructureChunk(state, p.x, p.z)) candidates.add(p);
					}
			} else if (placement instanceof ConcentricRingsStructurePlacement rings) {
				for (ChunkPos p : state.getRingPositionsFor(rings)) {
					if (Math.abs(p.x - cx) <= radius && Math.abs(p.z - cz) <= radius) candidates.add(p);
				}
			}
			for (ChunkPos p : candidates) {
				Found f = pick(entries, p, seed);
				if (f != null && wanted.test(f.group())) out.add(f);
			}
		}
		double px = (cx << 4) + 8, pz = (cz << 4) + 8;
		out.sort(Comparator.comparingDouble(f -> (f.x - px) * (f.x - px) + (f.z - pz) * (f.z - pz)));
		return out;
	}

	/** ChunkGenerator.createStructures' choice for one chunk: the only entry, or vanilla's weighted roll among several. */
	private Found pick(List<StructureSet.StructureSelectionEntry> entries, ChunkPos p, long seed) {
		if (entries.size() == 1) return tryAt(entries.get(0).structure(), p);
		List<StructureSet.StructureSelectionEntry> list = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(seed, p.x, p.z);
		int total = 0;
		for (StructureSet.StructureSelectionEntry e : list) total += e.weight();
		while (!list.isEmpty()) {
			int j = random.nextInt(total);
			int k = 0;
			for (StructureSet.StructureSelectionEntry e : list) {
				j -= e.weight();
				if (j < 0) break;
				k++;
			}
			StructureSet.StructureSelectionEntry chosen = list.get(k);
			Found f = tryAt(chosen.structure(), p);
			if (f != null) return f;
			list.remove(k);
			total -= chosen.weight();
		}
		return null;
	}

	private Found tryAt(Holder<Structure> holder, ChunkPos p) {
		Structure structure = holder.value();
		String path = path(holder);
		StructureTemplateManager tm = templates();
		try {
			Structure.GenerationContext ctx = new Structure.GenerationContext(world.registries(), world.generator(), world.biomeSource(),
					world.randomState(), tm, world.seed(), p, world.heightAccessor(), structure.biomes()::contains);
			Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(ctx);
			if (stub.isEmpty()) return null;
			BlockPos pos = stub.get().position();
			return new Found(path, prettyName(path), groupOf(path), pos.getX(), pos.getY(), pos.getZ(), false, p.x, p.z);
		} catch (Throwable t) {
			// Couldn't run the structure's own check (e.g. no templates): fall back to its biome at the chunk centre.
			int x = p.getMiddleBlockX(), z = p.getMiddleBlockZ();
			int y = world.surfaceY(x, z);
			Holder<net.minecraft.world.level.biome.Biome> b = world.noiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
			if (!structure.biomes().contains(b)) return null;
			return new Found(path, prettyName(path), groupOf(path), x, y, z, true, p.x, p.z);
		}
	}

	private String path(Holder<Structure> h) {
		return h.unwrapKey().map(k -> k.identifier().getPath()).orElseGet(() -> {
			var key = registry.getKey(h.value());
			return key == null ? "structure" : key.getPath();
		});
	}
}
