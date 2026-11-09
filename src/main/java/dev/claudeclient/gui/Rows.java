package dev.claudeclient.gui;

import dev.claudeclient.Theme;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.KeybindSetting;
import dev.claudeclient.setting.ModeSetting;
import dev.claudeclient.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BooleanSupplier;

/** The row types the menu uses. */
public final class Rows {
	static void playClick() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
	}

	private static String flashKey;
	private static long flashAt;

	/** A row with a value and a Copy button that copies the value and flashes "Copied!". */
	public static Seed copy(String value, String meta) {
		String key = value + "|" + meta;
		return new Seed(value, meta, () -> {
			Minecraft.getInstance().keyboardHandler.setClipboard(value);
			flashKey = key;
			flashAt = System.currentTimeMillis();
		}, () -> key.equals(flashKey) && System.currentTimeMillis() - flashAt < 1500);
	}

	/** 18x10 on/off switch. */
	static void drawSwitch(GuiGraphics g, int x, int y, boolean on) {
		g.fill(x, y, x + 18, y + 10, on ? Theme.ACCENT_DIM : Theme.OFF);
		int kx = on ? x + 9 : x + 1;
		g.fill(kx, y + 1, kx + 8, y + 9, on ? Theme.ACCENT : Theme.SUB);
	}

	static String fit(Font font, String s, int width) {
		if (font.width(s) <= width) return s;
		return font.plainSubstrByWidth(s, Math.max(0, width - font.width("..."))) + "...";
	}

	/** Module name with its on/off switch. */
	public record Title(Module module) implements Row {
		@Override
		public int height() {
			return 16;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			g.drawString(font, module.name(), x, y + 4, Theme.ACCENT, false);
			String state = module.isEnabled() ? "On" : "Off";
			g.drawString(font, state, x + w - 22 - font.width(state), y + 4, module.isEnabled() ? Theme.TEXT : Theme.SUB, false);
			drawSwitch(g, x + w - 18, y + 3, module.isEnabled());
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			if (mx < x + w - 40) return false;
			module.toggle();
			playClick();
			return true;
		}

		@Override
		public String tooltip() {
			return "Turn " + module.name() + (module.isEnabled() ? " off" : " on");
		}
	}

	/** Wrapped paragraph. */
	public static final class Text implements Row {
		private final List<FormattedCharSequence> lines;
		private final int color;

		public Text(String text, int color, int width) {
			this.lines = Minecraft.getInstance().font.split(Component.literal(text), Math.max(40, width));
			this.color = color;
		}

		@Override
		public int height() {
			return lines.size() * 10 + 2;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * 10, color, false);
		}
	}

	/** A label on the left and a coloured value on the right. */
	public record Info(String label, String value, int color) implements Row {
		@Override
		public int height() {
			return 14;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			g.drawString(font, label, x, y + 3, Theme.SUB, false);
			String v = fit(font, value, Math.max(20, w - font.width(label) - 8));
			g.drawString(font, v, x + w - font.width(v), y + 3, color, false);
		}
	}

	/** Small section label with a rule after it. */
	public record Header(String label) implements Row {
		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			String s = label.toUpperCase();
			g.drawString(font, s, x, y + 7, Theme.SUB, false);
			int tw = font.width(s);
			g.fill(x + tw + 6, y + 11, x + w, y + 12, Theme.BORDER);
		}
	}

	public record Toggle(BoolSetting setting) implements Row {
		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			if (hovered) g.fill(x - 4, y, x + w + 4, y + 18, Theme.ROW_HOVER);
			g.drawString(font, setting.name(), x, y + 5, Theme.TEXT, false);
			drawSwitch(g, x + w - 18, y + 4, setting.get());
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			setting.toggle();
			playClick();
			return true;
		}

		@Override
		public String tooltip() {
			return setting.description();
		}
	}

	public record Mode(ModeSetting setting) implements Row {
		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			if (hovered) g.fill(x - 4, y, x + w + 4, y + 18, Theme.ROW_HOVER);
			g.drawString(font, setting.name(), x, y + 5, Theme.TEXT, false);
			String v = "< " + setting.get() + " >";
			g.drawString(font, v, x + w - font.width(v), y + 5, Theme.ACCENT, false);
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			setting.cycle(button == 1 ? -1 : 1);
			playClick();
			return true;
		}

		@Override
		public String tooltip() {
			return setting.description() + " Left click: next, right click: back.";
		}
	}

	/** A seed value with a Copy button. The whole row is clickable. */
	public record Seed(String value, String meta, Runnable onCopy, BooleanSupplier copied) implements Row {
		private static final int BUTTON_W = 46;

		@Override
		public int height() {
			return 26;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			g.fill(x - 4, y, x + w + 4, y + 26, hovered ? Theme.ROW_HOVER : 0x18FFFFFF);
			int textW = w - BUTTON_W - 8;
			g.drawString(font, fit(font, value, textW), x, y + 4, Theme.TEXT, false);
			g.drawString(font, fit(font, meta, textW), x, y + 15, Theme.SUB, false);

			boolean done = copied.getAsBoolean();
			int bx = x + w - BUTTON_W, by = y + 6;
			int edge = done ? Theme.GOOD : hovered ? Theme.ACCENT : Theme.BORDER;
			g.fill(bx, by, bx + BUTTON_W, by + 14, edge);
			g.fill(bx + 1, by + 1, bx + BUTTON_W - 1, by + 13, hovered && !done ? Theme.ACCENT_DIM : Theme.PANEL);
			String label = done ? "Copied!" : "Copy";
			g.drawString(font, label, bx + (BUTTON_W - font.width(label)) / 2, by + 3, done ? Theme.GOOD : Theme.TEXT, false);
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			if (button != 0) return false;
			onCopy.run();
			playClick();
			return true;
		}
		// No tooltip: it would cover the seed, and the Copy button already says what a click does.
	}

	/** A number slider: click or drag along the bar. */
	public record Slider(NumberSetting setting) implements Row {
		@Override
		public int height() {
			return 22;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			if (hovered) g.fill(x - 4, y, x + w + 4, y + 22, Theme.ROW_HOVER);
			g.drawString(font, setting.name(), x, y + 3, Theme.TEXT, false);
			String v = setting.display();
			g.drawString(font, v, x + w - font.width(v), y + 3, Theme.ACCENT, false);
			int by = y + 15, fill = (int) Math.round(w * setting.fraction());
			g.fill(x, by, x + w, by + 3, Theme.OFF);
			g.fill(x, by, x + fill, by + 3, Theme.ACCENT);
			int kx = x + fill;
			g.fill(kx - 2, by - 2, kx + 2, by + 5, Theme.TEXT);
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			setting.setFraction((mx - x) / w);
			return true;
		}

		public void drag(double mx, int x, int w) {
			setting.setFraction((mx - x) / w);
		}

		@Override
		public String tooltip() {
			return setting.description();
		}
	}

	/** A keybind: click, then press a key. The screen does the listening. */
	public record Bind(KeybindSetting setting, BooleanSupplier listening) implements Row {
		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			if (hovered) g.fill(x - 4, y, x + w + 4, y + 18, Theme.ROW_HOVER);
			g.drawString(font, setting.name(), x, y + 5, Theme.TEXT, false);
			boolean wait = listening.getAsBoolean();
			String v = wait ? "Press a key..." : "[" + setting.keyName() + "]";
			g.drawString(font, v, x + w - font.width(v), y + 5, wait ? Theme.TEXT : setting.isBound() ? Theme.ACCENT : Theme.SUB, false);
		}

		@Override
		public String tooltip() {
			return setting.description();
		}
	}

	public record Button(String label, Runnable action) implements Row {
		@Override
		public int height() {
			return 18;
		}

		@Override
		public void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, boolean hovered) {
			int bw = font.width(label) + 16;
			g.fill(x, y + 2, x + bw, y + 16, hovered ? Theme.ACCENT : Theme.BORDER);
			g.fill(x + 1, y + 3, x + bw - 1, y + 15, hovered ? Theme.ACCENT_DIM : Theme.PANEL);
			g.drawString(font, label, x + 8, y + 5, Theme.TEXT, false);
		}

		@Override
		public boolean click(int button, double mx, double my, int x, int y, int w) {
			if (button != 0 || mx > x + Minecraft.getInstance().font.width(label) + 16) return false;
			action.run();
			playClick();
			return true;
		}
	}

	private Rows() {}
}
