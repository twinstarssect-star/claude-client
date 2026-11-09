package dev.claudeclient.module.finders;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * See armor stands through blocks, underground included: the armor stand's own model shape is drawn on top of the
 * world, it's never hidden for being far away, and a label lists what it's wearing and holding.
 * <p>
 * It can only show armor stands the server actually sends. Some anti-freecam plugins don't send entities in hidden
 * areas at all; there's nothing on your client to draw for those.
 */
public final class ArmorStandBypass extends Module {
	private static ArmorStandBypass instance;

	private final BoolSetting model = add(new BoolSetting("Show Model", "Draw the armor stand's shape through blocks.", true));
	private final BoolSetting items = add(new BoolSetting("Show Items", "A label with its armor and held items.", true));
	private final BoolSetting markers = add(new BoolSetting("Include Markers", "Also tiny marker stands (holograms, map tools).", false));
	private final BoolSetting onlyWithItems = add(new BoolSetting("Only With Items", "Only label stands that wear or hold something.", false));
	private final NumberSetting range = add(new NumberSetting("Range", "How far away they're shown (the server decides how far it sends them).",
			16384, 16, 16384, 16, " blocks"));

	private static final int MAX_CUBES = 6000;
	private static final List<float[]> CUBES = new ArrayList<>();
	private static int cubeCount;
	private static final PoseStack WALK = new PoseStack();
	private static final Vector3f V = new Vector3f();
	private static final int[][] FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};

	public ArmorStandBypass() {
		super("Armor Stand Bypass", "See armor stands through blocks (underground too) and what's on them. Not a box: the stand "
				+ "itself shows through the walls.", Category.FINDERS, false);
		instance = this;
	}

	public static boolean active() {
		return instance != null && instance.isEnabled();
	}

	/** Called from the armor stand render layer, in model space, for every armor stand vanilla draws this frame. */
	public static void capture(PoseStack ps, LivingEntityRenderState state, EntityModel<?> m) {
		ArmorStandBypass a = instance;
		if (a == null || !a.isEnabled() || !a.model.get() || cubeCount >= MAX_CUBES) return;
		WALK.last().pose().set(ps.last().pose());
		WALK.last().normal().set(ps.last().normal());
		m.root().visit(WALK, (p, path, index, cube) -> {
			if (cubeCount >= MAX_CUBES) return;
			float[] out;
			if (cubeCount < CUBES.size()) out = CUBES.get(cubeCount);
			else CUBES.add(out = new float[24]);
			cubeCount++;
			for (int i = 0; i < 8; i++) {
				float x = ((i & 1) == 0 ? cube.minX : cube.maxX) / 16f;
				float y = ((i & 2) == 0 ? cube.minY : cube.maxY) / 16f;
				float z = ((i & 4) == 0 ? cube.minZ : cube.maxZ) / 16f;
				p.pose().transformPosition(x, y, z, V);
				out[i * 3] = V.x;
				out[i * 3 + 1] = V.y;
				out[i * 3 + 2] = V.z;
			}
		});
	}

	@Override
	protected void onDisable() {
		cubeCount = 0;
	}

	@Override
	public void onRender3D(Render3D r) {
		try {
			int col = 0x70E6E2D8;
			for (int i = 0; i < cubeCount; i++) {
				float[] c = CUBES.get(i);
				for (int[] f : FACES) {
					int a = f[0] * 3, b = f[1] * 3, cc = f[2] * 3, d = f[3] * 3;
					r.quadRel(col, c[a], c[a + 1], c[a + 2], c[b], c[b + 1], c[b + 2], c[cc], c[cc + 1], c[cc + 2], c[d], c[d + 1], c[d + 2]);
				}
			}
		} finally {
			cubeCount = 0;
		}
		if (!items.get()) return;
		double max = range.get();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ArmorStand stand)) continue;
			if (stand.isMarker() && !markers.get()) continue;
			AABB box = r.entityBox(stand, 0);
			double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
			if (r.distance(cx, box.maxY, cz) > max) continue;
			List<String> lines = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			lines.add(stand.hasCustomName() ? stand.getCustomName().getString() : "Armor Stand");
			colors.add(Theme.ACCENT);
			int itemLines = 0;
			for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
					EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
				ItemStack s = stand.getItemBySlot(slot);
				if (s.isEmpty()) continue;
				itemLines++;
				String name = s.getHoverName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : "") + (s.isEnchanted() ? " *" : "");
				lines.add(slotName(slot) + ": " + name);
				colors.add(s.isEnchanted() ? 0xFFC9A8FF : Theme.TEXT);
			}
			if (itemLines == 0 && onlyWithItems.get()) continue;
			if (itemLines == 0) {
				lines.add("(empty)");
				colors.add(Theme.SUB);
			}
			int[] cols = colors.stream().mapToInt(Integer::intValue).toArray();
			r.text(cx, box.maxY + 0.35 + lines.size() * 0.12, cz, lines.toArray(new String[0]), cols, 0.9f);
			if (!model.get()) r.box(box, 0, 0x90E6E2D8);
		}
	}

	private static String slotName(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> "Head";
			case CHEST -> "Chest";
			case LEGS -> "Legs";
			case FEET -> "Feet";
			case MAINHAND -> "Hand";
			case OFFHAND -> "Offhand";
			default -> slot.getName();
		};
	}

	@Override
	public String hudInfo() {
		if (mc.level == null) return null;
		int n = 0;
		for (Entity e : mc.level.entitiesForRendering()) if (e instanceof ArmorStand) n++;
		return Integer.toString(n);
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("Note"));
		rows.add(new Rows.Text("Shows every armor stand the server sends, through any blocks and at any distance. If a server "
				+ "doesn't send the armor stands in an area at all, no client can show them.", Theme.SUB, width));
		return rows;
	}
}
