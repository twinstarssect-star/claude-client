package dev.claudeclient.module.finders;

import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.util.LightReader;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags chunks that look like a farm or a working base, from three kinds of evidence:
 * <ul>
 * <li><b>Blocks</b>: dense crops, sugar cane, kelp, bamboo, cactus, nether wart, stems, cocoa, berries, spawners,
 * redstone (observers, pistons, hoppers, droppers...), bubble columns.</li>
 * <li><b>Entities</b>: items and XP piling up, villagers, iron golems, crowds of one mob or animal.</li>
 * <li><b>Activity</b>, counted per minute from what the server sends: crops growing, plants growing taller, pistons
 * firing, dispensers, items appearing, mobs dying. This keeps working when anti-freecam hides the blocks, as long as
 * the server still sends those updates for the area.</li>
 * </ul>
 */
public final class FarmDebug extends ChunkFinder {
	private final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "Higher flags chunks with less evidence.", 5, 1, 10, 1));

	private static final int GROWTH = 0, PISTON = 1, DISPENSER = 2, ITEM = 3, XP = 4, DEATH = 5, KINDS = 6;

	/** Per-chunk activity: this minute and last minute, so the rate is always roughly "per minute". */
	private static final class Activity {
		final int[] cur = new int[KINDS], prev = new int[KINDS];
		long minuteStart = System.currentTimeMillis();
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

		void roll(long now) {
			while (now - minuteStart >= 60_000) {
				System.arraycopy(cur, 0, prev, 0, KINDS);
				java.util.Arrays.fill(cur, 0);
				minuteStart += 60_000;
				if (now - minuteStart >= 120_000) {
					java.util.Arrays.fill(prev, 0);
					minuteStart = now;
				}
			}
		}

		int rate(int kind) {
			return cur[kind] + prev[kind];
		}

		boolean idle() {
			for (int i = 0; i < KINDS; i++) if (cur[i] != 0 || prev[i] != 0) return false;
			return true;
		}
	}

	/** Entity counts per chunk, refreshed every two seconds. */
	private static final class Mobs {
		int items, xp, villagers, golems, maxHostile, maxAnimal;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		final Map<String, Integer> byType = new HashMap<>();
	}

	private final Long2ObjectOpenHashMap<Activity> activity = new Long2ObjectOpenHashMap<>();
	private Long2ObjectOpenHashMap<Mobs> mobs = new Long2ObjectOpenHashMap<>();
	private int mobTimer;

	public FarmDebug() {
		super("Farm Debug", "Flags farms and working bases: dense crops/plants, redstone, spawners, piled-up items, crowds of mobs, "
				+ "and live activity (growth, pistons, items, mob deaths) that still comes through anti-freecam.", false);
	}

	@Override
	protected void clear() {
		super.clear();
		activity.clear();
		mobs = new Long2ObjectOpenHashMap<>();
	}

	private int bar() {
		return Math.max(3, 14 - sensitivity.getInt());
	}

	private void bump(int x, int y, int z, int kind) {
		long key = ChunkPos.asLong(x >> 4, z >> 4);
		Activity a = activity.computeIfAbsent(key, k -> new Activity());
		a.roll(System.currentTimeMillis());
		a.cur[kind]++;
		a.minY = Math.min(a.minY, y);
		a.maxY = Math.max(a.maxY, y);
		enqueue(key);
	}

	private static boolean isPlant(Block b) {
		return b instanceof CropBlock || b instanceof StemBlock || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO || b == Blocks.CACTUS
				|| b == Blocks.KELP || b == Blocks.KELP_PLANT || b == Blocks.NETHER_WART || b == Blocks.COCOA || b == Blocks.SWEET_BERRY_BUSH
				|| b == Blocks.MELON || b == Blocks.PUMPKIN || b == Blocks.CHORUS_FLOWER || b == Blocks.CAVE_VINES || b == Blocks.TWISTING_VINES
				|| b == Blocks.WEEPING_VINES;
	}

	@Override
	protected boolean relevant(BlockState oldState, BlockState newState) {
		return isPlant(oldState.getBlock()) || isPlant(newState.getBlock()) || isRedstone(newState.getBlock()) || isRedstone(oldState.getBlock());
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		super.onBlockUpdate(pos, oldState, newState);
		Block nb = newState.getBlock();
		// Growth: a crop/plant ageing in place, or a plant appearing on top of the same plant (cane, bamboo, kelp, cactus).
		if (isPlant(nb) && (oldState.getBlock() == nb || oldState.isAir() || oldState.is(Blocks.WATER))) bump(pos.getX(), pos.getY(), pos.getZ(), GROWTH);
	}

	@Override
	public void onBlockEvent(BlockPos pos, Block block, int type, int data) {
		if (block instanceof PistonBaseBlock || block instanceof MovingPistonBlock) bump(pos.getX(), pos.getY(), pos.getZ(), PISTON);
	}

	@Override
	public void onLevelEvent(int type, BlockPos pos, int data, boolean global) {
		if (type == LevelEvent.SOUND_DISPENSER_DISPENSE || type == LevelEvent.SOUND_DISPENSER_FAIL || type == LevelEvent.SOUND_CRAFTER_CRAFT) {
			bump(pos.getX(), pos.getY(), pos.getZ(), DISPENSER);
		}
	}

	@Override
	public void onSound(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z, float volume) {
		String path = sound.value().location().getPath();
		if (path.startsWith("entity.") && path.endsWith(".death") && !path.startsWith("entity.player")) bump((int) x, (int) y, (int) z, DEATH);
	}

	@Override
	public void onEntityLoad(Entity e) {
		if (e instanceof ItemEntity) bump(e.getBlockX(), e.getBlockY(), e.getBlockZ(), ITEM);
		else if (e instanceof ExperienceOrb) bump(e.getBlockX(), e.getBlockY(), e.getBlockZ(), XP);
	}

	@Override
	public void onTick() {
		super.onTick();
		if (--mobTimer > 0) return;
		mobTimer = 40;
		Long2ObjectOpenHashMap<Mobs> next = new Long2ObjectOpenHashMap<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player) continue;
			long key = ChunkPos.asLong(e.getBlockX() >> 4, e.getBlockZ() >> 4);
			Mobs m = next.computeIfAbsent(key, k -> new Mobs());
			boolean counted = true;
			if (e instanceof ItemEntity) m.items++;
			else if (e instanceof ExperienceOrb) m.xp++;
			else if (e instanceof Villager) m.villagers++;
			else if (e instanceof IronGolem) m.golems++;
			else if (e instanceof Enemy || e instanceof Animal) {
				String id = e.getType().getDescriptionId();
				int n = m.byType.merge(id, 1, Integer::sum);
				if (e instanceof Enemy) m.maxHostile = Math.max(m.maxHostile, n);
				else m.maxAnimal = Math.max(m.maxAnimal, n);
			} else counted = false;
			if (counted) {
				m.minY = Math.min(m.minY, e.getBlockY());
				m.maxY = Math.max(m.maxY, e.getBlockY());
			}
		}
		for (long key : next.keySet()) {
			Mobs m = next.get(key);
			if (m.items >= 10 || m.xp >= 5 || m.villagers >= 3 || m.golems >= 1 || m.maxHostile >= 8 || m.maxAnimal >= 12) enqueue(key);
		}
		mobs = next;
		long now = System.currentTimeMillis();
		activity.long2ObjectEntrySet().removeIf(e -> {
			e.getValue().roll(now);
			return e.getValue().idle();
		});
	}

	private static boolean isRedstone(Block b) {
		return b == Blocks.OBSERVER || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.DISPENSER || b == Blocks.DROPPER
				|| b == Blocks.HOPPER || b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.REDSTONE_WIRE || b == Blocks.CRAFTER
				|| b == Blocks.TARGET || b == Blocks.REDSTONE_LAMP;
	}

	private static final class Score {
		int total;
		String label = "Farm";
		int labelScore;
		int color = 0xFF7FD36A;
		final List<String> why = new ArrayList<>();

		void add(int points, String what, String label, int color) {
			total += points;
			why.add(what);
			if (points > labelScore) {
				labelScore = points;
				this.label = label;
				this.color = color;
			}
		}
	}

	@Override
	protected Hit scan(LevelChunk chunk, LightReader light) {
		int crops = 0, cane = 0, bamboo = 0, cactus = 0, kelp = 0, wart = 0, stems = 0, cocoa = 0, berries = 0;
		int observers = 0, pistons = 0, hoppers = 0, droppers = 0, redstone = 0, spawners = 0, bubbles = 0, beds = 0, composters = 0;
		int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
		LevelChunkSection[] sections = chunk.getSections();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection sec = sections[si];
			if (sec.hasOnlyAir()) continue;
			if (!sec.maybeHas(s -> isPlant(s.getBlock()) || isRedstone(s.getBlock()) || s.is(Blocks.SPAWNER) || s.is(Blocks.BUBBLE_COLUMN)
					|| s.getBlock() instanceof BedBlock || s.is(Blocks.COMPOSTER))) continue;
			int by = chunk.getSectionYFromSectionIndex(si) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						Block b = sec.getBlockState(x, y, z).getBlock();
						boolean hit = true;
						if (b instanceof CropBlock) crops++;
						else if (b == Blocks.SUGAR_CANE) cane++;
						else if (b == Blocks.BAMBOO) bamboo++;
						else if (b == Blocks.CACTUS) cactus++;
						else if (b == Blocks.KELP || b == Blocks.KELP_PLANT) kelp++;
						else if (b == Blocks.NETHER_WART) wart++;
						else if (b instanceof StemBlock || b == Blocks.ATTACHED_MELON_STEM || b == Blocks.ATTACHED_PUMPKIN_STEM) stems++;
						else if (b == Blocks.COCOA) cocoa++;
						else if (b == Blocks.SWEET_BERRY_BUSH) berries++;
						else if (b == Blocks.OBSERVER) observers++;
						else if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON) pistons++;
						else if (b == Blocks.HOPPER) hoppers++;
						else if (b == Blocks.DROPPER || b == Blocks.DISPENSER || b == Blocks.CRAFTER) droppers++;
						else if (isRedstone(b)) redstone++;
						else if (b == Blocks.SPAWNER) spawners++;
						else if (b == Blocks.BUBBLE_COLUMN) bubbles++;
						else if (b instanceof BedBlock) beds++;
						else if (b == Blocks.COMPOSTER) composters++;
						else hit = false;
						if (hit) {
							lowY = Math.min(lowY, by + y);
							highY = Math.max(highY, by + y);
						}
					}
		}
		int machinery = observers + pistons + droppers;
		int allRedstone = machinery + hoppers + redstone;

		Score s = new Score();
		final int GREEN = 0xFF7FD36A, BLUE = 0xFF4FC3F7, RED = 0xFFFF5C5C, ORANGE = 0xFFFFA94D, PURPLE = 0xFFB48CFF, YELLOW = 0xFFFFD84A;
		if (crops >= 24) s.add(Math.min(20, 4 + crops / 8), "Crops x" + crops, "Crop farm", GREEN);
		if (cane >= 12) s.add(Math.min(18, 3 + cane / 4 + (machinery >= 2 ? 6 : 0)), "Sugar cane x" + cane, machinery >= 2 ? "Auto sugar cane farm" : "Sugar cane farm", GREEN);
		if (bamboo >= 20 && (machinery >= 2 || hoppers >= 1)) s.add(Math.min(18, 6 + bamboo / 10), "Bamboo x" + bamboo, "Bamboo farm", GREEN);
		if (kelp >= 30 && (machinery >= 2 || hoppers >= 1)) s.add(Math.min(18, 6 + kelp / 12), "Kelp x" + kelp + " with redstone", "Kelp farm", BLUE);
		if (cactus >= 10 && (hoppers >= 1 || cactus >= 40)) s.add(Math.min(16, 4 + cactus / 6), "Cactus x" + cactus, "Cactus farm", GREEN);
		if (wart >= 12) s.add(Math.min(18, 4 + wart / 6), "Nether wart x" + wart, "Nether wart farm", RED);
		if (stems >= 8) s.add(Math.min(16, 4 + stems / 3), "Melon/pumpkin stems x" + stems, "Melon/pumpkin farm", GREEN);
		if (cocoa >= 20 || (cocoa >= 8 && machinery >= 2)) s.add(Math.min(14, 4 + cocoa / 5), "Cocoa x" + cocoa, "Cocoa farm", GREEN);
		if (berries >= 24) s.add(Math.min(12, 3 + berries / 8), "Berry bushes x" + berries, "Berry farm", GREEN);
		if (bubbles >= 24) s.add(Math.min(10, 3 + bubbles / 12), "Bubble columns x" + bubbles, "Mob elevator / transport", BLUE);
		if (hoppers >= 12) s.add(Math.min(16, 4 + hoppers / 4), "Hoppers x" + hoppers, "Storage / sorting system", YELLOW);
		if (allRedstone >= 24) s.add(Math.min(16, 4 + allRedstone / 8), "Redstone x" + allRedstone, "Redstone machine", ORANGE);
		if (beds >= 6 || composters >= 4) s.add(Math.min(10, beds + composters), "Beds x" + beds + ", composters x" + composters, "Villager breeder / farm", PURPLE);

		Mobs m = mobs.get(chunk.getPos().toLong());
		if (m != null) {
			if (spawners >= 1 && (hoppers >= 1 || allRedstone >= 3 || m.items >= 10 || m.maxHostile >= 6)) {
				s.add(14, "Spawner with collection", "Spawner farm", RED);
			}
			if (m.maxHostile >= 12) s.add(Math.min(18, 4 + m.maxHostile / 3), "Crowd of one mob x" + m.maxHostile, "Mob farm", RED);
			if (m.golems >= 3 || (m.villagers >= 3 && m.golems >= 1)) s.add(16, "Villagers x" + m.villagers + ", golems x" + m.golems, "Iron farm", PURPLE);
			else if (m.villagers >= 8) s.add(Math.min(16, 4 + m.villagers / 2), "Villagers x" + m.villagers, "Trading hall / villager base", PURPLE);
			if (m.maxAnimal >= 16) s.add(Math.min(14, 2 + m.maxAnimal / 3), "Crowd of animals x" + m.maxAnimal, "Animal farm", GREEN);
			if (m.items >= 25) s.add(Math.min(12, 2 + m.items / 10), "Items on the ground x" + m.items, "Farm output", YELLOW);
			if (m.xp >= 15) s.add(Math.min(12, 3 + m.xp / 6), "XP orbs x" + m.xp, "XP farm", YELLOW);
			if (m.minY != Integer.MAX_VALUE) {
				lowY = Math.min(lowY, m.minY);
				highY = Math.max(highY, m.maxY);
			}
		} else if (spawners >= 1 && (hoppers >= 1 || allRedstone >= 3)) {
			s.add(12, "Spawner with redstone/hoppers", "Spawner farm", RED);
		}

		Activity a = activity.get(chunk.getPos().toLong());
		if (a != null) {
			a.roll(System.currentTimeMillis());
			if (a.rate(GROWTH) >= 6) s.add(Math.min(16, 4 + a.rate(GROWTH) / 3), "Growing now: " + a.rate(GROWTH) + "/min", "Active crop farm", GREEN);
			if (a.rate(PISTON) >= 10) s.add(Math.min(16, 4 + a.rate(PISTON) / 5), "Pistons firing: " + a.rate(PISTON) + "/min", "Running redstone farm", ORANGE);
			if (a.rate(DISPENSER) >= 6) s.add(Math.min(12, 3 + a.rate(DISPENSER) / 4), "Dispensers: " + a.rate(DISPENSER) + "/min", "Running machine", ORANGE);
			if (a.rate(ITEM) >= 12) s.add(Math.min(14, 3 + a.rate(ITEM) / 6), "Items appearing: " + a.rate(ITEM) + "/min", "Farm output", YELLOW);
			if (a.rate(XP) >= 8) s.add(Math.min(14, 4 + a.rate(XP) / 4), "XP appearing: " + a.rate(XP) + "/min", "XP / mob farm", YELLOW);
			if (a.rate(DEATH) >= 6) s.add(Math.min(16, 4 + a.rate(DEATH) / 2), "Mobs dying: " + a.rate(DEATH) + "/min", "Mob farm", RED);
			if (a.minY != Integer.MAX_VALUE) {
				lowY = Math.min(lowY, a.minY);
				highY = Math.max(highY, a.maxY);
			}
		}

		if (s.why.isEmpty() || s.total < bar()) return null;
		if (lowY == Integer.MAX_VALUE) {
			lowY = (int) mc.player.getY() - 4;
			highY = lowY + 8;
		}
		return new Hit(s.total, List.copyOf(s.why), s.label, s.color, lowY, highY);
	}
}
