package dev.claudeclient.module.client;

import dev.claudeclient.ClaudeClient;
import dev.claudeclient.Theme;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Watermark (top-left), module info lines under it, and the enabled-module list (top-right). */
public final class Hud extends Module {
	private static Hud instance;

	private final BoolSetting watermark = add(new BoolSetting("Watermark", "Shows \"Claude Client\" in the top-left corner.", true));
	private final BoolSetting moduleList = add(new BoolSetting("Module List", "Lists your enabled modules in the top-right corner.", true));

	public Hud() {
		super("HUD", "The on-screen overlay: watermark, module list, and info lines such as the hashed seed.", Category.CLIENT, true);
		instance = this;
	}

	public static void renderAll(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (instance == null || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
		Font font = mc.font;
		boolean on = instance.isEnabled();
		int y = 5;

		if (on && instance.watermark.get()) {
			String title = "Claude Client";
			String ver = " " + ClaudeClient.VERSION;
			int w = font.width(title) + font.width(ver) + 10;
			g.fill(2, 2, 2 + w, 15, Theme.HUD_BG);
			g.fill(2, 2, 4, 15, Theme.ACCENT);
			g.drawString(font, title, 8, 5, Theme.ACCENT, false);
			g.drawString(font, ver, 8 + font.width(title), 5, Theme.SUB, false);
			y = 20;
		}

		// Info lines show even with the HUD module off; each module has its own "Show On HUD" switch.
		for (Module m : ClaudeClient.MODULES.all()) {
			if (!m.isEnabled()) continue;
			for (HudLine line : m.hudLines()) {
				String label = line.label() + ": ";
				int lw = font.width(label);
				g.fill(2, y - 3, 2 + lw + font.width(line.value()) + 8, y + 10, Theme.HUD_BG);
				g.drawString(font, label, 6, y, Theme.SUB, false);
				g.drawString(font, line.value(), 6 + lw, y, Theme.TEXT, false);
				y += 14;
			}
		}

		if (on && instance.moduleList.get()) {
			List<String[]> names = new ArrayList<>();
			for (Module m : ClaudeClient.MODULES.all()) {
				if (!m.isEnabled() || m == instance) continue;
				String info = m.hudInfo();
				names.add(new String[]{m.name(), info == null ? "" : " " + info});
			}
			names.sort(Comparator.comparingInt((String[] n) -> font.width(n[0]) + font.width(n[1])).reversed());
			int right = g.guiWidth() - 2;
			int ry = 5;
			for (String[] n : names) {
				int w = font.width(n[0]) + font.width(n[1]);
				int x = right - 6 - w;
				g.fill(x - 4, ry - 3, right, ry + 10, Theme.HUD_BG);
				g.fill(right - 2, ry - 3, right, ry + 10, Theme.ACCENT);
				g.drawString(font, n[0], x, ry, Theme.TEXT, false);
				if (!n[1].isEmpty()) g.drawString(font, n[1], x + font.width(n[0]), ry, Theme.SUB, false);
				ry += 13;
			}
		}

		String toast = ClaudeClient.toastText();
		if (toast != null) {
			float age = ClaudeClient.toastAge();
			int alpha = (int) (255 * Math.min(1f, (1f - age) * 4f));
			if (alpha > 8) {
				int w = font.width(toast) + 16, x = (g.guiWidth() - w) / 2, ty = g.guiHeight() / 2 + 24;
				g.fill(x, ty, x + w, ty + 16, (Math.min(alpha, 0xC0) << 24) | 0x161514);
				g.fill(x, ty, x + 2, ty + 16, (alpha << 24) | (Theme.ACCENT & 0xFFFFFF));
				g.drawString(font, toast, x + 9, ty + 4, (alpha << 24) | (Theme.TEXT & 0xFFFFFF), false);
			}
		}
	}
}
