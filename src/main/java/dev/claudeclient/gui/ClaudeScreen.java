package dev.claudeclient.gui;

import dev.claudeclient.ClaudeClient;
import dev.claudeclient.Config;
import dev.claudeclient.Theme;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.KeybindSetting;
import dev.claudeclient.setting.ModeSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.setting.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The client menu: modules grouped by category on the left (scrolls), the selected module's switch, description,
 * settings and extra rows on the right (scrolls). Right-click a module to toggle it.
 */
public final class ClaudeScreen extends Screen {
	private static final int SIDEBAR_W = 122, HEADER_H = 22, PAD = 10, ROW_GAP = 2;

	private static Module selected;
	private static double scroll, sideScroll;

	private record Placed(Row row, int x, int y, int w, int h) {}

	private record SideEntry(Module module, int y) {}

	private final List<Placed> placed = new ArrayList<>();
	private final List<SideEntry> side = new ArrayList<>();
	private int px, py, pw, ph;
	private Rows.Slider dragging;
	private int dragX, dragW;
	private KeybindSetting listening;

	public ClaudeScreen() {
		super(Component.literal("Claude Client"));
	}

	@Override
	protected void init() {
		pw = Math.min(460, width - 16);
		ph = Math.min(300, height - 16);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		if (selected == null && !ClaudeClient.MODULES.all().isEmpty()) selected = ClaudeClient.MODULES.all().get(0);
	}

	/** Opens the menu on a module. */
	public static ClaudeScreen on(Module m) {
		if (m != selected) scroll = 0;
		selected = m;
		return new ClaudeScreen();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		Config.save();
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0x99000000);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);

		g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, Theme.BORDER);
		g.fill(px, py, px + pw, py + ph, Theme.PANEL);
		g.fill(px, py, px + pw, py + HEADER_H, Theme.SIDEBAR);
		g.fill(px, py + HEADER_H - 1, px + pw, py + HEADER_H, Theme.BORDER);
		g.fill(px + 8, py + 8, px + 14, py + 14, Theme.ACCENT);
		g.drawString(font, "Claude Client", px + 19, py + 7, Theme.TEXT, false);
		String ver = "v" + ClaudeClient.VERSION;
		g.drawString(font, ver, px + pw - 8 - font.width(ver), py + 7, Theme.SUB, false);

		renderSidebar(g, mx, my);
		renderContent(g, mx, my);
	}

	private void renderSidebar(GuiGraphics g, int mx, int my) {
		int top = py + HEADER_H, bottom = py + ph - 14;
		g.fill(px, top, px + SIDEBAR_W, py + ph, Theme.SIDEBAR);
		g.fill(px + SIDEBAR_W, top, px + SIDEBAR_W + 1, py + ph, Theme.BORDER);

		int total = 8;
		for (Category c : Category.values()) {
			int n = ClaudeClient.MODULES.byCategory(c).size();
			if (n > 0) total += 13 + n * 16 + 6;
		}
		double maxSide = Math.max(0, total - (bottom - top));
		sideScroll = Math.max(0, Math.min(sideScroll, maxSide));

		side.clear();
		g.enableScissor(px, top, px + SIDEBAR_W, bottom);
		int y = top + 8 - (int) sideScroll;
		boolean inSide = inside(mx, my, px, top, SIDEBAR_W, bottom - top);
		for (Category c : Category.values()) {
			List<Module> mods = ClaudeClient.MODULES.byCategory(c);
			if (mods.isEmpty()) continue;
			g.drawString(font, c.label.toUpperCase(), px + 8, y, Theme.SUB, false);
			y += 13;
			for (Module m : mods) {
				boolean sel = m == selected;
				boolean hov = inSide && inside(mx, my, px, y - 3, SIDEBAR_W, 15);
				if (sel) {
					g.fill(px + 3, y - 3, px + SIDEBAR_W - 3, y + 12, Theme.ROW_SELECTED);
					g.fill(px + 3, y - 3, px + 5, y + 12, Theme.ACCENT);
				} else if (hov) {
					g.fill(px + 3, y - 3, px + SIDEBAR_W - 3, y + 12, Theme.ROW_HOVER);
				}
				g.drawString(font, Rows.fit(font, m.name(), SIDEBAR_W - 30), px + 10, y + 1, m.isEnabled() ? Theme.TEXT : Theme.SUB, false);
				int dx = px + SIDEBAR_W - 14;
				g.fill(dx, y + 2, dx + 5, y + 7, m.isEnabled() ? Theme.ACCENT : Theme.OFF);
				side.add(new SideEntry(m, y - 3));
				y += 16;
			}
			y += 6;
		}
		g.disableScissor();
		if (maxSide > 0) {
			int trackH = bottom - top - 4;
			int barH = Math.max(14, (int) (trackH * (bottom - top) / (double) total));
			int barY = top + 2 + (int) ((trackH - barH) * (sideScroll / maxSide));
			g.fill(px + SIDEBAR_W - 3, barY, px + SIDEBAR_W - 1, barY + barH, Theme.OFF);
		}
		g.drawString(font, "Right-click: toggle", px + 8, py + ph - 11, Theme.OFF, false);
	}

	private void renderContent(GuiGraphics g, int mx, int my) {
		placed.clear();
		if (selected == null) return;
		int top = py + HEADER_H;
		int paneX = px + SIDEBAR_W + 1, paneW = pw - SIDEBAR_W - 1, paneH = ph - HEADER_H;
		int cx = paneX + PAD, cw = paneW - PAD * 2, cy = top + 6, viewH = paneH - 10;
		List<Row> rows = buildRows(selected, cw);
		int contentH = 0;
		for (Row r : rows) contentH += r.height() + ROW_GAP;
		double maxScroll = Math.max(0, contentH - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		boolean mouseInPane = inside(mx, my, paneX, top, paneW, paneH);
		Row hoveredRow = null;
		g.enableScissor(paneX, top, paneX + paneW, py + ph - 1);
		int ry = cy - (int) scroll;
		for (Row r : rows) {
			int h = r.height();
			boolean hov = mouseInPane && my >= ry && my < ry + h && mx >= cx - 4 && mx < cx + cw + 4;
			if (ry + h >= top && ry <= py + ph) r.render(g, font, cx, ry, cw, mx, my, hov);
			placed.add(new Placed(r, cx, ry, cw, h));
			if (hov) hoveredRow = r;
			ry += h + ROW_GAP;
		}
		g.disableScissor();

		if (maxScroll > 0) {
			int trackTop = top + 3, trackH = paneH - 6;
			int barH = Math.max(16, trackH * viewH / contentH);
			int barY = trackTop + (int) ((trackH - barH) * (scroll / maxScroll));
			g.fill(px + pw - 4, barY, px + pw - 2, barY + barH, Theme.OFF);
		}

		if (hoveredRow != null && hoveredRow.tooltip() != null && dragging == null) {
			g.setTooltipForNextFrame(font.split(Component.literal(hoveredRow.tooltip()), 220), mx, my);
		}
	}

	private List<Row> buildRows(Module m, int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Title(m));
		rows.add(new Rows.Text(m.description(), Theme.SUB, width));
		rows.addAll(m.headerRows(width));
		List<Setting<?>> settings = m.settings();
		boolean header = false;
		for (Setting<?> s : settings) {
			if (!m.isSettingVisible(s)) continue;
			if (!header) {
				rows.add(new Rows.Header("Settings"));
				header = true;
			}
			if (s instanceof BoolSetting b) rows.add(new Rows.Toggle(b));
			else if (s instanceof ModeSetting mode) rows.add(new Rows.Mode(mode));
			else if (s instanceof NumberSetting n) rows.add(new Rows.Slider(n));
			else if (s instanceof KeybindSetting k) rows.add(new Rows.Bind(k, () -> listening == k));
		}
		rows.addAll(m.extraRows(width));
		return rows;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x(), my = e.y();
		int button = e.button();
		listening = null;
		if (inside(mx, my, px, py + HEADER_H, SIDEBAR_W, ph - HEADER_H - 14)) {
			for (SideEntry s : side) {
				if (!inside(mx, my, px, s.y(), SIDEBAR_W, 15)) continue;
				// Right-click, or a click on the status dot, toggles; a left click selects.
				if (button == 1 || mx >= px + SIDEBAR_W - 20) {
					s.module().toggle();
				} else if (s.module() != selected) {
					selected = s.module();
					scroll = 0;
				}
				Rows.playClick();
				return true;
			}
		}
		if (inside(mx, my, px + SIDEBAR_W + 1, py + HEADER_H, pw - SIDEBAR_W - 1, ph - HEADER_H)) {
			for (Placed p : placed) {
				if (my >= p.y() && my < p.y() + p.h() && mx >= p.x() - 4 && mx < p.x() + p.w() + 4) {
					if (p.row() instanceof Rows.Bind bind && button == 0) {
						listening = bind.setting();
						Rows.playClick();
						return true;
					}
					if (p.row() instanceof Rows.Slider slider && button == 0) {
						dragging = slider;
						dragX = p.x();
						dragW = p.w();
						slider.drag(mx, dragX, dragW);
						return true;
					}
					if (p.row().click(button, mx, my, p.x(), p.y(), p.w())) return true;
					break;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging != null) {
			dragging.drag(e.x(), dragX, dragW);
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging != null) {
			dragging = null;
			Rows.playClick();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (inside(mx, my, px, py + HEADER_H, SIDEBAR_W, ph - HEADER_H)) {
			sideScroll -= scrollY * 16;
			return true;
		}
		if (inside(mx, my, px + SIDEBAR_W + 1, py + HEADER_H, pw - SIDEBAR_W - 1, ph - HEADER_H)) {
			scroll -= scrollY * 16;
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (listening != null) {
			int key = e.key();
			listening.set(key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? GLFW.GLFW_KEY_UNKNOWN : key);
			listening = null;
			Rows.playClick();
			return true;
		}
		if (ClaudeClient.menuKey != null && ClaudeClient.menuKey.matches(e)) {
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
