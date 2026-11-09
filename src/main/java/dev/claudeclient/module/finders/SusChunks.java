package dev.claudeclient.module.finders;

import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.util.LightReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Flags chunks that show signs of players, scored so one odd block doesn't light everything up:
 * <ul>
 * <li>Rotated deepslate: deepslate only generates standing up, so sideways deepslate was placed by a player.</li>
 * <li>Player-made blocks underground (hoppers, observers, beds, crafting tables, furnaces, ender chests, shulkers...).</li>
 * <li>Hidden light: light inside blocks that look solid to you, so the server is hiding a lit space there. This one
 * keeps working when anti-freecam replaces everything underground with stone, as long as the server sends real light.</li>
 * <li>Recent underground changes: blocks broken or placed below the Base Y line while you've been nearby.</li>
 * <li>Long-loaded growth: fully grown kelp underground, grown amethyst, very long cave vines and dripstone.</li>
 * </ul>
 */
public final class SusChunks extends ChunkFinder {
	private final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "Higher flags chunks with less evidence.", 5, 1, 10, 1));
	private final NumberSetting baseY = add(new NumberSetting("Base Y", "Below this height, player blocks and changes count as underground.", 0, -64, 128, 1));
	private final BoolSetting deepslate = add(new BoolSetting("Rotated Deepslate", "Sideways deepslate (only players place it that way).", true));
	private final BoolSetting playerBlocks = add(new BoolSetting("Player Blocks", "Hoppers, observers, beds, furnaces, crafting tables, ender chests, shulkers... underground.", true));
	private final BoolSetting hiddenLight = add(new BoolSetting("Hidden Light", "Light inside blocks that look solid (works through anti-freecam).", true));
	private final BoolSetting changes = add(new BoolSetting("Underground Changes", "Blocks broken or placed underground while you're nearby.", true));
	private final BoolSetting growth = add(new BoolSetting("Long Growth", "Grown kelp underground, grown amethyst, long cave vines and dripstone.", true));

	private static final Set<Block> PLAYER_BLOCKS = Set.of(Blocks.HOPPER, Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.CRAFTING_TABLE,
			Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
			Blocks.DAMAGED_ANVIL, Blocks.ENDER_CHEST, Blocks.BEACON, Blocks.RESPAWN_ANCHOR, Blocks.LODESTONE, Blocks.SMITHING_TABLE,
			Blocks.GRINDSTONE, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM, Blocks.STONECUTTER, Blocks.JUKEBOX,
			Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET, Blocks.NOTE_BLOCK, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
			Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK,
			Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT,
			Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT, Blocks.END_ROD);

	/** Underground block changes seen per chunk (decays slowly). */
	private final Long2IntOpenHashMap changeCounts = new Long2IntOpenHashMap();

	public SusChunks() {
		super("Sus Chunks", "Flags chunks with signs of players: rotated deepslate, player blocks underground, hidden light (works "
				+ "through anti-freecam), fresh digging and long-loaded growth.", false);
	}

	@Override
	protected void clear() {
		super.clear();
		changeCounts.clear();
	}

	private int bar() {
		return Math.max(2, 13 - sensitivity.getInt());
	}

	private static boolean isPlayerBlock(BlockState s) {
		Block b = s.getBlock();
		return PLAYER_BLOCKS.contains(b) || b instanceof net.minecraft.world.level.block.BedBlock || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
	}

	// Natural underground structures that generate some "player" blocks: trial chambers (beds, crafting tables,
	// hoppers) and ancient cities (comparators, note blocks, target blocks). Found by blocks only they have.
	private static final Set<Block> TRIAL_SIGNS = Set.of(Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF_BRICKS,
			Blocks.WAXED_COPPER_GRATE, Blocks.WAXED_OXIDIZED_COPPER_GRATE, Blocks.WAXED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB,
			Blocks.WAXED_CUT_COPPER, Blocks.WAXED_OXIDIZED_CUT_COPPER, Blocks.HEAVY_CORE);
	private static final Set<Block> TRIAL_BLOCKS = Set.of(Blocks.CRAFTING_TABLE, Blocks.HOPPER);
	private static final Set<Block> ANCIENT_SIGNS = Set.of(Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST);
	private static final Set<Block> ANCIENT_BLOCKS = Set.of(Blocks.COMPARATOR, Blocks.NOTE_BLOCK, Blocks.TARGET);

	/** [trial chamber nearby, ancient city nearby], from the palettes of this chunk and its neighbours. */
	private boolean[] naturalStructures(LevelChunk chunk) {
		boolean trial = false, ancient = false;
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		for (int dx = -1; dx <= 1 && !(trial && ancient); dx++)
			for (int dz = -1; dz <= 1; dz++) {
				if (!mc.level.hasChunk(cx + dx, cz + dz)) continue;
				for (LevelChunkSection sec : mc.level.getChunk(cx + dx, cz + dz).getSections()) {
					if (sec.hasOnlyAir()) continue;
					if (!trial && sec.maybeHas(s -> TRIAL_SIGNS.contains(s.getBlock()))) trial = true;
					if (!ancient && sec.maybeHas(s -> ANCIENT_SIGNS.contains(s.getBlock()))) ancient = true;
				}
			}
		return new boolean[]{trial, ancient};
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		super.onBlockUpdate(pos, oldState, newState);
		if (!changes.get() || pos.getY() >= baseY.getInt()) return;
		// Growth and fluids aren't people; air<->solid swaps and placed blocks are.
		if (oldState.getBlock() == newState.getBlock()) return;
		if (!oldState.getFluidState().isEmpty() || !newState.getFluidState().isEmpty()) return;
		changeCounts.addTo(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), 1);
	}

	@Override
	protected Hit scan(LevelChunk chunk, LightReader light) {
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		int rotated = 0, placed = 0, hidden = 0, grownKelp = 0, clusters = 0, vineRun = 0, dripRun = 0;
		int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
		int base = baseY.getInt();
		boolean[] natural = naturalStructures(chunk);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection sec = sections[si];
			int sy = chunk.getSectionYFromSectionIndex(si);
			int by = sy << 4;
			boolean any = !sec.hasOnlyAir() && sec.maybeHas(s -> s.is(Blocks.DEEPSLATE) || isPlayerBlock(s) || s.is(Blocks.KELP)
					|| s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT) || s.is(Blocks.POINTED_DRIPSTONE));
			if (any) {
				for (int x = 0; x < 16; x++)
					for (int z = 0; z < 16; z++) {
						int vr = 0, dr = 0;
						for (int y = 0; y < 16; y++) {
							BlockState s = sec.getBlockState(x, y, z);
							int wy = by + y;
							Block b = s.getBlock();
							if (deepslate.get() && b == Blocks.DEEPSLATE && s.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
								rotated++;
								lowY = Math.min(lowY, wy);
								highY = Math.max(highY, wy);
							}
							boolean structureBlock = (natural[0] && (TRIAL_BLOCKS.contains(b) || b instanceof net.minecraft.world.level.block.BedBlock))
									|| (natural[1] && ANCIENT_BLOCKS.contains(b));
							if (playerBlocks.get() && wy < base && isPlayerBlock(s) && !structureBlock) {
								placed++;
								lowY = Math.min(lowY, wy);
								highY = Math.max(highY, wy);
							}
							if (growth.get()) {
								if (b == Blocks.KELP && s.getValue(BlockStateProperties.AGE_25) >= 25 && roofed(chunk, p, (cx << 4) + x, wy, (cz << 4) + z)) grownKelp++;
								if (b == Blocks.AMETHYST_CLUSTER && nextToGeode(chunk, p, (cx << 4) + x, wy, (cz << 4) + z)) clusters++;
								vr = (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT) ? vr + 1 : 0;
								vineRun = Math.max(vineRun, vr);
								dr = (b == Blocks.POINTED_DRIPSTONE && s.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) ? dr + 1 : 0;
								dripRun = Math.max(dripRun, dr);
							}
						}
					}
			}
			if (hiddenLight.get()) {
				DataLayer d = light.section(cx, sy, cz);
				if (!d.isDefinitelyFilledWith(0)) {
					for (int y = 0; y < 16; y++)
						for (int z = 0; z < 16; z++)
							for (int x = 0; x < 16; x++) {
								if (d.get(x, y, z) == 0) continue;
								BlockState s = sec.getBlockState(x, y, z);
								// Only blocks that stop light completely: slabs, stairs, chests etc. let it in for real.
								if (s.getLightBlock() >= 15 && s.getLightEmission() == 0) {
									hidden++;
									lowY = Math.min(lowY, by + y);
									highY = Math.max(highY, by + y);
								}
							}
				}
			}
		}

		int score = 0;
		List<String> why = new ArrayList<>();
		if (rotated > 0) {
			score += Math.min(15, rotated * 3);
			why.add("Rotated deepslate x" + rotated);
		}
		if (placed > 0) {
			score += Math.min(20, placed * 2);
			why.add("Player blocks underground x" + placed);
		}
		if (hidden >= 3) {
			score += Math.min(16, 2 + hidden / 4);
			why.add("Hidden light x" + hidden);
		}
		int ch = changes.get() ? changeCounts.get(chunk.getPos().toLong()) : 0;
		if (ch >= 2) {
			score += Math.min(12, ch);
			why.add("Fresh underground changes x" + ch);
		}
		if (grownKelp > 0) {
			score += Math.min(12, grownKelp * 2);
			why.add("Grown kelp underground x" + grownKelp);
		}
		if (clusters >= 2) {
			score += Math.min(8, 1 + clusters / 2);
			why.add("Grown amethyst x" + clusters);
		}
		if (vineRun >= 10) {
			score += vineRun >= 20 ? 5 : 3;
			why.add("Cave vines " + vineRun + " long");
		}
		if (dripRun >= 18) {
			score += dripRun >= 35 ? 5 : 3;
			why.add("Dripstone " + dripRun + " long");
		}
		if (why.isEmpty() || score < bar()) return null;
		if (lowY == Integer.MAX_VALUE) {
			lowY = Math.min(base, (int) mc.player.getY()) - 8;
			highY = lowY + 16;
		}
		String label = score >= 20 ? "Very sus chunk" : "Sus chunk";
		int color = score >= 20 ? 0xFFFF4D6D : 0xFFC77DFF;
		return new Hit(score, List.copyOf(why), label, color, lowY, highY);
	}

	private static boolean roofed(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
		for (int dy = 1; dy <= 48; dy++) {
			BlockState s = chunk.getLevel().getBlockState(p.set(x, y + dy, z));
			if (s.isAir()) return false;
			if (s.is(Blocks.WATER) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
					|| s.is(Blocks.BUBBLE_COLUMN)) continue;
			return s.canOcclude();
		}
		return false;
	}

	private static boolean nextToGeode(LevelChunk chunk, BlockPos.MutableBlockPos p, int x, int y, int z) {
		for (Direction d : Direction.values()) {
			Block b = chunk.getLevel().getBlockState(p.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())).getBlock();
			if (b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST) return true;
		}
		return false;
	}

	@Override
	public void onTick() {
		super.onTick();
		// Old digging fades out over a few minutes.
		if (mc.level.getGameTime() % 1200 == 0) {
			var it = changeCounts.long2IntEntrySet().iterator();
			while (it.hasNext()) {
				var e = it.next();
				int v = e.getIntValue() / 2;
				if (v <= 0) it.remove();
				else e.setValue(v);
			}
		}
	}
}
