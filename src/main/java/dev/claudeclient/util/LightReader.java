package dev.claudeclient.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Reads the block-light data the server sent, straight from the light engine's section arrays. The server computes
 * light from the real blocks, so on many anti-xray / anti-freecam servers the light still shows hidden rooms and
 * light sources even when the blocks themselves are replaced with stone or air.
 * <p>
 * A light source is a cell no neighbour is brighter than: light only spreads by dropping at least one level per
 * block, so any cell that isn't a source has a neighbour exactly one brighter. The source's level is its brightness.
 */
public final class LightReader {
	private static final DataLayer DARK = new DataLayer(0);

	private final ClientLevel level;
	private final LayerLightEventListener blockLight;
	private final Long2ObjectOpenHashMap<DataLayer> cache = new Long2ObjectOpenHashMap<>();

	public LightReader(ClientLevel level) {
		this.level = level;
		this.blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
	}

	/** Light data for a section; an all-dark layer when the client has none (dark or unloaded). */
	public DataLayer section(int sx, int sy, int sz) {
		long key = SectionPos.asLong(sx, sy, sz);
		DataLayer d = cache.get(key);
		if (d == null) {
			d = blockLight.getDataLayerData(SectionPos.of(sx, sy, sz));
			if (d == null) d = DARK;
			cache.put(key, d);
		}
		return d;
	}

	public int get(int x, int y, int z) {
		return section(x >> 4, y >> 4, z >> 4).get(x & 15, y & 15, z & 15);
	}

	public boolean chunkLoaded(int x, int z) {
		return level.hasChunk(x >> 4, z >> 4);
	}

	/**
	 * The brightness of the light source at this cell, or 0 if it isn't one. Cells next to an unloaded chunk return 0,
	 * since the brighter neighbour might be on the far side.
	 */
	public int sourceLevel(int x, int y, int z) {
		int l = get(x, y, z);
		if (l == 0) return 0;
		if (!chunkLoaded(x + 1, z) || !chunkLoaded(x - 1, z) || !chunkLoaded(x, z + 1) || !chunkLoaded(x, z - 1)) return 0;
		if (get(x + 1, y, z) > l || get(x - 1, y, z) > l || get(x, y + 1, z) > l || get(x, y - 1, z) > l
				|| get(x, y, z + 1) > l || get(x, y, z - 1) > l) return 0;
		return l;
	}

	public void clear() {
		cache.clear();
	}
}
