package dev.claudeclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** One line in the menu's right-hand pane. The screen lays rows out top to bottom and scrolls them. */
public interface Row {
	int height();

	void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean hovered);

	/** Called when the row is clicked. Returns true if the click did something. */
	default boolean click(int button, double mouseX, double mouseY, int x, int y, int w) {
		return false;
	}

	/** Hover text, or null. */
	default String tooltip() {
		return null;
	}
}
