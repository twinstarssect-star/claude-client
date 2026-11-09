package dev.claudeclient.module.seed;

import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.gui.SeedMapScreen;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.seed.BiomeInfo;
import dev.claudeclient.seed.SeedStore;
import dev.claudeclient.seed.SeedWorld;
import dev.claudeclient.seed.StructureFinder;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.ModeSetting;
import dev.claudeclient.setting.NumberSetting;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Biomes, structures and more from the world seed, with vanilla's own biome source and structure rules: the exact
 * biome you're in and the trees it grows, the nearest villages / temples / monuments / mansions / outposts / portals /
 * mineshafts / strongholds / ancient cities / trial chambers / fortresses / bastions / end cities with coordinates,
 * nearest biome of any type, and slime chunks. /claude map opens a seed map.
 */
public final class BiomeChecker extends Module {
	private static BiomeChecker instance;

	private final BoolSetting hudBiome = add(new BoolSetting("HUD Biome", "Show the biome you're in (from the seed) on the HUD.", true));
	private final BoolSetting hudTrees = add(new BoolSetting("HUD Trees", "Show which trees that biome grows.", true));
	private final BoolSetting hudSlime = add(new BoolSetting("HUD Slime Chunk", "Show whether you're standing in a slime chunk.", true));
	private final BoolSetting markers = add(new BoolSetting("World Markers", "A beam and label on each found structure, through walls.", true));
	private final NumberSetting searchRadius = add(new NumberSetting("Search Radius", "How far structures are searched for.", 1536, 256, 8192, 128, " blocks"));
	private final Map<StructureFinder.Group, BoolSetting> groups = new EnumMap<>(StructureFinder.Group.class);
	private final ModeSetting findBiome = add(new ModeSetting("Find Biome", "Which biome the Find button looks for. Left/right click to change.",
			BiomeInfo.SEARCHABLE.keySet().iterator().next(), BiomeInfo.SEARCHABLE.keySet().toArray(new String[0])));

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Claude Biome Checker");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private SeedWorld world;
	private CompletableFuture<SeedWorld> building;
	private long builtSeed;
	private ResourceKey<Level> builtDim;
	private volatile List<StructureFinder.Found> found = List.of();
	private CompletableFuture<?> searching;
	private int lastCx = Integer.MIN_VALUE, lastCz, lastRadius;
	private String lastGroups = "";
	private volatile String biomeResult;
	private volatile String biomeResultCoords;
	private String hudBiomeName, hudTreeText;
	private boolean slime;
	private int hudTimer;

	public BiomeChecker() {
		super("Seed Biome Checker", "Biomes, trees, structures and slime chunks from the world seed: what biome you're in and its "
				+ "trees, the nearest villages, temples, monuments, strongholds, ancient cities and more, and any biome. "
				+ "Needs the world seed (/claude seed set <seed>).", Category.SEED, false);
		instance = this;
		for (StructureFinder.Group g : StructureFinder.Group.values()) {
			if (g == StructureFinder.Group.OTHER) continue;
			groups.put(g, add(new BoolSetting(g.label, "Find " + g.label.toLowerCase() + ".", g != StructureFinder.Group.UNDERGROUND)));
		}
	}

	public static BiomeChecker get() {
		return instance;
	}

	public boolean wants(StructureFinder.Group g) {
		BoolSetting s = groups.get(g);
		return s != null && s.get();
	}

	/** The seed world for where you are, building it in the background if needed. Null until ready. */
	public SeedWorld world() {
		return world;
	}

	@Override
	protected void onDisable() {
		found = List.of();
		lastCx = Integer.MIN_VALUE;
	}

	@Override
	public void onWorldChange() {
		world = null;
		building = null;
		found = List.of();
		lastCx = Integer.MIN_VALUE;
		biomeResult = null;
	}

	@Override
	public void onTick() {
		OptionalLong seed = SeedStore.current();
		if (seed.isEmpty()) {
			world = null;
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		if (world != null && (world.seed() != seed.getAsLong() || world.dimension() != dim)) {
			world = null;
			found = List.of();
			lastCx = Integer.MIN_VALUE;
		}
		if (world == null) {
			if (building == null || builtSeed != seed.getAsLong() || builtDim != dim) {
				builtSeed = seed.getAsLong();
				builtDim = dim;
				long s = builtSeed;
				building = CompletableFuture.supplyAsync(() -> SeedWorld.cached(s, dim), worker);
			}
			if (!building.isDone()) return;
			try {
				world = building.join();
			} catch (Throwable t) {
				building = null;
				return;
			}
			if (world == null) return;
		}
		if (--hudTimer <= 0) {
			hudTimer = 10;
			int x = mc.player.getBlockX(), y = mc.player.getBlockY(), z = mc.player.getBlockZ();
			Holder<Biome> b = world.biomeAt(x, y, z);
			hudBiomeName = BiomeInfo.name(b);
			hudTreeText = BiomeInfo.trees(b);
			ChunkPos c = mc.player.chunkPosition();
			slime = world.dimension() == Level.OVERWORLD && WorldgenRandom.seedSlimeChunk(c.x, c.z, world.seed(), 987234911L).nextInt(10) == 0;
		}
		ChunkPos c = mc.player.chunkPosition();
		int radius = searchRadius.getInt() >> 4;
		StringBuilder g = new StringBuilder();
		for (Map.Entry<StructureFinder.Group, BoolSetting> e : groups.entrySet()) g.append(e.getValue().get() ? '1' : '0');
		boolean moved = lastCx == Integer.MIN_VALUE || Math.abs(c.x - lastCx) > 4 || Math.abs(c.z - lastCz) > 4;
		if ((moved || radius != lastRadius || !g.toString().equals(lastGroups)) && (searching == null || searching.isDone())) {
			lastCx = c.x;
			lastCz = c.z;
			lastRadius = radius;
			lastGroups = g.toString();
			SeedWorld w = world;
			int cx = c.x, cz = c.z;
			searching = CompletableFuture.runAsync(() -> found = StructureFinder.of(w).find(cx, cz, radius, this::wants), worker);
		}
	}

	@Override
	public List<HudLine> hudLines() {
		List<HudLine> out = new ArrayList<>();
		if (world == null) return out;
		if (hudBiome.get() && hudBiomeName != null) out.add(new HudLine("Biome", hudBiomeName));
		if (hudTrees.get() && hudTreeText != null && !hudTreeText.equals("None")) out.add(new HudLine("Trees", hudTreeText));
		if (hudSlime.get() && slime) out.add(new HudLine("Slime chunk", "yes"));
		return out;
	}

	@Override
	public void onRender3D(Render3D r) {
		if (!markers.get() || world == null) return;
		double range = searchRadius.get() + 64;
		for (StructureFinder.Found f : found) {
			double d = r.distance(f.x() + 0.5, mc.player.getY(), f.z() + 0.5);
			if (d > range) continue;
			int col = f.group().color;
			double y0 = Math.min(f.y(), mc.player.getY() - 40), y1 = Math.max(f.y(), mc.player.getY()) + 60;
			r.box(f.x() + 0.3, y0, f.z() + 0.3, f.x() + 0.7, y1, f.z() + 0.7, Render3D.alpha(col, 70), Render3D.alpha(col, 200));
			r.text(f.x() + 0.5, mc.player.getY() + 4, f.z() + 0.5, new String[]{f.name(), Math.round(d) + "m  (" + f.x() + ", " + f.z() + ")"},
					new int[]{col, Theme.SUB}, 1.2f);
		}
	}

	@Override
	public String hudInfo() {
		return world == null ? null : Integer.toString(found.size());
	}

	private void searchBiome() {
		SeedWorld w = world;
		if (w == null) return;
		String pretty = findBiome.get();
		String id = BiomeInfo.SEARCHABLE.get(pretty);
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
		biomeResult = "Searching for " + pretty + "...";
		biomeResultCoords = null;
		CompletableFuture.runAsync(() -> {
			int[] ys = BiomeInfo.isCave(id) ? new int[]{-48, -24, 0, 24} : new int[]{64};
			int step = 32, maxR = 8000;
			for (int r = 0; r <= maxR; r += step) {
				for (int dx = -r; dx <= r; dx += step)
					for (int dz = -r; dz <= r; dz += step) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
						int x = px + dx, z = pz + dz;
						for (int y : ys) {
							if (BiomeInfo.id(w.noiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))).equals(id)) {
								int sy = BiomeInfo.isCave(id) ? y : w.surfaceY(x, z);
								int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
								biomeResultCoords = x + " " + sy + " " + z;
								biomeResult = pretty + ": " + dist + " blocks away";
								return;
							}
						}
					}
			}
			biomeResult = "No " + pretty + " within " + maxR + " blocks.";
		}, worker);
	}

	@Override
	public List<Row> headerRows(int width) {
		List<Row> rows = new ArrayList<>();
		OptionalLong s = SeedStore.current();
		SeedStore.Status st = SeedStore.status();
		rows.add(new Rows.Header("Seed"));
		rows.add(new Rows.Info("World seed", s.isPresent() ? Long.toString(s.getAsLong()) : "not set (/claude seed set <seed>)", s.isPresent() ? Theme.TEXT : Theme.SUB));
		rows.add(new Rows.Info("Check", SeedStore.describe(st), st == SeedStore.Status.VERIFIED ? Theme.GOOD : st == SeedStore.Status.MISMATCH ? 0xFFFF6B6B : Theme.SUB));
		if (!isEnabled()) return rows;
		if (world == null) {
			rows.add(new Rows.Text(s.isPresent() ? "Loading vanilla worldgen..." : "Set the world seed to start.", Theme.SUB, width));
			return rows;
		}
		rows.add(new Rows.Header("Here"));
		if (hudBiomeName != null) rows.add(new Rows.Info("Biome", hudBiomeName, Theme.TEXT));
		if (hudTreeText != null) rows.add(new Rows.Info("Trees", hudTreeText, Theme.TEXT));
		if (world.dimension() == Level.OVERWORLD) rows.add(new Rows.Info("Slime chunk", slime ? "Yes" : "No", slime ? Theme.GOOD : Theme.SUB));
		rows.add(new Rows.Button("Open Seed Map", () -> mc.setScreen(new SeedMapScreen())));
		rows.add(new Rows.Header("Find a biome"));
		rows.add(new Rows.Mode(findBiome));
		rows.add(new Rows.Button("Find nearest " + findBiome.get(), this::searchBiome));
		if (biomeResult != null) {
			String coords = biomeResultCoords;
			if (coords != null) rows.add(Rows.copy(coords, biomeResult));
			else rows.add(new Rows.Text(biomeResult, Theme.SUB, width));
		}
		return rows;
	}

	@Override
	public boolean isSettingVisible(dev.claudeclient.setting.Setting<?> setting) {
		return setting != findBiome;
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		if (!isEnabled() || world == null) return rows;
		rows.add(new Rows.Header("Nearest structures"));
		List<StructureFinder.Found> list = found;
		if (list.isEmpty()) rows.add(new Rows.Text(searching != null && !searching.isDone() ? "Searching..." : "None found in range.", Theme.SUB, width));
		for (int i = 0; i < Math.min(40, list.size()); i++) {
			StructureFinder.Found f = list.get(i);
			int d = (int) Math.round(Math.sqrt(Math.pow(f.x() - mc.player.getX(), 2) + Math.pow(f.z() - mc.player.getZ(), 2)));
			String coords = f.x() + " " + f.y() + " " + f.z();
			rows.add(Rows.copy(coords, f.name() + "  ·  " + d + "m" + (f.approximate() ? "  ·  approx." : "")));
		}
		return rows;
	}
}
