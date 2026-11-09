package dev.claudeclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.claudeclient.gui.ClaudeScreen;
import dev.claudeclient.module.Module;
import dev.claudeclient.module.ModuleManager;
import dev.claudeclient.module.client.Hud;
import dev.claudeclient.module.world.HashedSeed;
import dev.claudeclient.render.Render3D;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ClaudeClient implements ClientModInitializer {
	public static final String MOD_ID = "claudeclient";
	public static final Logger LOG = LoggerFactory.getLogger("Claude Client");
	public static final ModuleManager MODULES = new ModuleManager();
	public static String VERSION = "dev";
	public static KeyMapping menuKey;
	public static KeyMapping copySeedKey;

	private static ClientLevel lastLevel;
	private static String toastText;
	private static long toastAt;

	@Override
	public void onInitializeClient() {
		VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

		Modules.registerAll(MODULES);
		Config.load();
		HashedSeed.get().loadHistory();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.claudeclient.menu",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
		copySeedKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.claudeclient.copy_seed",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != lastLevel) {
				lastLevel = mc.level;
				MODULES.onWorldChange();
			}
			while (menuKey.consumeClick()) if (mc.screen == null) mc.setScreen(new ClaudeScreen());
			while (copySeedKey.consumeClick()) HashedSeed.get().copyCurrent();
			MODULES.pollKeybinds(mc);
			if (mc.player != null && mc.level != null) MODULES.onTick();
		});

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> MODULES.onChunkLoad(chunk));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> MODULES.onChunkUnload(chunk));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (Minecraft.getInstance().player != null) MODULES.onEntityLoad(entity);
		});
		dev.claudeclient.render.ArmorStandLayer.register();

		WorldRenderEvents.END_MAIN.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) return;
			CameraRenderState camera = context.worldState().cameraRenderState;
			Render3D render = new Render3D(camera.pos, camera.orientation, Render3D.partialTick());
			MODULES.onRender3D(render);
			render.flush(context.matrices());
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (graphics, tick) -> Hud.renderAll(graphics));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HashedSeed.get().onDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Config.save());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(Commands.build(literal("claude"))));

		LOG.info("Claude Client {} loaded with {} modules", VERSION, MODULES.all().size());
	}

	/** A short message in the middle of the screen. */
	public static void toast(String text) {
		toastText = text;
		toastAt = System.currentTimeMillis();
	}

	public static String toastText() {
		return System.currentTimeMillis() - toastAt < 1600 ? toastText : null;
	}

	public static float toastAge() {
		return (System.currentTimeMillis() - toastAt) / 1600f;
	}

	/** Opens a module's page in the menu a tick later (screens opened straight from chat get closed with it). */
	public static void openMenu(Module m) {
		Minecraft mc = Minecraft.getInstance();
		mc.schedule(() -> mc.setScreen(m == null ? new ClaudeScreen() : ClaudeScreen.on(m)));
	}
}
