package dev.claudeclient.module.finders;

import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.setting.Setting;
import dev.claudeclient.util.LightReader;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Boxes every storage block the server has sent, through walls, as far as you like. With Remember on, finds stay
 * after their chunks unload. Light Storage also reads the server's light data for hidden light sources with the
 * brightness of an ender chest (7) or a lit furnace / smoker / blast furnace (13) where the block you can see doesn't
 * give off light: a sign of a storage room the server is hiding from you.
 */
public final class StorageESP extends Module {
	public enum Kind {
		CHEST("Chest", 0xFFE8A93C),
		TRAPPED_CHEST("Trapped Chest", 0xFFE0573A),
		ENDER_CHEST("Ender Chest", 0xFFB45CF0),
		BARREL("Barrel", 0xFFB98A5A),
		SHULKER("Shulker Box", 0xFFF07AC8),
		HOPPER("Hopper", 0xFF9AA0A6),
		DISPENSER("Dispenser / Dropper", 0xFF7D8590),
		FURNACE("Furnace", 0xFFC9C2B8),
		OTHER("Other", 0xFF5FB8B0),
		LIGHT_ENDER("Hidden light 7 (ender chest?)", 0xFFD49BFF),
		LIGHT_FURNACE("Hidden light 13 (lit furnace?)", 0xFFFFB45C);

		public final String label;
		public final int color;

		Kind(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	private final BoolSetting chests = add(new BoolSetting("Chests", "Chests and trapped chests.", true));
	private final BoolSetting enderChests = add(new BoolSetting("Ender Chests", "Ender chests.", true));
	private final BoolSetting barrels = add(new BoolSetting("Barrels", "Barrels.", true));
	private final BoolSetting shulkers = add(new BoolSetting("Shulker Boxes", "Every colour of shulker box.", true));
	private final BoolSetting hoppers = add(new BoolSetting("Hoppers", "Hoppers (storage systems, farms).", true));
	private final BoolSetting dispensers = add(new BoolSetting("Dispensers / Droppers", "Dispensers and droppers.", false));
	private final BoolSetting furnaces = add(new BoolSetting("Furnaces", "Furnaces, smokers and blast furnaces.", false));
	private final BoolSetting other = add(new BoolSetting("Other", "Brewing stands, crafters, decorated pots, chiseled bookshelves.", false));
	private final BoolSetting lightStorage = add(new BoolSetting("Light Storage",
			"Finds storage from light: hidden light sources as bright as an ender chest (7) or a lit furnace (13) where the "
					+ "block you can see gives off no light. Works when the server hides the blocks but still sends real light.", true));
	private final BoolSetting fill = add(new BoolSetting("Fill", "Tinted fill inside the outline.", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines from your crosshair to each find.", false));
	private final BoolSetting labels = add(new BoolSetting("Labels", "Names over the hidden-light finds.", true));
	private final NumberSetting range = add(new NumberSetting("Range", "How far away finds are drawn.", 4096, 16, 16384, 16, " blocks"));
	private final BoolSetting remember = add(new BoolSetting("Remember", "Keep finds after their chunks unload (until you change world).", true));

	private final Long2ObjectOpenHashMap<Kind> found = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Kind> lightFound = new Long2ObjectOpenHashMap<>();
	private final LongArrayFIFOQueue lightQueue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private int rescanTimer;

	public StorageESP() {
		super("Storage ESP", "Boxes chests, ender chests, barrels, shulkers and more through walls, at any distance. "
				+ "Light Storage finds hidden storage rooms from the server's light data.", Category.FINDERS, false);
	}

	@Override
	public boolean isSettingVisible(Setting<?> s) {
		return s != labels || lightStorage.get();
	}

	@Override
	protected void onEnable() {
		reset();
		if (inWorld()) {
			int r = mc.options.getEffectiveRenderDistance() + 1;
			ChunkPos c = mc.player.chunkPosition();
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++)
					if (mc.level.hasChunk(c.x + dx, c.z + dz)) scanChunk(mc.level.getChunk(c.x + dx, c.z + dz));
		}
	}

	@Override
	protected void onDisable() {
		reset();
	}

	@Override
	public void onWorldChange() {
		reset();
	}

	private void reset() {
		found.clear();
		lightFound.clear();
		lightQueue.clear();
		queued.clear();
	}

	public static Kind kindOf(BlockState state) {
		Block b = state.getBlock();
		if (b instanceof TrappedChestBlock) return Kind.TRAPPED_CHEST;
		if (b instanceof ChestBlock) return Kind.CHEST;
		if (b instanceof EnderChestBlock) return Kind.ENDER_CHEST;
		if (b instanceof BarrelBlock) return Kind.BARREL;
		if (b instanceof ShulkerBoxBlock) return Kind.SHULKER;
		if (b instanceof HopperBlock) return Kind.HOPPER;
		if (b instanceof DispenserBlock) return Kind.DISPENSER;
		if (b instanceof AbstractFurnaceBlock) return Kind.FURNACE;
		if (b instanceof BrewingStandBlock || b instanceof CrafterBlock || b instanceof DecoratedPotBlock || b instanceof ChiseledBookShelfBlock) return Kind.OTHER;
		return null;
	}

	private boolean shown(Kind k) {
		return switch (k) {
			case CHEST, TRAPPED_CHEST -> chests.get();
			case ENDER_CHEST -> enderChests.get();
			case BARREL -> barrels.get();
			case SHULKER -> shulkers.get();
			case HOPPER -> hoppers.get();
			case DISPENSER -> dispensers.get();
			case FURNACE -> furnaces.get();
			case OTHER -> other.get();
			case LIGHT_ENDER, LIGHT_FURNACE -> lightStorage.get();
		};
	}

	private void scanChunk(LevelChunk chunk) {
		for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
			Kind k = kindOf(e.getValue().getBlockState());
			if (k != null) found.put(e.getKey().asLong(), k);
		}
		queueLight(chunk.getPos().toLong());
	}

	private void queueLight(long chunkKey) {
		if (queued.add(chunkKey)) lightQueue.enqueue(chunkKey);
	}

	@Override
	public void onChunkLoad(LevelChunk chunk) {
		scanChunk(chunk);
	}

	@Override
	public void onChunkUnload(LevelChunk chunk) {
		if (remember.get()) return;
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		found.long2ObjectEntrySet().removeIf(e -> (BlockPos.getX(e.getLongKey()) >> 4) == cx && (BlockPos.getZ(e.getLongKey()) >> 4) == cz);
		lightFound.long2ObjectEntrySet().removeIf(e -> (BlockPos.getX(e.getLongKey()) >> 4) == cx && (BlockPos.getZ(e.getLongKey()) >> 4) == cz);
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		Kind k = kindOf(newState);
		if (k != null) found.put(pos.asLong(), k);
		else found.remove(pos.asLong());
		if (lightStorage.get()) queueLight(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
	}

	@Override
	public void onTick() {
		if (!lightStorage.get()) return;
		// Light arrives (and settles) a little after the blocks, so nearby chunks are re-read every 10 seconds.
		if (--rescanTimer <= 0) {
			rescanTimer = 200;
			ChunkPos c = mc.player.chunkPosition();
			int r = Math.min(12, mc.options.getEffectiveRenderDistance());
			for (int dx = -r; dx <= r; dx++)
				for (int dz = -r; dz <= r; dz++) queueLight(ChunkPos.asLong(c.x + dx, c.z + dz));
		}
		LightReader reader = new LightReader(mc.level);
		for (int budget = 0; budget < 6 && !lightQueue.isEmpty(); budget++) {
			long key = lightQueue.dequeueLong();
			queued.remove(key);
			int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
			if (mc.level.hasChunk(cx, cz)) scanLight(reader, mc.level.getChunk(cx, cz));
		}
	}

	private void scanLight(LightReader reader, LevelChunk chunk) {
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		lightFound.long2ObjectEntrySet().removeIf(e -> (BlockPos.getX(e.getLongKey()) >> 4) == cx && (BlockPos.getZ(e.getLongKey()) >> 4) == cz);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int minS = chunk.getMinSectionY(), maxS = chunk.getMaxSectionY();
		for (int sy = minS; sy <= maxS; sy++) {
			DataLayer d = reader.section(cx, sy, cz);
			if (d.isDefinitelyFilledWith(0)) continue;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						int v = d.get(x, y, z);
						if (v != 7 && v != 13) continue;
						int wx = (cx << 4) + x, wy = (sy << 4) + y, wz = (cz << 4) + z;
						if (reader.sourceLevel(wx, wy, wz) != v) continue;
						BlockState visible = chunk.getBlockState(p.set(wx, wy, wz));
						// The block you can see explains the light: not hidden.
						if (visible.getLightEmission() == v) continue;
						lightFound.put(p.asLong(), v == 7 ? Kind.LIGHT_ENDER : Kind.LIGHT_FURNACE);
					}
		}
	}

	@Override
	public void onRender3D(Render3D r) {
		double max = range.get();
		draw(r, found, max, false);
		if (lightStorage.get()) draw(r, lightFound, max, true);
	}

	private void draw(Render3D r, Long2ObjectOpenHashMap<Kind> map, double max, boolean light) {
		for (Long2ObjectMap.Entry<Kind> e : map.long2ObjectEntrySet()) {
			Kind k = e.getValue();
			if (!shown(k)) continue;
			long p = e.getLongKey();
			int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
			if (r.distance(x + 0.5, y + 0.5, z + 0.5) > max) continue;
			double inset = (k == Kind.CHEST || k == Kind.TRAPPED_CHEST || k == Kind.ENDER_CHEST) ? 0.0625 : 0;
			int line = Render3D.alpha(k.color, 230);
			int fillColor = fill.get() ? Render3D.alpha(k.color, light ? 70 : 45) : 0;
			r.box(x + inset, y, z + inset, x + 1 - inset, y + 1 - inset * 2, z + 1 - inset, fillColor, line);
			if (tracers.get()) r.tracer(x + 0.5, y + 0.5, z + 0.5, Render3D.alpha(k.color, 170));
			if (light && labels.get()) r.text(x + 0.5, y + 1.6, z + 0.5, k.label, k.color, 1f);
		}
	}

	@Override
	public String hudInfo() {
		return Integer.toString(found.size() + lightFound.size());
	}

	@Override
	public List<Row> extraRows(int width) {
		Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
		for (Kind k : found.values()) counts.merge(k, 1, Integer::sum);
		for (Kind k : lightFound.values()) counts.merge(k, 1, Integer::sum);
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("Found"));
		if (counts.isEmpty()) rows.add(new Rows.Text(isEnabled() ? "Nothing yet." : "Turn it on to start finding.", Theme.SUB, width));
		for (Map.Entry<Kind, Integer> e : counts.entrySet()) rows.add(new Rows.Info(e.getKey().label, Integer.toString(e.getValue()), e.getKey().color));
		return rows;
	}
}
