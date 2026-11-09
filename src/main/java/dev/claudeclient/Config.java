package dev.claudeclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Module on/off state and settings, in config/claudeclient.json. */
public final class Config {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("claudeclient.json");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) return;
		try {
			JsonObject modules = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("modules");
			if (modules == null) return;
			for (Module m : ClaudeClient.MODULES.all()) {
				JsonElement el = modules.get(m.name());
				if (el == null || !el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) m.setEnabledSilently(o.get("enabled").getAsBoolean());
				JsonElement settings = o.get("settings");
				if (settings == null || !settings.isJsonObject()) continue;
				for (Setting<?> s : m.settings()) s.fromJson(settings.getAsJsonObject().get(s.name()));
			}
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't read {}, using defaults", f, e);
		}
	}

	public static void save() {
		JsonObject modules = new JsonObject();
		for (Module m : ClaudeClient.MODULES.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.isEnabled());
			JsonObject settings = new JsonObject();
			for (Setting<?> s : m.settings()) settings.add(s.name(), s.toJson());
			o.add("settings", settings);
			modules.add(m.name(), o);
		}
		JsonObject root = new JsonObject();
		root.add("modules", modules);
		try {
			Files.writeString(file(), GSON.toJson(root));
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't save config", e);
		}
	}

	private Config() {}
}
