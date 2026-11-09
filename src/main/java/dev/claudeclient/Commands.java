package dev.claudeclient;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.claudeclient.module.Module;
import dev.claudeclient.module.world.HashedSeed;
import dev.claudeclient.seed.SeedStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.util.OptionalLong;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** "/claude ..." client commands. Nothing here is sent to the server. */
public final class Commands {
	public static LiteralArgumentBuilder<FabricClientCommandSource> build(LiteralArgumentBuilder<FabricClientCommandSource> root) {
		return root
				.executes(ctx -> {
					Chat.info("/claude seed - the hashed seed(s), with [Copy]");
					Chat.info("/claude seed copy - copy the current hashed seed");
					Chat.info("/claude seed set <seed> - the real world seed for this server (for Seed ESP / Biome Checker)");
					Chat.info("/claude seed world - show the saved world seed and whether it checks out");
					Chat.info("/claude seed clear - forget this server's world seed");
					Chat.info("/claude map - seed map of biomes and structures");
					Chat.info("/claude menu - open the menu (default key: Right Shift)");
					Chat.info("/claude toggle <module> - turn a module on or off");
					return 1;
				})
				.then(literal("seed")
						.executes(ctx -> {
							HashedSeed.get().printSeeds();
							return 1;
						})
						.then(literal("copy").executes(ctx -> {
							HashedSeed.get().copyCurrent();
							return 1;
						}))
						.then(literal("set").then(argument("seed", StringArgumentType.greedyString()).executes(ctx -> {
							String text = StringArgumentType.getString(ctx, "seed");
							if (SeedStore.singleplayer()) {
								Chat.info("In singleplayer the world seed is read automatically.");
								return 1;
							}
							long seed = SeedStore.parse(text);
							SeedStore.Status st = SeedStore.check(seed);
							SeedStore.set(seed);
							Chat.info("World seed for " + SeedStore.serverKey() + " set to " + seed + ".");
							switch (st) {
								case VERIFIED -> Chat.send(Chat.colored("It matches this server's hashed seed: it's the right seed.", Theme.GOOD));
								case MISMATCH -> Chat.send(Chat.colored("Warning: it does NOT match this server's hashed seed. "
										+ "Seed-based modules will show the wrong things until you set the right seed.", 0xFFFF6B6B));
								default -> Chat.info("This server hides its hashed seed, so the seed can't be checked.");
							}
							return 1;
						})))
						.then(literal("world").executes(ctx -> {
							OptionalLong s = SeedStore.current();
							if (s.isEmpty()) Chat.info("No world seed saved for " + SeedStore.serverKey() + ". Use /claude seed set <seed>.");
							else Chat.info("World seed: " + s.getAsLong() + " - " + SeedStore.describe(SeedStore.status()));
							return 1;
						}))
						.then(literal("clear").executes(ctx -> {
							SeedStore.clear();
							Chat.info("Forgot the world seed for " + SeedStore.serverKey() + ".");
							return 1;
						})))
				.then(literal("map").executes(ctx -> {
					Minecraft mc = Minecraft.getInstance();
					mc.schedule(() -> mc.setScreen(new dev.claudeclient.gui.SeedMapScreen()));
					return 1;
				}))
				.then(literal("menu").executes(ctx -> {
					ClaudeClient.openMenu(null);
					return 1;
				}))
				.then(literal("toggle").then(argument("module", StringArgumentType.greedyString()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "module");
					Module m = ClaudeClient.MODULES.byName(name);
					if (m == null) {
						Chat.info("No module called \"" + name + "\".");
						return 0;
					}
					m.toggle();
					Config.save();
					Chat.info(m.name() + (m.isEnabled() ? " on" : " off"));
					return 1;
				})));
	}

	private Commands() {}
}
