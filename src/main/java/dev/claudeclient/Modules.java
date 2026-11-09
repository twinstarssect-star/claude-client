package dev.claudeclient;

import dev.claudeclient.module.ModuleManager;
import dev.claudeclient.module.client.Hud;
import dev.claudeclient.module.finders.ArmorStandBypass;
import dev.claudeclient.module.finders.FarmDebug;
import dev.claudeclient.module.finders.LightFinder;
import dev.claudeclient.module.finders.PlayerESP;
import dev.claudeclient.module.finders.SoundESP;
import dev.claudeclient.module.finders.StorageESP;
import dev.claudeclient.module.finders.SusChunks;
import dev.claudeclient.module.player.Freecam;
import dev.claudeclient.module.player.Freelook;
import dev.claudeclient.module.player.SpeedMine;
import dev.claudeclient.module.world.HashedSeed;

/** Every module, in menu order. */
final class Modules {
	static void registerAll(ModuleManager m) {
		// Seed
		m.add(new HashedSeed());
		m.add(new dev.claudeclient.module.seed.SeedESP());
		m.add(new dev.claudeclient.module.seed.BiomeChecker());
		// Finders
		m.add(new StorageESP());
		m.add(new PlayerESP());
		m.add(new SusChunks());
		m.add(new FarmDebug());
		m.add(new LightFinder());
		m.add(new SoundESP());
		m.add(new ArmorStandBypass());
		// Player
		m.add(new Freecam());
		m.add(new Freelook());
		m.add(new SpeedMine());
		// Client
		m.add(new Hud());
	}

	private Modules() {}
}
