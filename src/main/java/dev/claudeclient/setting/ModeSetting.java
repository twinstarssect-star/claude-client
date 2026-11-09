package dev.claudeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

public final class ModeSetting extends Setting<String> {
	private final List<String> modes;

	public ModeSetting(String name, String description, String defaultValue, String... modes) {
		super(name, description, defaultValue);
		this.modes = List.of(modes);
	}

	public List<String> modes() {
		return modes;
	}

	public boolean is(String mode) {
		return value.equals(mode);
	}

	/** Steps to the next (dir = 1) or previous (dir = -1) mode, wrapping around. */
	public void cycle(int dir) {
		int i = Math.max(0, modes.indexOf(value));
		value = modes.get(Math.floorMod(i + dir, modes.size()));
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive() && modes.contains(json.getAsString())) value = json.getAsString();
	}
}
