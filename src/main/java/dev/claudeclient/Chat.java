package dev.claudeclient;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Client-side chat lines with the "[Claude]" prefix. Nothing here is ever sent to the server. */
public final class Chat {
	public static MutableComponent colored(String text, int argb) {
		return Component.literal(text).withStyle(s -> s.withColor(Theme.rgb(argb)));
	}

	public static void send(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addMessage(colored("[Claude] ", Theme.ACCENT).append(message));
	}

	public static void info(String text) {
		send(colored(text, Theme.TEXT));
	}

	private Chat() {}
}
