package dev.claudeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/** A keyboard key (GLFW code), or -1 for none. Set in the menu by clicking the row and pressing a key. */
public final class KeybindSetting extends Setting<Integer> {
	public KeybindSetting(String name, String description, int defaultKey) {
		super(name, description, defaultKey);
	}

	public boolean isBound() {
		return value != GLFW.GLFW_KEY_UNKNOWN;
	}

	public String keyName() {
		if (!isBound()) return "None";
		return InputConstants.Type.KEYSYM.getOrCreate(value).getDisplayName().getString();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) value = json.getAsInt();
	}
}
