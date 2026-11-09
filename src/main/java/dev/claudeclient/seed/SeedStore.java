package dev.claudeclient.seed;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.claudeclient.ClaudeClient;
import dev.claudeclient.Config;
import dev.claudeclient.module.world.HashedSeed;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.biome.BiomeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The real world seed per server (the server only ever sends a hash of it). Singleplayer reads it straight from the
 * built-in server. Every seed is checked against the server's hashed seed: SHA-256 can't be reversed, but a wrong seed
 * can't produce the right hash, so a match means the seed is right.
 */
public final class SeedStore {
	public enum Status {
		/** obfuscateSeed(seed) equals the hashed seed the server sent. */
		VERIFIED,
		/** It doesn't: this is not the seed of this world. */
		MISMATCH,
		/** The server sent no usable hash (0 or nothing yet), so it can't be checked. */
		UNCHECKED,
		NONE
	}

	private static final Map<String, Long> seeds = new HashMap<>();
	private static boolean loaded;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("claudeclient-worldseeds.json");
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			JsonObject o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : o.entrySet()) seeds.put(e.getKey(), Long.parseLong(e.getValue().getAsString()));
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't read world seeds", e);
		}
	}

	private static void save() {
		JsonObject o = new JsonObject();
		seeds.forEach((k, v) -> o.addProperty(k, Long.toString(v)));
		try {
			Files.writeString(file(), Config.GSON.toJson(o));
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't save world seeds", e);
		}
	}

	/** Vanilla's rule: a number is used as-is, any other text is hashed with String.hashCode(). */
	public static long parse(String text) {
		String t = text.trim();
		try {
			return Long.parseLong(t);
		} catch (NumberFormatException e) {
			return t.hashCode();
		}
	}

	public static String serverKey() {
		return HashedSeed.serverLabel();
	}

	public static boolean singleplayer() {
		return Minecraft.getInstance().getSingleplayerServer() != null;
	}

	/** The world seed for where you are now, if known. */
	public static OptionalLong current() {
		IntegratedServer sp = Minecraft.getInstance().getSingleplayerServer();
		if (sp != null) return OptionalLong.of(sp.getWorldData().worldGenOptions().seed());
		load();
		Long s = seeds.get(serverKey());
		return s == null ? OptionalLong.empty() : OptionalLong.of(s);
	}

	public static void set(long seed) {
		load();
		seeds.put(serverKey(), seed);
		save();
	}

	public static void clear() {
		load();
		seeds.remove(serverKey());
		save();
	}

	public static Status status() {
		OptionalLong seed = current();
		if (seed.isEmpty()) return Status.NONE;
		return check(seed.getAsLong());
	}

	public static Status check(long seed) {
		HashedSeed hs = HashedSeed.get();
		Long hashed = hs == null ? null : hs.currentSeed();
		if (hashed == null || hashed == 0L) return Status.UNCHECKED;
		return BiomeManager.obfuscateSeed(seed) == hashed ? Status.VERIFIED : Status.MISMATCH;
	}

	public static String describe(Status s) {
		return switch (s) {
			case VERIFIED -> "Verified (matches the server's hashed seed)";
			case MISMATCH -> "WRONG: doesn't match this server's hashed seed";
			case UNCHECKED -> "Can't verify (server hides its hashed seed)";
			case NONE -> "Not set";
		};
	}

	private SeedStore() {}
}
