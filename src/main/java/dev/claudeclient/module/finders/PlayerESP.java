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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boxes, tracers and name tags on other players through walls. The server only sends players within its entity
 * tracking range (often 48-128 blocks); when one leaves it, a faded "last seen" marker stays where they were.
 */
public final class PlayerESP extends Module {
	private record Ghost(String name, double x, double y, double z, long seenAt) {}

	private final BoolSetting boxes = add(new BoolSetting("Boxes", "A box around each player.", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines from your crosshair to each player.", true));
	private final BoolSetting tags = add(new BoolSetting("Name Tags", "Name, distance and health over each player.", true));
	private final ModeSetting color = add(new ModeSetting("Color", "Distance: red close, green far. Fixed: always the accent colour.",
			"Distance", "Distance", "Fixed"));
	private final NumberSetting range = add(new NumberSetting("Range", "How far away players are drawn (the server decides how far it sends them).",
			16384, 16, 16384, 16, " blocks"));
	private final NumberSetting lastSeen = add(new NumberSetting("Last Seen", "How long a marker stays where a player was last seen after the "
			+ "server stops sending them. 0 turns it off.", 60, 0, 600, 5, "s"));

	private final Map<UUID, Ghost> ghosts = new HashMap<>();

	public PlayerESP() {
		super("Player ESP", "Boxes, tracers and name tags on other players through walls, with last-seen markers when they "
				+ "leave the server's tracking range.", Category.FINDERS, false);
	}

	@Override
	public void onWorldChange() {
		ghosts.clear();
	}

	@Override
	protected void onDisable() {
		ghosts.clear();
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		for (Player p : mc.level.players()) {
			if (p == mc.player) continue;
			ghosts.put(p.getUUID(), new Ghost(p.getGameProfile().name(), p.getX(), p.getY(), p.getZ(), now));
		}
		long keep = (long) (lastSeen.get() * 1000);
		ghosts.values().removeIf(g -> now - g.seenAt > keep + 200);
	}

	private int colorFor(double dist) {
		if (color.is("Fixed")) return Theme.ACCENT;
		float t = (float) Mth.clamp(dist / 96.0, 0, 1);
		int r = (int) Mth.lerp(t, 255, 90), g = (int) Mth.lerp(t, 70, 230);
		return 0xFF000000 | (r << 16) | (g << 8) | 60;
	}

	@Override
	public void onRender3D(Render3D r) {
		double max = range.get();
		long now = System.currentTimeMillis();
		List<UUID> live = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (p == mc.player) continue;
			live.add(p.getUUID());
			AABB box = r.entityBox(p, 0.05);
			Vec3 c = box.getCenter();
			double d = r.distance(c.x, c.y, c.z);
			if (d > max) continue;
			int col = colorFor(d);
			if (boxes.get()) r.box(box, Render3D.alpha(col, 40), Render3D.alpha(col, 230));
			if (tracers.get()) r.tracer(c.x, box.maxY - 0.2, c.z, Render3D.alpha(col, 190));
			if (tags.get()) {
				String hp = Math.round(p.getHealth() + p.getAbsorptionAmount()) + " hp";
				r.text(c.x, box.maxY + 0.6, c.z, new String[]{p.getGameProfile().name(), Math.round(d) + "m  " + hp},
						new int[]{Theme.TEXT, col}, 1f);
			}
		}
		if (lastSeen.get() <= 0) return;
		Iterator<Map.Entry<UUID, Ghost>> it = ghosts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Ghost> e = it.next();
			if (live.contains(e.getKey())) continue;
			Ghost g = e.getValue();
			long ago = (now - g.seenAt) / 1000;
			if (r.distance(g.x, g.y, g.z) > max) continue;
			int col = 0xFF8A8F98;
			r.box(g.x - 0.3, g.y, g.z - 0.3, g.x + 0.3, g.y + 1.8, g.z + 0.3, 0, Render3D.alpha(col, 150));
			if (tags.get()) r.text(g.x, g.y + 2.4, g.z, new String[]{g.name, "last seen " + ago + "s ago"}, new int[]{0xFFC8CCD2, col}, 1f);
		}
	}

	@Override
	public String hudInfo() {
		return mc.level == null ? null : Integer.toString(Math.max(0, mc.level.players().size() - 1));
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		if (mc.level == null) return rows;
		rows.add(new Rows.Header("Players in range"));
		int n = 0;
		for (Player p : mc.level.players()) {
			if (p == mc.player) continue;
			n++;
			int d = (int) Math.round(p.distanceTo(mc.player));
			rows.add(new Rows.Info(p.getGameProfile().name(), d + "m  (" + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ() + ")", Theme.TEXT));
		}
		if (n == 0) rows.add(new Rows.Text("Nobody the server is sending right now.", Theme.SUB, width));
		return rows;
	}
}
