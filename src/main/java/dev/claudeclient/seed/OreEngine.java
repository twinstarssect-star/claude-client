package dev.claudeclient.seed;

import dev.claudeclient.ClaudeClient;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Works out the ores around you from the seed, in the background. Terrain chunks are generated in parallel; features
 * are applied by one thread in a fixed order, because a chunk's features also write into its neighbours (ore veins
 * cross chunk borders). A chunk's ores are final once it and all 8 neighbours have been decorated.
 */
public final class OreEngine {
	public record Ore(long pos, OreKind kind) {}

	private final SeedWorld world;
	private final ExecutorService terrainPool;
	private final Thread coordinator;
	private final Map<Long, CompletableFuture<ProtoChunk>> terrain = new ConcurrentHashMap<>();
	private final LongOpenHashSet decorated = new LongOpenHashSet();
	private final Map<Long, List<Ore>> results = new ConcurrentHashMap<>();
	private final AtomicInteger generated = new AtomicInteger();
	private volatile int centerX, centerZ, radius;
	private volatile boolean running = true;
	private volatile String error;
	private volatile long msPerChunk;

	public OreEngine(SeedWorld world, int threads) {
		this.world = world;
		this.terrainPool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "Claude Seed Terrain");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		this.coordinator = new Thread(this::run, "Claude Seed Features");
		this.coordinator.setDaemon(true);
		this.coordinator.setPriority(Thread.MIN_PRIORITY + 1);
		this.coordinator.start();
	}

	public SeedWorld world() {
		return world;
	}

	public void setCenter(int cx, int cz, int radius) {
		this.centerX = cx;
		this.centerZ = cz;
		this.radius = radius;
	}

	public Map<Long, List<Ore>> results() {
		return results;
	}

	public int done() {
		return results.size();
	}

	public String error() {
		return error;
	}

	public long msPerChunk() {
		return msPerChunk;
	}

	public void stop() {
		running = false;
		coordinator.interrupt();
		terrainPool.shutdownNow();
		terrain.clear();
	}

	private CompletableFuture<ProtoChunk> terrain(int cx, int cz) {
		return terrain.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> CompletableFuture.supplyAsync(() -> {
			long t = System.nanoTime();
			ProtoChunk c = world.generateTerrain(cx, cz);
			generated.incrementAndGet();
			msPerChunk = (System.nanoTime() - t) / 1_000_000;
			return c;
		}, terrainPool));
	}

	/** Output chunks around the centre, nearest first, that don't have results yet. */
	private List<long[]> pending(int cx, int cz, int r) {
		List<long[]> out = new ArrayList<>();
		for (int ring = 0; ring <= r; ring++) {
			for (int dx = -ring; dx <= ring; dx++)
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
					if (!results.containsKey(ChunkPos.asLong(cx + dx, cz + dz))) out.add(new long[]{cx + dx, cz + dz});
				}
		}
		return out;
	}

	private void run() {
		long lastEvict = 0;
		while (running) {
			try {
				int cx = centerX, cz = centerZ, r = radius;
				List<long[]> todo = pending(cx, cz, r);
				if (System.currentTimeMillis() - lastEvict > 2000) {
					evict(cx, cz, r);
					lastEvict = System.currentTimeMillis();
				}
				if (todo.isEmpty()) {
					Thread.sleep(150);
					continue;
				}
				// Keep the terrain threads busy a few chunks ahead.
				for (int i = 0; i < Math.min(8, todo.size()); i++) {
					int ox = (int) todo.get(i)[0], oz = (int) todo.get(i)[1];
					for (int dx = -2; dx <= 2; dx++)
						for (int dz = -2; dz <= 2; dz++) terrain(ox + dx, oz + dz);
				}
				int ox = (int) todo.get(0)[0], oz = (int) todo.get(0)[1];
				for (int dz = -1; dz <= 1; dz++)
					for (int dx = -1; dx <= 1; dx++) {
						int nx = ox + dx, nz = oz + dz;
						long key = ChunkPos.asLong(nx, nz);
						if (decorated.contains(key)) continue;
						Map<Long, ProtoChunk> region = new HashMap<>();
						for (int ex = -1; ex <= 1; ex++)
							for (int ez = -1; ez <= 1; ez++) region.put(ChunkPos.asLong(nx + ex, nz + ez), terrain(nx + ex, nz + ez).join());
						world.decorate(region, region.get(key), world.oreStep());
						decorated.add(key);
					}
				results.put(ChunkPos.asLong(ox, oz), extract(terrain(ox, oz).join()));
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				if (!running) return;
				error = t.toString();
				ClaudeClient.LOG.error("Seed engine failed", t);
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	private void evict(int cx, int cz, int r) {
		int keepTerrain = r + 3, keepResults = Math.max(2 * r + 8, 24);
		terrain.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > keepTerrain);
		decorated.removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > keepTerrain);
		results.keySet().removeIf(k -> Math.max(Math.abs(ChunkPos.getX(k) - cx), Math.abs(ChunkPos.getZ(k) - cz)) > keepResults);
	}

	private static List<Ore> extract(ProtoChunk chunk) {
		List<Ore> out = new ArrayList<>();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(OreKind::isOre)) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						OreKind k = OreKind.of(st);
						if (k != null) out.add(new Ore(BlockPos.asLong(bx + x, by + y, bz + z), k));
					}
		}
		return out;
	}
}
