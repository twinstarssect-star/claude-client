package dev.claudeclient.seed;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** Every ore the seed engine reports, matched by exact block (stone and deepslate variants alike). */
public enum OreKind {
	DIAMOND("Diamond", 0xFF4FE3E8, true, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
	ANCIENT_DEBRIS("Ancient Debris", 0xFFB0623A, true, Blocks.ANCIENT_DEBRIS),
	EMERALD("Emerald", 0xFF2EE66B, true, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
	GOLD("Gold", 0xFFFFD233, true, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
	IRON("Iron", 0xFFE0A986, false, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
	REDSTONE("Redstone", 0xFFFF3B3B, false, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
	LAPIS("Lapis", 0xFF3D63FF, false, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
	COPPER("Copper", 0xFFE8794E, false, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
	COAL("Coal", 0xFF5A5A5A, false, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
	RAW_IRON("Raw Iron Block (ore vein)", 0xFFD8A27C, false, Blocks.RAW_IRON_BLOCK),
	RAW_COPPER("Raw Copper Block (ore vein)", 0xFFD0704A, false, Blocks.RAW_COPPER_BLOCK),
	NETHER_GOLD("Nether Gold", 0xFFFFE680, false, Blocks.NETHER_GOLD_ORE),
	QUARTZ("Nether Quartz", 0xFFF2EEE6, false, Blocks.NETHER_QUARTZ_ORE);

	public final String label;
	public final int color;
	public final boolean defaultOn;
	private final Block[] blocks;

	private static final Map<Block, OreKind> BY_BLOCK = new IdentityHashMap<>();

	static {
		for (OreKind k : values()) for (Block b : k.blocks) BY_BLOCK.put(b, k);
	}

	OreKind(String label, int color, boolean defaultOn, Block... blocks) {
		this.label = label;
		this.color = color;
		this.defaultOn = defaultOn;
		this.blocks = blocks;
	}

	public static OreKind of(BlockState state) {
		return BY_BLOCK.get(state.getBlock());
	}

	public static boolean isOre(BlockState state) {
		return BY_BLOCK.containsKey(state.getBlock());
	}
}
