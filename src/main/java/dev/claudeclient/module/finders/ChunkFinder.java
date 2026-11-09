package dev.claudeclient.module.finders;

import dev.claudeclient.Chat;
import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.util.LightReader;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Shared base for chunk finders: scans chunks as they load and whenever their blocks change, keeps the results after
 * the chunks unload (Remember), and draws each flagged chunk as a box with its reasons.
 */
public abstract class ChunkFinder extends Module {
	/** A flagged chunk. {@code minY}/{@code maxY} bound the evidence so the box sits where it was found. */
	public record Hit(int score, List<String> reasons, String label, int color, int minY, int maxY) {}

	protected final NumberSetting range = add(new NumberSetting("Range", "How far away flagged chunks are drawn.", 16384, 64, 16384, 64, " blocks"));
	protected final BoolSetting remember = add(new BoolSetting("Remember", "Keep finds after their chunks unload (until you change world).", true));
	protected final BoolSetting notify = add(new BoolSetting("Chat Alerts", "A chat line for each new find.", true));
	protected final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to flagged chunks.", false));

	protected final Long2ObjectOpenHashMap<Hit> results = new Long2ObjectOpenHashMap<>();
	private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	/** Chunk -> game tick it should be (re)scanned at, so light has settled after a load. */
	private final Long2LongOpenHashMap delayed = new Long2LongOpenHashMap();
	private long ticks;
	private int rescanTimer;

	protected ChunkFinder(String name, String description, boolean enabledByDefault) {
		super(name, description, Category.FINDERS, enabledByDefault);
	}

	/** Scan one loaded chunk; null means nothing to flag. Runs on the game thread. */
	protected abstract Hit scan(LevelChunk chunk, LightReader light);

	/** Whether a block change is worth rescanning the chunk for. */
	protected boolean relevant(BlockState oldState, BlockState newState) {
		return true;
	}

	protected int scansPerTick() {
		return 3;
	}

	@Override
	protected void onEnable() {
		clear();
		if (!inWorld()) return;
		ChunkPos c = mc.player.chunkPosition();
		int r = mc.options.getEffectiveRenderDistance() + 1;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) enqueue(ChunkPos.asLong(c.x + dx, c.z + dz));
	}

	@Override
	protected void onDisable() {
		clear();
	}

	@Override
	public void onWorldChange() {
		clear();
	}

	protected void clear() {
		results.clear();
		queue.clear();
		queued.clear();
		delayed.clear();
	}

	protected void enqueue(long key) {
		if (queued.add(key)) queue.enqueue(key);
	}

	@Override
	public void onChunkLoad(LevelChunk chunk) {
		long key = chunk.getPos().toLong();
		enqueue(key);
		delayed.put(key, ticks + 60);
	}

	@Override
	public void onChunkUnload(LevelChunk chunk) {
		if (!remember.get()) results.remove(chunk.getPos().toLong());
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		if (relevant(oldState, newState)) enqueue(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	@Override
	public void onTick() {
		ticks++;
		if (!delayed.isEmpty()) {
			var it = delayed.long2LongEntrySet().iterator();
			while (it.hasNext()) {
				var e = it.next();
				if (e.getLongValue() <= ticks) {
					enqueue(e.getLongKey());
					it.remove();
				}
			}
		}
		if (--rescanTimer <= 0) {
			rescanTimer = 300;
			ChunkPos c = mc.player.chunkPosition();
			int r = Math.min(10, mc.options.getEffectiveRenderDistance());
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) enqueue(ChunkPos.asLong(c.x + dx, c.z + dz));
		}
		LightReader light = new LightReader(mc.level);
		for (int i = 0; i < scansPerTick() && !queue.isEmpty(); i++) {
			long key = queue.dequeueLong();
			queued.remove(key);
			int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
			if (!mc.level.hasChunk(cx, cz)) continue;
			Hit hit = scan(mc.level.getChunk(cx, cz), light);
			Hit old = results.get(key);
			if (hit == null) {
				// Keep a remembered find when the chunk merely looks emptier now (e.g. the server stopped sending detail).
				if (old != null && !remember.get()) results.remove(key);
				continue;
			}
			results.put(key, hit);
			if (old == null && notify.get()) alert(cx, cz, hit);
		}
		if (unreported > 0 && System.currentTimeMillis() - lastAlert > 5000) {
			Chat.send(Chat.colored(name() + ": " + unreported + " more found (see the menu for the list)", Theme.SUB));
			unreported = 0;
			lastAlert = System.currentTimeMillis();
		}
	}

	private long lastAlert;
	private int unreported;

	/** One chat line per find, at most one every few seconds; the rest are summed up so chat doesn't flood. */
	private void alert(int cx, int cz, Hit hit) {
		long now = System.currentTimeMillis();
		if (now - lastAlert < 4000) {
			unreported++;
			return;
		}
		lastAlert = now;
		int d = (int) Math.round(Math.sqrt(mc.player.distanceToSqr((cx << 4) + 8, mc.player.getY(), (cz << 4) + 8)));
		Chat.send(Chat.colored(name() + ": ", Theme.SUB).append(Chat.colored(hit.label(), hit.color()))
				.append(Chat.colored(" at " + ((cx << 4) + 8) + " " + ((hit.minY() + hit.maxY()) / 2) + " " + ((cz << 4) + 8)
						+ " (" + d + "m) - " + String.join(", ", hit.reasons()), Theme.SUB)));
	}

	@Override
	public void onRender3D(Render3D r) {
		double max = range.get();
		for (Map.Entry<Long, Hit> e : results.long2ObjectEntrySet()) {
			long key = e.getKey();
			Hit h = e.getValue();
			int x0 = ChunkPos.getX(key) << 4, z0 = ChunkPos.getZ(key) << 4;
			double y0 = h.minY(), y1 = h.maxY() + 1;
			if (r.distance(x0 + 8, (y0 + y1) / 2, z0 + 8) > max) continue;
			r.box(x0, y0, z0, x0 + 16, y1, z0 + 16, Render3D.alpha(h.color(), 34), Render3D.alpha(h.color(), 220));
			r.text(x0 + 8, y1 + 1.2, z0 + 8, new String[]{h.label() + "  (" + h.score() + ")", String.join(", ", h.reasons().subList(0, Math.min(2, h.reasons().size())))},
					new int[]{h.color(), Theme.SUB}, 1.1f);
			if (tracers.get()) r.tracer(x0 + 8, (y0 + y1) / 2, z0 + 8, Render3D.alpha(h.color(), 170));
		}
	}

	@Override
	public String hudInfo() {
		return results.isEmpty() ? null : Integer.toString(results.size());
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("Found"));
		if (results.isEmpty()) {
			rows.add(new Rows.Text(isEnabled() ? "Nothing flagged yet." : "Turn it on to start scanning.", Theme.SUB, width));
			return rows;
		}
		List<Map.Entry<Long, Hit>> list = new ArrayList<>(results.long2ObjectEntrySet());
		if (mc.player != null) {
			double px = mc.player.getX(), pz = mc.player.getZ();
			list.sort(Comparator.comparingDouble(e -> {
				double dx = (ChunkPos.getX(e.getKey()) << 4) + 8 - px, dz = (ChunkPos.getZ(e.getKey()) << 4) + 8 - pz;
				return dx * dx + dz * dz;
			}));
		}
		for (int i = 0; i < Math.min(40, list.size()); i++) {
			long key = list.get(i).getKey();
			Hit h = list.get(i).getValue();
			String coords = ((ChunkPos.getX(key) << 4) + 8) + " " + ((h.minY() + h.maxY()) / 2) + " " + ((ChunkPos.getZ(key) << 4) + 8);
			rows.add(Rows.copy(coords, h.label() + " (" + h.score() + ")  ·  " + String.join(", ", h.reasons())));
		}
		return rows;
	}
}
