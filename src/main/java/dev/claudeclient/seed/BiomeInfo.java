package dev.claudeclient.seed;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Names, map colours and tree types for vanilla biomes. */
public final class BiomeInfo {
	private static final Map<String, String> TREES = new HashMap<>();
	private static final Map<String, Integer> COLORS = new HashMap<>();
	/** Biomes the Biome Checker can search for (pretty name -> id path), in menu order. */
	public static final Map<String, String> SEARCHABLE = new LinkedHashMap<>();

	private static void b(String id, int color, String trees) {
		COLORS.put(id, 0xFF000000 | color);
		if (trees != null) TREES.put(id, trees);
	}

	static {
		b("plains", 0x8DB360, "Oak (scattered), bee nests");
		b("sunflower_plains", 0xB5DB88, "Oak (scattered), bee nests");
		b("snowy_plains", 0xFFFFFF, "Spruce (rare)");
		b("ice_spikes", 0xB4DCDC, "None");
		b("desert", 0xFA9418, "None (cactus, dead bushes)");
		b("swamp", 0x07F9B2, "Swamp oak with vines");
		b("mangrove_swamp", 0x67352B, "Mangrove");
		b("forest", 0x056621, "Oak, Birch");
		b("flower_forest", 0x2D8E49, "Oak, Birch, bee nests");
		b("birch_forest", 0x307444, "Birch");
		b("old_growth_birch_forest", 0x589C6C, "Tall birch");
		b("dark_forest", 0x40511A, "Dark oak, Oak, Birch, huge mushrooms");
		b("pale_garden", 0xA9AFA9, "Pale oak (creaking hearts)");
		b("taiga", 0x0B6659, "Spruce, Pine");
		b("snowy_taiga", 0x31554A, "Spruce, Pine");
		b("old_growth_pine_taiga", 0x596651, "Giant pine, Spruce");
		b("old_growth_spruce_taiga", 0x818E79, "Giant spruce, Spruce");
		b("windswept_hills", 0x606060, "Oak, Spruce (sparse)");
		b("windswept_gravelly_hills", 0x888888, "Oak, Spruce (sparse)");
		b("windswept_forest", 0x507050, "Oak, Spruce");
		b("windswept_savanna", 0xE5DA87, "Acacia, Oak");
		b("savanna", 0xBDB25F, "Acacia, Oak");
		b("savanna_plateau", 0xA79D64, "Acacia, Oak");
		b("jungle", 0x537B09, "Jungle (giant and small), Oak bushes, cocoa");
		b("sparse_jungle", 0x628B17, "Jungle, Oak");
		b("bamboo_jungle", 0x768E14, "Jungle, bamboo");
		b("badlands", 0xD94515, "None");
		b("eroded_badlands", 0xFF6D3D, "None");
		b("wooded_badlands", 0xB09765, "Oak");
		b("meadow", 0x60A445, "Single oak or birch with bee nests");
		b("cherry_grove", 0xFFB7D5, "Cherry");
		b("grove", 0x47875A, "Spruce");
		b("snowy_slopes", 0xC4C4C4, "None");
		b("frozen_peaks", 0xA0A0FF, "None");
		b("jagged_peaks", 0xDCDCDC, "None");
		b("stony_peaks", 0x7B8F74, "None");
		b("river", 0x0000FF, "None");
		b("frozen_river", 0xA0A0FF, "None");
		b("beach", 0xFADE55, "None");
		b("snowy_beach", 0xFAF0C0, "None");
		b("stony_shore", 0xA2A284, "None");
		b("warm_ocean", 0x0000AC, "None");
		b("lukewarm_ocean", 0x000090, "None");
		b("deep_lukewarm_ocean", 0x000040, "None");
		b("ocean", 0x000070, "None");
		b("deep_ocean", 0x000030, "None");
		b("cold_ocean", 0x202070, "None");
		b("deep_cold_ocean", 0x202038, "None");
		b("frozen_ocean", 0x7070D6, "None");
		b("deep_frozen_ocean", 0x404090, "None");
		b("mushroom_fields", 0xFF00FF, "Huge mushrooms");
		b("dripstone_caves", 0x866043, null);
		b("lush_caves", 0x283C00, "Azalea trees on the surface above");
		b("deep_dark", 0x0B1517, null);
		b("nether_wastes", 0xBF3B3B, "None");
		b("crimson_forest", 0xDD0808, "Crimson fungus");
		b("warped_forest", 0x49907B, "Warped fungus");
		b("soul_sand_valley", 0x5E3830, "None");
		b("basalt_deltas", 0x403636, "None");
		b("the_end", 0x8080FF, "None");
		b("end_highlands", 0xB5B536, "Chorus plants");
		b("end_midlands", 0xFFFF99, "Chorus plants");
		b("small_end_islands", 0x4B4BAB, "None");
		b("end_barrens", 0x7070CC, "None");

		for (String id : new String[]{"mushroom_fields", "cherry_grove", "pale_garden", "jungle", "bamboo_jungle", "badlands", "eroded_badlands",
				"wooded_badlands", "ice_spikes", "mangrove_swamp", "swamp", "dark_forest", "flower_forest", "sunflower_plains", "birch_forest",
				"old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "meadow", "savanna", "desert", "snowy_taiga",
				"taiga", "forest", "plains", "snowy_plains", "grove", "jagged_peaks", "frozen_peaks", "stony_peaks", "warm_ocean",
				"deep_dark", "lush_caves", "dripstone_caves", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"}) {
			SEARCHABLE.put(name(id), id);
		}
	}

	public static String id(Holder<Biome> biome) {
		return biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
	}

	public static String name(String id) {
		String key = "biome.minecraft." + id;
		String s = Component.translatable(key).getString();
		if (!s.equals(key)) return s;
		StringBuilder sb = new StringBuilder();
		for (String w : id.split("_")) if (!w.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		return sb.toString();
	}

	public static String name(Holder<Biome> biome) {
		return name(id(biome));
	}

	public static String trees(Holder<Biome> biome) {
		return TREES.getOrDefault(id(biome), "None");
	}

	public static int color(Holder<Biome> biome) {
		Integer c = COLORS.get(id(biome));
		if (c != null) return c;
		return 0xFF000000 | (id(biome).hashCode() & 0x7F7F7F) + 0x303030;
	}

	public static boolean isCave(String id) {
		return id.equals("deep_dark") || id.equals("lush_caves") || id.equals("dripstone_caves");
	}

	private BiomeInfo() {}
}
