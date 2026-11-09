package dev.claudeclient.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {
	/** The seed engine drops each chunk's noise cache once carving is done, to keep memory down. */
	@Accessor("noiseChunk")
	void claudeclient$setNoiseChunk(NoiseChunk noiseChunk);
}
