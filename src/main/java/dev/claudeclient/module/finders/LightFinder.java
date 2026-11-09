package dev.claudeclient.module.finders;

import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.ModeSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.util.LightReader;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A yellow overlay over every space that has block light, read from the light data the server sends rather than from
 * the blocks. Servers compute light from the real world, so light usually still shows rooms, tunnels and torches that
 * anti-freecam / anti-xray hides (e.g. everything below y=0 replaced with stone).
 * <ul>
 * <li><b>All Light</b>: every lit space in range, brighter where the light is stronger.</li>
 * <li><b>Hidden Light</b>: only light inside blocks your client thinks are solid. Light can't exist inside a solid
 * block, so these are spaces the server is hiding from you.</li>
 * </ul>
 * Mark Sources puts a box on each light source (where the light comes from) with its brightness.
 */
public final class LightFinder extends Module {
	private record Source(long pos, int level) {}

	/** Per chunk: quads relative to the chunk's corner (12 floats each) with a colour each, and the sources found. */
	private record ChunkMesh(float[] quads, int[] colors, List<Source> sources) {}

	private final ModeSetting mode = add(new ModeSetting("Mode", "All Light: every lit space. Hidden Light: only light inside blocks "
			+ "that look solid to you (spaces the server is hiding).", "All Light", "All Light", "Hidden Light"));
	private final NumberSetting minLight = add(new NumberSetting("Min Light", "Dimmest light level shown.", 1, 1, 15, 1));
	private final NumberSetting minY = add(new NumberSetting("Min Y", "Lowest Y scanned.", -64, -64, 320, 1));
	private final NumberSetting maxY = add(new NumberSetting("Max Y", "Highest Y scanned.", 320, -64, 320, 1));
	private final NumberSetting radius = add(new NumberSetting("Chunk Radius", "How many chunks around you are scanned.", 12, 2, 32, 1));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the yellow is.", 0.45, 0.1, 1.0, 0.05));
	private final BoolSetting sources = add(new BoolSetting("Mark Sources", "Box each light source with its brightness.", true));
	private final BoolSetting tracers = add(new BoolSetting("Source Tracers", "Lines to the light sources.", false));

	private final Long2ObjectOpenHashMap<ChunkMesh> meshes = new Long2ObjectOpenHashMap<>();
	private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private int rescanTimer;
	private String lastConfig = "";

	public LightFinder() {
		super("Light Finder", "Yellow overlay of every lit space, from the server's light data, so it still shows rooms and torches "
				+ "underground when anti-freecam hides the blocks.", Category.FINDERS, false);
	}

	@Override
	protected void onDisable() {
		clear();
	}

	@Override
	public void onWorldChange() {
		clear();
	}

	private void clear() {
		meshes.clear();
		queue.clear();
		queued.clear();
	}

	private void enqueue(long key) {
		if (queued.add(key)) queue.enqueue(key);
	}

	@Override
	public void onChunkLoad(LevelChunk chunk) {
		enqueue(chunk.getPos().toLong());
	}

	@Override
	public void onChunkUnload(LevelChunk chunk) {
		meshes.remove(chunk.getPos().toLong());
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		enqueue(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	@Override
	public void onTick() {
		String config = mode.get() + minLight.getInt() + "/" + minY.getInt() + "/" + maxY.getInt();
		ChunkPos c = mc.player.chunkPosition();
		int r = radius.getInt();
		if (!config.equals(lastConfig)) {
			lastConfig = config;
			meshes.clear();
			rescanTimer = 0;
		}
		// Light settles a moment after chunks arrive and changes without block updates, so nearby chunks are re-read.
		if (--rescanTimer <= 0) {
			rescanTimer = 100;
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) enqueue(ChunkPos.asLong(c.x + dx, c.z + dz));
			meshes.long2ObjectEntrySet().removeIf(e -> Math.abs(ChunkPos.getX(e.getLongKey()) - c.x) > r + 1
					|| Math.abs(ChunkPos.getZ(e.getLongKey()) - c.z) > r + 1);
		}
		LightReader reader = new LightReader(mc.level);
		for (int budget = 0; budget < 4 && !queue.isEmpty(); budget++) {
			long key = queue.dequeueLong();
			queued.remove(key);
			int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
			if (Math.abs(cx - c.x) > r || Math.abs(cz - c.z) > r || !mc.level.hasChunk(cx, cz)) continue;
			ChunkMesh mesh = build(reader, cx, cz);
			if (mesh == null) meshes.remove(key);
			else meshes.put(key, mesh);
		}
	}

	private boolean included(LightReader reader, BlockPos.MutableBlockPos p, int x, int y, int z, boolean hidden, int min) {
		if (y < minY.getInt() || y > maxY.getInt()) return false;
		if (!reader.chunkLoaded(x, z)) return false;
		if (reader.get(x, y, z) < min) return false;
		if (!hidden) return true;
		BlockState s = mc.level.getBlockState(p.set(x, y, z));
		// Light can't be inside a block that stops light completely, so the real block there is something else.
		return s.getLightBlock() >= 15 && s.getLightEmission() == 0;
	}

	private ChunkMesh build(LightReader reader, int cx, int cz) {
		boolean hidden = mode.is("Hidden Light");
		int min = minLight.getInt();
		int y0 = Math.max(minY.getInt(), mc.level.getMinY()), y1 = Math.min(maxY.getInt(), mc.level.getMaxY());
		FloatArrayList quads = new FloatArrayList();
		IntArrayList colors = new IntArrayList();
		List<Source> found = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int baseAlpha = (int) Math.round(opacity.get() * 200);
		for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
			DataLayer d = reader.section(cx, sy, cz);
			if (d.isDefinitelyFilledWith(0)) continue;
			for (int ly = 0; ly < 16; ly++) {
				int y = (sy << 4) + ly;
				if (y < y0 || y > y1) continue;
				for (int lz = 0; lz < 16; lz++)
					for (int lx = 0; lx < 16; lx++) {
						int l = d.get(lx, ly, lz);
						if (l < min) continue;
						int x = (cx << 4) + lx, z = (cz << 4) + lz;
						if (!included(reader, p, x, y, z, hidden, min)) continue;
						if (quads.size() > 12 * 12000) continue;
						float t = l / 15f;
						int a = Math.max(18, Math.round(baseAlpha * (0.35f + 0.65f * t)));
						int rr = 255, gg = Math.round(200 + 40 * t), bb = Math.round(40 + 30 * (1 - t));
						int col = (a << 24) | (rr << 16) | (gg << 8) | bb;
						float fx = lx, fy = y, fz = lz;
						// Only the outside of the lit volume: faces whose neighbour isn't lit too.
						if (!included(reader, p, x, y - 1, z, hidden, min)) face(quads, colors, col, fx, fy, fz, fx, fy, fz + 1, fx + 1, fy, fz + 1, fx + 1, fy, fz);
						if (!included(reader, p, x, y + 1, z, hidden, min)) face(quads, colors, col, fx, fy + 1, fz, fx + 1, fy + 1, fz, fx + 1, fy + 1, fz + 1, fx, fy + 1, fz + 1);
						if (!included(reader, p, x, y, z - 1, hidden, min)) face(quads, colors, col, fx, fy, fz, fx + 1, fy, fz, fx + 1, fy + 1, fz, fx, fy + 1, fz);
						if (!included(reader, p, x, y, z + 1, hidden, min)) face(quads, colors, col, fx, fy, fz + 1, fx, fy + 1, fz + 1, fx + 1, fy + 1, fz + 1, fx + 1, fy, fz + 1);
						if (!included(reader, p, x - 1, y, z, hidden, min)) face(quads, colors, col, fx, fy, fz, fx, fy + 1, fz, fx, fy + 1, fz + 1, fx, fy, fz + 1);
						if (!included(reader, p, x + 1, y, z, hidden, min)) face(quads, colors, col, fx + 1, fy, fz, fx + 1, fy, fz + 1, fx + 1, fy + 1, fz + 1, fx + 1, fy + 1, fz);
						int src = reader.sourceLevel(x, y, z);
						if (src > 0 && found.size() < 400) found.add(new Source(BlockPos.asLong(x, y, z), src));
					}
			}
		}
		if (quads.isEmpty() && found.isEmpty()) return null;
		return new ChunkMesh(quads.toFloatArray(), colors.toIntArray(), found);
	}

	private static void face(FloatArrayList q, IntArrayList c, int col, float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float x4, float y4, float z4) {
		q.add(x1);
		q.add(y1);
		q.add(z1);
		q.add(x2);
		q.add(y2);
		q.add(z2);
		q.add(x3);
		q.add(y3);
		q.add(z3);
		q.add(x4);
		q.add(y4);
		q.add(z4);
		c.add(col);
	}

	/** What usually gives off this much light. */
	public static String guess(int level) {
		return switch (level) {
			case 15 -> "lantern, glowstone, sea lantern, lava, campfire, froglight";
			case 14 -> "torch, end rod, glow berries";
			case 13 -> "lit furnace, smoker or blast furnace";
			case 12 -> "candles x4, sea pickles x3";
			case 11 -> "nether portal";
			case 10 -> "soul torch / lantern / campfire, crying obsidian";
			case 9 -> "candles x3, lit redstone ore";
			case 7 -> "ender chest, enchanting table, redstone torch, glow lichen";
			case 6 -> "candles x2, sea pickle x2";
			case 5 -> "amethyst cluster";
			case 3 -> "magma block, candle";
			case 1 -> "brewing stand, sculk sensor, dragon egg";
			default -> "light source";
		};
	}

	@Override
	public void onRender3D(Render3D r) {
		Vec3 cam = r.camera();
		for (var e : meshes.long2ObjectEntrySet()) {
			long key = e.getLongKey();
			ChunkMesh m = e.getValue();
			float ox = (float) ((ChunkPos.getX(key) << 4) - cam.x), oy = (float) -cam.y, oz = (float) ((ChunkPos.getZ(key) << 4) - cam.z);
			float[] q = m.quads;
			for (int i = 0, k = 0; k < m.colors.length; i += 12, k++) {
				r.quadRel(m.colors[k], q[i] + ox, q[i + 1] + oy, q[i + 2] + oz, q[i + 3] + ox, q[i + 4] + oy, q[i + 5] + oz,
						q[i + 6] + ox, q[i + 7] + oy, q[i + 8] + oz, q[i + 9] + ox, q[i + 10] + oy, q[i + 11] + oz);
			}
			if (!sources.get()) continue;
			for (Source s : m.sources) {
				int x = BlockPos.getX(s.pos), y = BlockPos.getY(s.pos), z = BlockPos.getZ(s.pos);
				r.box(x + 0.2, y + 0.2, z + 0.2, x + 0.8, y + 0.8, z + 0.8, 0x60FFE040, 0xF0FFE040);
				if (r.distance(x, y, z) < 48) r.text(x + 0.5, y + 1.3, z + 0.5, "Light " + s.level, 0xFFFFE040, 0.7f);
				if (tracers.get()) r.tracer(x + 0.5, y + 0.5, z + 0.5, 0xA0FFE040);
			}
		}
	}

	@Override
	public String hudInfo() {
		return mode.is("Hidden Light") ? "Hidden" : null;
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		int srcCount = 0;
		for (ChunkMesh m : meshes.values()) srcCount += m.sources.size();
		rows.add(new Rows.Header("Status"));
		rows.add(new Rows.Info("Chunks with light", Integer.toString(meshes.size()), Theme.TEXT));
		rows.add(new Rows.Info("Light sources", Integer.toString(srcCount), 0xFFFFE040));
		rows.add(new Rows.Header("Brightness guide"));
		for (int lvl : new int[]{15, 14, 13, 10, 7}) rows.add(new Rows.Info(Integer.toString(lvl), guess(lvl), Theme.SUB));
		return rows;
	}
}
