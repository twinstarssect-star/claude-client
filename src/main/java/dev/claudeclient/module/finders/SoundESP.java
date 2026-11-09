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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to what the server tells your client is happening around you: sound packets, block events (pistons moving,
 * chests opening, note blocks) and level events (dispensers, doors, blocks breaking, brewing, composters). Sounds that
 * sound like a base or farm are grouped into areas with a red box and a label of what was heard.
 * <p>
 * Range is set by the server, not by the client: normal sounds are sent to players within about 16 blocks (more for
 * loud ones), block and level events within 64 blocks, and a few global events (wither, dragon, end portal) to
 * everyone. Areas you heard stay marked for Keep For minutes, wherever you go.
 */
public final class SoundESP extends Module {
	public enum Kind {
		PISTONS("Pistons", 0xFFFF4D4D),
		REDSTONE("Redstone / dispensers", 0xFFFF7A45),
		MINECARTS("Minecarts / rails", 0xFFFFB347),
		DOORS("Doors / trapdoors / gates", 0xFFE6C35C),
		CONTAINERS("Chests / barrels / shulkers", 0xFFE8A93C),
		UTILITY("Anvils / brewing / enchanting / crafting", 0xFFB48CFF),
		FARM("Crops / bone meal / composters", 0xFF7FD36A),
		MOBS("Mobs / animals", 0xFFFF6F91),
		MOVEMENT("Footsteps / players", 0xFF6FB7FF),
		BUILDING("Breaking / placing", 0xFFA8A49B),
		OTHER("Other", 0xFF8A8F98);

		public final String label;
		public final int color;

		Kind(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	private record Heard(double x, double y, double z, Kind kind, long time) {}

	private static final class Area {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
		long last;
		int total;

		void add(Heard h) {
			minX = Math.min(minX, h.x);
			minY = Math.min(minY, h.y);
			minZ = Math.min(minZ, h.z);
			maxX = Math.max(maxX, h.x);
			maxY = Math.max(maxY, h.y);
			maxZ = Math.max(maxZ, h.z);
			counts.merge(h.kind, 1, Integer::sum);
			last = Math.max(last, h.time);
			total++;
		}

		double cx() {
			return (minX + maxX) / 2;
		}

		double cy() {
			return (minY + maxY) / 2;
		}

		double cz() {
			return (minZ + maxZ) / 2;
		}

		List<Map.Entry<Kind, Integer>> top() {
			List<Map.Entry<Kind, Integer>> l = new ArrayList<>(counts.entrySet());
			l.sort(Map.Entry.<Kind, Integer>comparingByValue().reversed());
			return l;
		}
	}

	private final BoolSetting pistons = add(new BoolSetting("Pistons & Redstone", "Pistons, dispensers, droppers, crafters, note blocks, levers, buttons.", true));
	private final BoolSetting minecarts = add(new BoolSetting("Minecarts", "Minecarts rolling, rails.", true));
	private final BoolSetting doors = add(new BoolSetting("Doors", "Doors, trapdoors, fence gates.", true));
	private final BoolSetting containers = add(new BoolSetting("Containers", "Chests, barrels, shulkers and ender chests opening.", true));
	private final BoolSetting utility = add(new BoolSetting("Utility Blocks", "Anvils, brewing stands, enchanting, grindstones, smithing.", true));
	private final BoolSetting farms = add(new BoolSetting("Farms", "Crops breaking/growing, bone meal, composters, mobs.", true));
	private final BoolSetting movement = add(new BoolSetting("Player Movement", "Footsteps, eating, hits and other player sounds (not yours).", true));
	private final BoolSetting building = add(new BoolSetting("Building", "Blocks being broken or placed.", true));
	private final NumberSetting ignoreOwn = add(new NumberSetting("Ignore Near You", "Sounds this close to you are treated as your own and ignored.", 3, 0, 16, 1, " blocks"));
	private final NumberSetting minHeard = add(new NumberSetting("Min Sounds", "How many sounds an area needs before it's boxed.", 2, 1, 30, 1));
	private final NumberSetting merge = add(new NumberSetting("Area Size", "Sounds this close together count as one area.", 10, 3, 48, 1, " blocks"));
	private final NumberSetting keepFor = add(new NumberSetting("Keep For", "How long a heard area stays marked.", 10, 1, 120, 1, " min"));
	private final BoolSetting alerts = add(new BoolSetting("Chat Alerts", "A chat line when a new base-like area is heard.", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to the areas.", false));

	private final ArrayDeque<Heard> heard = new ArrayDeque<>();
	private List<Area> areas = new ArrayList<>();
	private final LongOpenHashSet alerted = new LongOpenHashSet();
	private int clusterTimer;
	private long lastAlert;

	public SoundESP() {
		super("Sound ESP", "Red boxes where the server tells you there's base-like noise: pistons, minecarts, doors, chests, "
				+ "anvils, farms, mobs, other players. Doesn't count your own sounds.", Category.FINDERS, false);
	}

	@Override
	protected void onDisable() {
		heard.clear();
		areas = new ArrayList<>();
		alerted.clear();
	}

	@Override
	public void onWorldChange() {
		onDisable();
	}

	private boolean wanted(Kind k) {
		return switch (k) {
			case PISTONS, REDSTONE -> pistons.get();
			case MINECARTS -> minecarts.get();
			case DOORS -> doors.get();
			case CONTAINERS -> containers.get();
			case UTILITY -> utility.get();
			case FARM, MOBS -> farms.get();
			case MOVEMENT -> movement.get();
			case BUILDING -> building.get();
			case OTHER -> false;
		};
	}

	private void record(double x, double y, double z, Kind k) {
		if (k == null || !wanted(k)) return;
		double ig = ignoreOwn.get();
		if (ig > 0 && mc.player.distanceToSqr(x, y, z) <= ig * ig) return;
		heard.addLast(new Heard(x, y, z, k, System.currentTimeMillis()));
		while (heard.size() > 20000) heard.pollFirst();
	}

	public static Kind classify(String path) {
		if (path.startsWith("ui.") || path.startsWith("music") || path.startsWith("ambient.") || path.startsWith("weather.")) return null;
		if (path.contains("piston")) return Kind.PISTONS;
		if (path.contains("dispenser") || path.contains("crafter") || path.contains("note_block") || path.contains("lever")
				|| path.contains("button") || path.contains("pressure_plate") || path.contains("comparator") || path.contains("tripwire")
				|| path.contains("observer") || path.contains("copper_bulb")) return Kind.REDSTONE;
		if (path.contains("minecart") || path.contains("rail")) return Kind.MINECARTS;
		if (path.contains("door") || path.contains("fence_gate")) return Kind.DOORS;
		if (path.contains("chest") || path.contains("barrel") || path.contains("shulker_box") || path.contains("bundle")) return Kind.CONTAINERS;
		if (path.contains("anvil") || path.contains("brewing_stand") || path.contains("enchantment_table") || path.contains("grindstone")
				|| path.contains("smithing_table") || path.contains("loom") || path.contains("cartography") || path.contains("stonecutter")
				|| path.contains("beacon") || path.contains("respawn_anchor") || path.contains("conduit")) return Kind.UTILITY;
		if (path.contains("crop") || path.contains("bone_meal") || path.contains("composter") || path.contains("sweet_berry")
				|| path.contains("hoe.till") || path.contains("nether_wart") || path.contains("cave_vines") || path.contains("bee")
				|| path.contains("chicken.egg") || path.contains("sniffer")) return Kind.FARM;
		if (path.startsWith("entity.player.") || path.contains(".step") || path.contains("generic.eat") || path.contains("generic.drink")
				|| path.contains("armor.equip") || path.contains("item.shield") || path.contains("elytra") || path.contains("firework")
				|| path.contains("ender_pearl") || path.contains("totem")) return Kind.MOVEMENT;
		if (path.startsWith("entity.") && !path.contains("item.pickup") && !path.contains("experience_orb")) return Kind.MOBS;
		if (path.startsWith("block.") && (path.endsWith(".break") || path.endsWith(".place") || path.endsWith(".hit"))) return Kind.BUILDING;
		if (path.contains("item.pickup") || path.contains("experience_orb")) return Kind.FARM;
		return Kind.OTHER;
	}

	@Override
	public void onSound(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z, float volume) {
		if (source == SoundSource.MUSIC || source == SoundSource.AMBIENT || source == SoundSource.WEATHER || source == SoundSource.UI) return;
		record(x, y, z, classify(sound.value().location().getPath()));
	}

	@Override
	public void onBlockEvent(BlockPos pos, Block block, int type, int data) {
		Kind k = null;
		if (block instanceof PistonBaseBlock || block instanceof MovingPistonBlock) k = Kind.PISTONS;
		else if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock || block instanceof BarrelBlock) k = Kind.CONTAINERS;
		else if (block instanceof NoteBlock) k = Kind.REDSTONE;
		else if (block instanceof BellBlock) k = Kind.UTILITY;
		if (k != null) record(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, k);
	}

	@Override
	public void onLevelEvent(int type, BlockPos pos, int data, boolean global) {
		Kind k = switch (type) {
			case LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL, LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH,
					LevelEvent.SOUND_CRAFTER_CRAFT, LevelEvent.SOUND_CRAFTER_FAIL -> Kind.REDSTONE;
			case LevelEvent.SOUND_ANVIL_USED, LevelEvent.SOUND_ANVIL_LAND, LevelEvent.SOUND_ANVIL_BROKEN, LevelEvent.SOUND_BREWING_STAND_BREW,
					LevelEvent.SOUND_PLAY_JUKEBOX_SONG -> Kind.UTILITY;
			case LevelEvent.COMPOSTER_FILL, LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, LevelEvent.PARTICLES_BEE_GROWTH -> Kind.FARM;
			case LevelEvent.PARTICLES_DESTROY_BLOCK -> Kind.BUILDING;
			case LevelEvent.SOUND_ZOMBIE_WOODEN_DOOR, LevelEvent.SOUND_ZOMBIE_IRON_DOOR, LevelEvent.SOUND_ZOMBIE_DOOR_CRASH -> Kind.DOORS;
			default -> null;
		};
		if (k != null) record(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, k);
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		long keep = (long) (keepFor.get() * 60_000);
		while (!heard.isEmpty() && now - heard.peekFirst().time > keep) heard.pollFirst();
		if (--clusterTimer > 0) return;
		clusterTimer = 10;
		areas = cluster();
		if (!alerts.get()) return;
		for (Area a : areas) {
			Kind top = a.top().get(0).getKey();
			if (top == Kind.MOVEMENT || top == Kind.BUILDING) continue;
			long cell = BlockPos.asLong((int) Math.floor(a.cx() / 32), (int) Math.floor(a.cy() / 32), (int) Math.floor(a.cz() / 32));
			if (alerted.contains(cell) || System.currentTimeMillis() - lastAlert < 4000) continue;
			alerted.add(cell);
			lastAlert = System.currentTimeMillis();
			int d = (int) Math.round(Math.sqrt(mc.player.distanceToSqr(a.cx(), a.cy(), a.cz())));
			Chat.send(Chat.colored("Sound ESP: ", Theme.SUB).append(Chat.colored(describe(a, 2), 0xFFFF6B6B))
					.append(Chat.colored(" at " + (int) a.cx() + " " + (int) a.cy() + " " + (int) a.cz() + " (" + d + "m)", Theme.SUB)));
		}
	}

	/** Groups sounds that are close together into areas (connected grid cells). */
	private List<Area> cluster() {
		double cell = merge.get();
		Long2ObjectOpenHashMap<List<Heard>> cells = new Long2ObjectOpenHashMap<>();
		for (Heard h : heard) {
			long key = BlockPos.asLong((int) Math.floor(h.x / cell), (int) Math.floor(h.y / cell), (int) Math.floor(h.z / cell));
			cells.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
		}
		List<Area> out = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		for (long start : cells.keySet()) {
			if (!seen.add(start)) continue;
			Area a = new Area();
			LongArrayFIFOQueue q = new LongArrayFIFOQueue();
			q.enqueue(start);
			while (!q.isEmpty()) {
				long c = q.dequeueLong();
				for (Heard h : cells.get(c)) a.add(h);
				int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
				for (int dx = -1; dx <= 1; dx++)
					for (int dy = -1; dy <= 1; dy++)
						for (int dz = -1; dz <= 1; dz++) {
							long n = BlockPos.asLong(x + dx, y + dy, z + dz);
							if (cells.containsKey(n) && seen.add(n)) q.enqueue(n);
						}
			}
			if (a.total >= minHeard.getInt()) out.add(a);
		}
		out.sort(Comparator.comparingDouble(a -> mc.player.distanceToSqr(a.cx(), a.cy(), a.cz())));
		return out;
	}

	private static String describe(Area a, int n) {
		StringBuilder sb = new StringBuilder();
		List<Map.Entry<Kind, Integer>> top = a.top();
		for (int i = 0; i < Math.min(n, top.size()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(top.get(i).getKey().label).append(" x").append(top.get(i).getValue());
		}
		return sb.toString();
	}

	@Override
	public void onRender3D(Render3D r) {
		long now = System.currentTimeMillis();
		for (Area a : areas) {
			double pad = 1.0;
			long ago = (now - a.last) / 1000;
			float fresh = ago < 20 ? 1f : 0.55f;
			int line = Render3D.alpha(0xFF3B3B, (int) (235 * fresh));
			r.box(a.minX - pad, a.minY - pad, a.minZ - pad, a.maxX + pad, a.maxY + pad, a.maxZ + pad, Render3D.alpha(0xFF3B3B, (int) (38 * fresh)), line);
			String when = ago < 2 ? "now" : ago < 120 ? ago + "s ago" : (ago / 60) + "m ago";
			r.text(a.cx(), a.maxY + pad + 0.8, a.cz(), new String[]{describe(a, 3), a.total + " sounds - " + when},
					new int[]{0xFFFF8080, Theme.SUB}, 1f);
			if (tracers.get()) r.tracer(a.cx(), a.cy(), a.cz(), 0xB0FF4040);
		}
	}

	@Override
	public String hudInfo() {
		return areas.isEmpty() ? null : Integer.toString(areas.size());
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("Heard areas"));
		if (areas.isEmpty()) rows.add(new Rows.Text("Nothing yet.", Theme.SUB, width));
		long now = System.currentTimeMillis();
		for (int i = 0; i < Math.min(30, areas.size()); i++) {
			Area a = areas.get(i);
			String coords = (int) a.cx() + " " + (int) a.cy() + " " + (int) a.cz();
			long ago = (now - a.last) / 1000;
			rows.add(Rows.copy(coords, describe(a, 2) + "  ·  " + (ago < 120 ? ago + "s" : ago / 60 + "m") + " ago"));
		}
		rows.add(new Rows.Header("Range"));
		rows.add(new Rows.Text("The server decides what you can hear: normal sounds reach about 16 blocks (more if loud), pistons, "
				+ "chests, doors, dispensers and breaking reach 64 blocks. Heard areas stay marked wherever you go.", Theme.SUB, width));
		return rows;
	}
}
