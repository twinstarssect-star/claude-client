package dev.claudeclient.gui;

import dev.claudeclient.Theme;
import dev.claudeclient.module.seed.BiomeChecker;
import dev.claudeclient.seed.BiomeInfo;
import dev.claudeclient.seed.SeedStore;
import dev.claudeclient.seed.SeedWorld;
import dev.claudeclient.seed.StructureFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A map of the world from its seed: biome colours, structures (from the Biome Checker's groups), slime chunks and
 * where you are. Drag to pan, scroll to zoom, click a structure to copy its coordinates, Space to recentre,
 * S to toggle slime chunks.
 */
public final class SeedMapScreen extends Screen {
	private static final int CELL = 3;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Claude Seed Map");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static int blocksPerCell = 16;
	private static boolean showSlime;

	private SeedWorld world;
	private double centerX, centerZ;
	private int mapX, mapY, mapW, mapH, cols, rows;
	private volatile int[] colors;
	private volatile boolean[] slimes;
	private volatile int renderedFor = -1;
	private volatile double drawnCX, drawnCZ;
	private volatile int drawnBpc;
	private volatile List<StructureFinder.Found> structures = List.of();
	private int requestId;
	private CompletableFuture<?> job;
	private String message;
	private boolean dragging;
	private double dragStartX, dragStartY, dragCX, dragCZ;
	private String copied;
	private long copiedAt;

	public SeedMapScreen() {
		super(Component.literal("Seed Map"));
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		mapX = 10;
		mapY = 26;
		mapW = width - 20;
		mapH = height - 50;
		cols = mapW / CELL;
		rows = mapH / CELL;
		if (world == null && mc.player != null) {
			centerX = mc.player.getX();
			centerZ = mc.player.getZ();
			BiomeChecker bc = BiomeChecker.get();
			world = bc != null ? bc.world() : null;
			OptionalLong seed = SeedStore.current();
			if (world == null && seed.isPresent()) {
				message = "Loading worldgen...";
				long s = seed.getAsLong();
				var dim = mc.level.dimension();
				CompletableFuture.supplyAsync(() -> SeedWorld.cached(s, dim), WORKER).thenAccept(w -> {
					world = w;
					message = w == null ? "This dimension can't be generated from the seed." : null;
					mc.execute(this::refresh);
				});
			} else if (seed.isEmpty()) {
				message = "No world seed. Use /claude seed set <seed> first.";
			}
		}
		refresh();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void refresh() {
		if (world == null) return;
		int id = ++requestId;
		SeedWorld w = world;
		double cx = centerX, cz = centerZ;
		int bpc = blocksPerCell, c = cols, r = rows;
		BiomeChecker bc = BiomeChecker.get();
		if (job != null) job.cancel(false);
		job = CompletableFuture.runAsync(() -> {
			int[] out = new int[c * r];
			boolean[] sl = new boolean[c * r];
			boolean overworld = w.dimension() == Level.OVERWORLD;
			int qy = QuartPos.fromBlock(w.dimension() == Level.NETHER ? 64 : 64);
			for (int j = 0; j < r; j++) {
				if (id != requestId) return;
				for (int i = 0; i < c; i++) {
					int x = (int) Math.floor(cx + (i - c / 2.0) * bpc), z = (int) Math.floor(cz + (j - r / 2.0) * bpc);
					out[j * c + i] = BiomeInfo.color(w.noiseBiome(QuartPos.fromBlock(x), qy, QuartPos.fromBlock(z)));
					if (overworld && bpc <= 16) sl[j * c + i] = WorldgenRandom.seedSlimeChunk(x >> 4, z >> 4, w.seed(), 987234911L).nextInt(10) == 0;
				}
			}
			drawnCX = cx;
			drawnCZ = cz;
			drawnBpc = bpc;
			colors = out;
			slimes = sl;
			renderedFor = id;
			int radiusChunks = (int) Math.min(512, Math.ceil(Math.max(c, r) * bpc / 2.0 / 16) + 2);
			structures = StructureFinder.of(w).find((int) Math.floor(cx) >> 4, (int) Math.floor(cz) >> 4, radiusChunks,
					g -> bc == null || bc.wants(g));
		}, WORKER);
	}

	private int screenX(double worldX) {
		return (int) Math.round(mapX + mapW / 2.0 + (worldX - centerX) / blocksPerCell * CELL);
	}

	private int screenY(double worldZ) {
		return (int) Math.round(mapY + mapH / 2.0 + (worldZ - centerZ) / blocksPerCell * CELL);
	}

	private double worldX(double sx) {
		return centerX + (sx - mapX - mapW / 2.0) / CELL * blocksPerCell;
	}

	private double worldZ(double sy) {
		return centerZ + (sy - mapY - mapH / 2.0) / CELL * blocksPerCell;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF0141312);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		Minecraft mc = Minecraft.getInstance();
		g.drawString(font, "Seed Map", 10, 9, Theme.ACCENT, false);
		String help = "drag pan · scroll zoom · click copies · space recentre · S slime";
		if (font.width(help) < width - 90) g.drawString(font, help, width - 10 - font.width(help), 9, Theme.SUB, false);
		g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, Theme.BORDER);
		g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF0E0D0C);
		if (message != null) {
			g.drawCenteredString(font, message, width / 2, height / 2, Theme.SUB);
			return;
		}
		int[] col = colors;
		boolean[] sl = slimes;
		g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
		if (col != null && col.length == cols * rows && drawnBpc == blocksPerCell) {
			// The image may be for an older centre (while dragging): shift it so it moves with the mouse.
			int ox = (int) Math.round((drawnCX - centerX) / blocksPerCell * CELL), oy = (int) Math.round((drawnCZ - centerZ) / blocksPerCell * CELL);
			for (int j = 0; j < rows; j++)
				for (int i = 0; i < cols; i++) {
					int x = mapX + i * CELL + ox, y = mapY + j * CELL + oy;
					g.fill(x, y, x + CELL, y + CELL, col[j * cols + i]);
					if (showSlime && sl != null && sl[j * cols + i]) g.fill(x, y, x + CELL, y + CELL, 0x6640FF40);
				}
		}
		StructureFinder.Found hover = null;
		for (StructureFinder.Found f : structures) {
			int sx = screenX(f.x()), sy = screenY(f.z());
			if (sx < mapX - 4 || sx > mapX + mapW + 4 || sy < mapY - 4 || sy > mapY + mapH + 4) continue;
			boolean h = Math.abs(mx - sx) <= 4 && Math.abs(my - sy) <= 4;
			if (h) hover = f;
			g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF000000);
			g.fill(sx - 2, sy - 2, sx + 3, sy + 3, f.group().color);
		}
		if (mc.player != null) {
			int px = screenX(mc.player.getX()), py = screenY(mc.player.getZ());
			g.fill(px - 3, py - 3, px + 4, py + 4, 0xFF000000);
			g.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
		}
		g.disableScissor();

		int wx = (int) Math.floor(worldX(mx)), wz = (int) Math.floor(worldZ(my));
		boolean inMap = mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH;
		String info;
		if (hover != null) info = hover.name() + "  " + hover.x() + " " + hover.y() + " " + hover.z() + (hover.approximate() ? " (approx.)" : "") + "  - click to copy";
		else if (inMap && world != null) info = wx + ", " + wz + "  " + BiomeInfo.name(world.noiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(64), QuartPos.fromBlock(wz)));
		else info = "";
		g.drawString(font, info, 10, height - 18, Theme.TEXT, false);
		String scale = "1 px = " + Math.max(1, blocksPerCell / CELL) + " blocks   " + structures.size() + " structures";
		if (copied != null && System.currentTimeMillis() - copiedAt < 1500) scale = "Copied " + copied;
		g.drawString(font, scale, width - 10 - font.width(scale), height - 18, Theme.SUB, false);
		if (renderedFor != requestId) g.drawString(font, "drawing...", mapX + 4, mapY + 4, Theme.TEXT, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == 0) {
			for (StructureFinder.Found f : structures) {
				int sx = screenX(f.x()), sy = screenY(f.z());
				if (Math.abs(e.x() - sx) <= 4 && Math.abs(e.y() - sy) <= 4) {
					copied = f.x() + " " + f.y() + " " + f.z();
					copiedAt = System.currentTimeMillis();
					Minecraft.getInstance().keyboardHandler.setClipboard(copied);
					Rows.playClick();
					return true;
				}
			}
			dragging = true;
			dragStartX = e.x();
			dragStartY = e.y();
			dragCX = centerX;
			dragCZ = centerZ;
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			centerX = dragCX - (e.x() - dragStartX) / CELL * blocksPerCell;
			centerZ = dragCZ - (e.y() - dragStartY) / CELL * blocksPerCell;
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging) {
			dragging = false;
			refresh();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int old = blocksPerCell;
		blocksPerCell = sy > 0 ? Math.max(4, blocksPerCell / 2) : Math.min(256, blocksPerCell * 2);
		if (old != blocksPerCell) refresh();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		Minecraft mc = Minecraft.getInstance();
		if (e.key() == GLFW.GLFW_KEY_SPACE && mc.player != null) {
			centerX = mc.player.getX();
			centerZ = mc.player.getZ();
			refresh();
			return true;
		}
		if (e.key() == GLFW.GLFW_KEY_S) {
			showSlime = !showSlime;
			return true;
		}
		return super.keyPressed(e);
	}
}
