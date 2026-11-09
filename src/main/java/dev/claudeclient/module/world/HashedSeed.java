package dev.claudeclient.module.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.claudeclient.Chat;
import dev.claudeclient.ClaudeClient;
import dev.claudeclient.Config;
import dev.claudeclient.Theme;
import dev.claudeclient.gui.Row;
import dev.claudeclient.gui.Rows;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.ModeSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the hashed seed straight out of the login and respawn packets (see ClientPacketListenerMixin) and lets you copy
 * it: a click-to-copy chat line, Copy buttons in the menu, a keybind, "/claude seed copy" and an optional auto-copy.
 * Every seed you see is also kept in config/claudeclient-seeds.json so you can copy it again later.
 */
public final class HashedSeed extends Module {
	public record Capture(String server, String dimension, long seed, long time) {}

	private static final int MAX_HISTORY = 50;
	private static final long COPIED_FLASH_MS = 1500;
	private static HashedSeed instance;

	private final BoolSetting chat = add(new BoolSetting("Chat Message",
			"Posts the seed in chat with a [Copy] button when you join or enter a dimension with a new seed.", true));
	private final BoolSetting autoCopy = add(new BoolSetting("Auto Copy",
			"Puts the seed on your clipboard the moment the server sends it.", false));
	private final BoolSetting showHud = add(new BoolSetting("Show On HUD",
			"Shows the current hashed seed in the top-left corner.", true));
	private final ModeSetting format = add(new ModeSetting("Format",
			"Decimal is what seed tools expect. Hex is the same 64 bits written in base 16.", "Decimal", "Decimal", "Hex"));

	/** dimension id -> hashed seed, for the server you're on right now. */
	private final Map<String, Long> session = new LinkedHashMap<>();
	/** Everything seen, newest first. */
	private final List<Capture> history = new ArrayList<>();
	private String server = "";
	private String dimension;
	private String copiedKey;
	private long copiedAt;

	public HashedSeed() {
		super("Hashed Seed", "Grabs the hashed seed the server sends when you join, ready to copy. It's a SHA-256 "
				+ "hash of the world seed, not the seed itself.", Category.SEED, true);
		instance = this;
	}

	public static HashedSeed get() {
		return instance;
	}

	// ---------------------------------------------------------------- capture

	/** Called on the game thread for every login and respawn packet. */
	public void onSpawnInfo(String dim, long seed, boolean login) {
		if (login) {
			session.clear();
			server = serverLabel();
		}
		boolean newValue = !session.containsValue(seed);
		session.put(dim, seed);
		dimension = dim;
		if (!isEnabled()) return;
		remember(dim, seed);
		// Respawning, or a dimension that shares the seed you already got: nothing new to say.
		if (!newValue) return;
		if (autoCopy.get()) copy(seed, liveKey(dim, seed));
		if (chat.get()) announce(dim, seed);
	}

	public void onDisconnect() {
		session.clear();
		dimension = null;
	}

	@Override
	protected void onEnable() {
		// Turned on mid-game: the seed from this join is still known, so file it.
		for (Map.Entry<String, Long> e : session.entrySet()) remember(e.getKey(), e.getValue());
	}

	private void remember(String dim, long seed) {
		Capture c = new Capture(server, dim, seed, System.currentTimeMillis());
		history.removeIf(h -> h.server().equals(c.server()) && h.dimension().equals(c.dimension()) && h.seed() == c.seed());
		history.add(0, c);
		while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
		saveHistory();
	}

	public static String serverLabel() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) return "Singleplayer: " + sp.getWorldData().getLevelName();
		ServerData sd = mc.getCurrentServer();
		if (sd != null) return sd.ip;
		return "Unknown server";
	}

	// ---------------------------------------------------------------- copy

	public String format(long seed) {
		return format.is("Hex") ? String.format("%016x", seed) : Long.toString(seed);
	}

	public Long currentSeed() {
		return dimension == null ? null : session.get(dimension);
	}

	public void copy(long seed, String key) {
		Minecraft.getInstance().keyboardHandler.setClipboard(format(seed));
		copiedKey = key;
		copiedAt = System.currentTimeMillis();
	}

	public boolean justCopied(String key) {
		return key != null && key.equals(copiedKey) && System.currentTimeMillis() - copiedAt < COPIED_FLASH_MS;
	}

	/** Keybind and "/claude seed copy". */
	public void copyCurrent() {
		Long seed = currentSeed();
		if (seed == null) {
			Chat.info("No hashed seed yet. Join a world or server first.");
			return;
		}
		copy(seed, liveKey(dimension, seed));
		Chat.send(Chat.colored("Copied hashed seed ", Theme.SUB).append(Chat.colored(format(seed), Theme.TEXT)));
	}

	/** "/claude seed": prints every seed from this join, each with its own [Copy]. */
	public void printSeeds() {
		if (session.isEmpty()) {
			Chat.info("No hashed seed yet. Join a world or server first.");
			return;
		}
		for (Map.Entry<String, Long> e : session.entrySet()) announce(e.getKey(), e.getValue());
	}

	private void announce(String dim, long seed) {
		String value = format(seed);
		MutableComponent line = Chat.colored("Hashed seed (" + shortDim(dim) + "): ", Theme.SUB)
				.append(copyable(value, Chat.colored(value, Theme.TEXT)))
				.append(Component.literal(" "))
				.append(copyable(value, Component.literal("[Copy]").withStyle(s -> s.withColor(Theme.rgb(Theme.ACCENT)).withBold(true))));
		if (seed == 0) line.append(Chat.colored("  0 usually means the server hides it", Theme.SUB));
		Chat.send(line);
	}

	private static MutableComponent copyable(String value, MutableComponent text) {
		return text.withStyle(s -> s.withClickEvent(new ClickEvent.CopyToClipboard(value))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy " + value))));
	}

	private static String liveKey(String dim, long seed) {
		return "live|" + dim + "|" + seed;
	}

	public static String shortDim(String dim) {
		return dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
	}

	private static String ago(long time) {
		long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		if (s < 60) return "just now";
		long m = s / 60;
		if (m < 60) return m + "m ago";
		long h = m / 60;
		if (h < 24) return h + "h ago";
		return (h / 24) + "d ago";
	}

	// ---------------------------------------------------------------- HUD + menu

	@Override
	public List<HudLine> hudLines() {
		Long seed = currentSeed();
		if (!showHud.get() || seed == null) return List.of();
		return List.of(new HudLine("Hashed Seed", format(seed)));
	}

	/** The seed(s) from this join sit right under the title, so Copy is visible without scrolling. */
	@Override
	public List<Row> headerRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("This world"));
		if (session.isEmpty()) {
			rows.add(new Rows.Text("Nothing yet. Join a world or server and its hashed seed shows up here.", Theme.SUB, width));
			return rows;
		}
		for (Map.Entry<String, Long> e : session.entrySet()) {
			String dim = e.getKey();
			long seed = e.getValue();
			String key = liveKey(dim, seed);
			String meta = shortDim(dim) + (dim.equals(dimension) ? "  ·  you are here" : "") + (seed == 0 ? "  ·  hidden by server?" : "");
			rows.add(new Rows.Seed(format(seed), meta, () -> copy(seed, key), () -> justCopied(key)));
		}
		return rows;
	}

	@Override
	public List<Row> extraRows(int width) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Rows.Header("History"));
		if (history.isEmpty()) {
			rows.add(new Rows.Text("Every seed you see is saved here so you can copy it again later.", Theme.SUB, width));
		} else {
			for (Capture c : history) {
				String key = "hist|" + c.server() + "|" + c.dimension() + "|" + c.seed();
				String meta = c.server() + "  ·  " + shortDim(c.dimension()) + "  ·  " + ago(c.time());
				rows.add(new Rows.Seed(format(c.seed()), meta, () -> copy(c.seed(), key), () -> justCopied(key)));
			}
			rows.add(new Rows.Button("Clear history", () -> {
				history.clear();
				saveHistory();
			}));
		}
		return rows;
	}

	// ---------------------------------------------------------------- history file

	private static Path historyFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("claudeclient-seeds.json");
	}

	public void loadHistory() {
		Path f = historyFile();
		if (!Files.exists(f)) return;
		try {
			JsonArray arr = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
			history.clear();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				history.add(new Capture(o.get("server").getAsString(), o.get("dimension").getAsString(),
						Long.parseLong(o.get("seed").getAsString()), o.get("time").getAsLong()));
				if (history.size() >= MAX_HISTORY) break;
			}
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't read {}", f, e);
		}
	}

	private void saveHistory() {
		JsonArray arr = new JsonArray();
		for (Capture c : history) {
			JsonObject o = new JsonObject();
			o.addProperty("server", c.server());
			o.addProperty("dimension", c.dimension());
			o.addProperty("seed", Long.toString(c.seed())); // a string, so no tool ever rounds it through a double
			o.addProperty("time", c.time());
			arr.add(o);
		}
		try {
			Files.writeString(historyFile(), Config.GSON.toJson(arr));
		} catch (Exception e) {
			ClaudeClient.LOG.warn("Couldn't save seed history", e);
		}
	}
}
