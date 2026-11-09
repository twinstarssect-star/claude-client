package dev.claudeclient.module.seed;

import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.seed.OreEngine;
import dev.claudeclient.seed.OreKind;
import dev.claudeclient.seed.SeedStore;
import dev.claudeclient.seed.SeedWorld;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Shows every ore from the world seed, at any depth, whatever the server sends you. The terrain is regenerated from
 * the seed with vanilla's own worldgen (noise, aquifers, ore veins, caves, surface), then vanilla's own feature code
 * places lakes, geodes, dungeons and ores on it with the same per-feature random seeds a server uses. Nothing is read
 * from the server's blocks, so anti-xray and anti-freecam don't affect it.
 * <p>
 * Accuracy check: wherever your client can see real ores (singleplayer, or servers without anti-xray), the predicted
 * ores are compared with the real ones and the match rate is shown in the menu.
 */
public final class SeedESP extends Module {
	private final Map<OreKind, BoolSetting> shown = new EnumMap<>(OreKind.class);
	private final NumberSetting radius = add(new NumberSetting("Chunk Radius", "How many chunks around you are worked out from the seed. "
			+ "Bigger takes longer and more memory.", 6, 1, 16, 1));
	private final NumberSetting minY = add(new NumberSetting("Min Y", "Lowest ores shown.", -64, -64, 320, 1));
	private final NumberSetting maxY = add(new NumberSetting("Max Y", "Highest ores shown.", 320, -64, 320, 1));
	private final BoolSetting hideMined = add(new BoolSetting("Hide Mined", "Hide predicted ores where your client sees air or a different "
			+ "block (mined out, or a cave/structure). Stone still counts, since anti-xray shows ores as stone.", true));
	private final BoolSetting fill = add(new BoolSetting("Fill", "Tinted fill inside the outline.", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to diamonds and ancient debris within 64 blocks.", false));
	private final NumberSetting threads = add(new NumberSetting("Threads", "CPU threads for generating terrain from the seed.",
			Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)), 1, 16, 1));

	private OreEngine engine;
	private long engineSeed;
	private ResourceKey<Level> engineDim;
	private int engineThreads;
	private CompletableFuture<SeedWorld> building;
	private String status = "";
	private int verifyTimer;
	private int[] verify = new int[5]; // match, hiddenAsStone, wrong, unpredictedReal, chunks

	public SeedESP() {
		super("Seed ESP", "Every ore from the world seed (diamonds, debris, gold... and ore veins), worked out with vanilla's own "
				+ "worldgen, so anti-xray and anti-freecam can't hide them. Needs the world seed: /claude seed set <seed>.", Category.SEED, false);
		for (OreKind k : OreKind.values()) shown.put(k, add(new BoolSetting(k.label, "Show " + k.label + ".", k.defaultOn)));
	}

	@Override
	protected void onDisable() {
		stopEngine();
		status = "";
	}

	@Override
	public void onWorldChange() {
		stopEngine();
	}

	private void stopEngine() {
		if (engine != null) engine.stop();
		engine = null;
		building = null;
		verify = new int[5];
	}

	@Override
	public void onTick() {
		OptionalLong seed = SeedStore.current();
		if (seed.isEmpty()) {
			status = "No world seed. Use /claude seed set <seed>.";
			stopEngine();
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		if (engine != null && (engine.world().seed() != seed.getAsLong() || engine.world().dimension() != dim || engineThreads != threads.getInt())) stopEngine();
		if (engine == null) {
			if (building == null) {
				long s = seed.getAsLong();
				engineSeed = s;
				engineDim = dim;
				status = "Loading vanilla worldgen...";
				building = CompletableFuture.supplyAsync(() -> SeedWorld.cached(s, dim));
				return;
			} else if (building.isDone()) {
				try {
					SeedWorld w = building.join();
					if (w == null) {
						status = "This dimension can't be generated from the seed.";
						return;
					}
					if (w.seed() != engineSeed || w.dimension() != engineDim) {
						building = null;
						return;
					}
					engineThreads = threads.getInt();
					engine = new OreEngine(w, engineThreads);
				} catch (Throwable t) {
					status = "Couldn't load worldgen: " + t.getMessage();
					building = null;
					return;
				}
			} else {
				return;
			}
		}
		ChunkPos c = mc.player.chunkPosition();
		engine.setCenter(c.x, c.z, radius.getInt());
		int total = (2 * radius.getInt() + 1) * (2 * radius.getInt() + 1);
		int near = 0;
		for (long key : engine.results().keySet()) {
			if (Math.abs(ChunkPos.getX(key) - c.x) <= radius.getInt() && Math.abs(ChunkPos.getZ(key) - c.z) <= radius.getInt()) near++;
		}
		status = engine.error() != null ? "Error: " + engine.error() : near >= total ? "Up to date" : "Working: " + near + "/" + total + " chunks";
		if (--verifyTimer <= 0) {
			verifyTimer = 40;
			verify = verify(c);
		}
	}

	/** Compare predictions with the blocks your client actually has, in loaded chunks around you. */
	private int[] verify(ChunkPos c) {
		int[] v = new int[5];
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int r = Math.min(radius.getInt(), 4);
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				int cx = c.x + dx, cz = c.z + dz;
				List<OreEngine.Ore> ores = engine.results().get(ChunkPos.asLong(cx, cz));
				if (ores == null || !mc.level.hasChunk(cx, cz)) continue;
				v[4]++;
				java.util.HashSet<Long> predicted = new java.util.HashSet<>();
				for (OreEngine.Ore o : ores) {
					predicted.add(o.pos());
					BlockState s = mc.level.getBlockState(p.set(o.pos()));
					OreKind actual = OreKind.of(s);
					if (actual == o.kind()) v[0]++;
					else if (actual == null && stoneLike(s)) v[1]++;
					else if (!s.isAir()) v[2]++;
				}
				var chunk = mc.level.getChunk(cx, cz);
				var sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					var sec = sections[i];
					if (sec.hasOnlyAir() || !sec.maybeHas(OreKind::isOre)) continue;
					int by = chunk.getSectionYFromSectionIndex(i) << 4;
					for (int y = 0; y < 16; y++)
						for (int z = 0; z < 16; z++)
							for (int x = 0; x < 16; x++) {
								if (!OreKind.isOre(sec.getBlockState(x, y, z))) continue;
								if (!predicted.contains(BlockPos.asLong((cx << 4) + x, by + y, (cz << 4) + z))) v[3]++;
							}
				}
			}
		return v;
	}

	/**
	 * True if your client clearly shows something else there (air in a section that has real blocks, or a different
	 * non-stone block). A section that's all air is treated as hidden by the server, not as mined out.
	 */
	private boolean contradicted(int x, int y, int z, OreKind kind) {
		var chunk = mc.level.getChunk(x >> 4, z >> 4);
		int si = chunk.getSectionIndex(y);
		if (si < 0 || si >= chunk.getSectionsCount()) return false;
		var sec = chunk.getSection(si);
		if (sec.hasOnlyAir()) return false;
		BlockState s = sec.getBlockState(x & 15, y & 15, z & 15);
		return OreKind.of(s) != kind && !stoneLike(s);
	}

	private static boolean stoneLike(BlockState s) {
		return s.is(BlockTags.STONE_ORE_REPLACEABLES) || s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES) || s.is(BlockTags.BASE_STONE_NETHER);
	}

	@Override
	public void onRender3D(Render3D r) {
		if (engine == null) return;
		ChunkPos c = mc.player.chunkPosition();
		int rad = radius.getInt(), y0 = minY.getInt(), y1 = maxY.getInt();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (Map.Entry<Long, List<OreEngine.Ore>> e : engine.results().entrySet()) {
			long key = e.getKey();
			if (Math.abs(ChunkPos.getX(key) - c.x) > rad + 2 || Math.abs(ChunkPos.getZ(key) - c.z) > rad + 2) continue;
			boolean loaded = mc.level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
			for (OreEngine.Ore o : e.getValue()) {
				if (!shown.get(o.kind()).get()) continue;
				int x = BlockPos.getX(o.pos()), y = BlockPos.getY(o.pos()), z = BlockPos.getZ(o.pos());
				if (y < y0 || y > y1) continue;
				if (hideMined.get() && loaded && contradicted(x, y, z, o.kind())) continue;
				int col = o.kind().color;
				r.box(x, y, z, x + 1, y + 1, z + 1, fill.get() ? Render3D.alpha(col, 55) : 0, Render3D.alpha(col, 225));
				if (tracers.get() && (o.kind() == OreKind.DIAMOND || o.kind() == OreKind.ANCIENT_DEBRIS) && r.distance(x, y, z) < 64) {
					r.tracer(x + 0.5, y + 0.5, z + 0.5, Render3D.alpha(col, 170));
				}
			}
		}
	}

	/** Match rate among ores your client can actually see, or -1 when there's nothing to compare. */
	public int accuracy() {
		int seen = verify[0] + verify[2] + verify[3];
		return seen < 20 ? -1 : Math.round(100f * verify[0] / seen);
	}

	@Override
	public String hudInfo() {
		int a = accuracy();
		return engine == null ? null : a >= 0 ? a + "%" : null;
	}

	@Override
	public List<Row> headerRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("Seed"));
		OptionalLong s = SeedStore.current();
		SeedStore.Status st = SeedStore.status();
		rows.add(new Rows.Info("World seed", s.isPresent() ? Long.toString(s.getAsLong()) : "not set", s.isPresent() ? Theme.TEXT : Theme.SUB));
		int sc = st == SeedStore.Status.VERIFIED ? Theme.GOOD : st == SeedStore.Status.MISMATCH ? 0xFFFF6B6B : Theme.SUB;
		rows.add(new Rows.Info("Check", SeedStore.describe(st), sc));
		if (s.isEmpty() && !SeedStore.singleplayer()) {
			rows.add(new Rows.Text("Type /claude seed set <seed> in chat. The server only sends a hash of the seed, which can't be "
					+ "turned back into the seed, but it does prove the seed you enter is right.", Theme.SUB, width));
		}
		if (isEnabled()) {
			rows.add(new Rows.Info("Engine", status, Theme.TEXT));
			int a = accuracy();
			if (verify[4] > 0) {
				if (a >= 0) rows.add(new Rows.Info("Matches real ores", a + "% (" + verify[0] + " of " + (verify[0] + verify[2] + verify[3]) + ")",
						a >= 95 ? Theme.GOOD : a >= 80 ? 0xFFFFD233 : 0xFFFF6B6B));
				else if (verify[1] > 20) rows.add(new Rows.Info("Matches real ores", "can't tell: the server hides ores as stone", Theme.SUB));
			}
		}
		return rows;
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("How accurate is it?"));
		rows.add(new Rows.Text("Terrain, caves, aquifers and ore veins are generated by Minecraft's own code from the seed, and the "
				+ "ores, geodes, lakes and dungeons are placed by vanilla's own feature code with the server's exact random seeds. "
				+ "Exact for chunks a 1.21.11 server generated from that seed. It can be off right next to structures "
				+ "(mineshafts, ancient cities, trial chambers), and in chunks generated by much older versions.", Theme.SUB, width));
		return rows;
	}
}
