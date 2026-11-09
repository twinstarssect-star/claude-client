package dev.claudeclient.module;

import com.mojang.blaze3d.platform.InputConstants;
import dev.claudeclient.ClaudeClient;
import dev.claudeclient.Config;
import dev.claudeclient.render.Render3D;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();
	private final Map<Class<?>, Module> byClass = new HashMap<>();
	private final Map<Module, Boolean> keyWasDown = new HashMap<>();

	public <M extends Module> M add(M module) {
		modules.add(module);
		byClass.put(module.getClass(), module);
		return module;
	}

	@SuppressWarnings("unchecked")
	public <M extends Module> M get(Class<M> type) {
		return (M) byClass.get(type);
	}

	public List<Module> all() {
		return Collections.unmodifiableList(modules);
	}

	public List<Module> byCategory(Category category) {
		List<Module> out = new ArrayList<>();
		for (Module m : modules) if (m.category() == category) out.add(m);
		return out;
	}

	public Module byName(String name) {
		for (Module m : modules) if (m.name().equalsIgnoreCase(name)) return m;
		return null;
	}

	/** Module keybinds: toggle on the press, only while no screen is open. */
	public void pollKeybinds(Minecraft mc) {
		boolean anyToggled = false;
		for (Module m : modules) {
			if (!m.keybind().isBound()) continue;
			boolean down = mc.screen == null && InputConstants.isKeyDown(mc.getWindow(), m.keybind().get());
			boolean was = keyWasDown.getOrDefault(m, false);
			keyWasDown.put(m, down);
			if (down && !was) {
				m.toggle();
				anyToggled = true;
				if (m.toastOnToggle()) ClaudeClient.toast(m.name() + (m.isEnabled() ? " on" : " off"));
			}
		}
		if (anyToggled) Config.save();
	}

	private void each(Consumer<Module> action) {
		for (Module m : modules) {
			if (!m.isEnabled()) continue;
			try {
				action.accept(m);
			} catch (Throwable t) {
				ClaudeClient.LOG.error("{} failed", m.name(), t);
			}
		}
	}

	public void onTick() {
		each(Module::onTick);
	}

	public void onRender3D(Render3D render) {
		each(m -> m.onRender3D(render));
	}

	public void onWorldChange() {
		for (Module m : modules) {
			try {
				m.onWorldChange();
			} catch (Throwable t) {
				ClaudeClient.LOG.error("{} failed on world change", m.name(), t);
			}
		}
	}

	public void onChunkLoad(LevelChunk chunk) {
		each(m -> m.onChunkLoad(chunk));
	}

	public void onChunkUnload(LevelChunk chunk) {
		each(m -> m.onChunkUnload(chunk));
	}

	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		each(m -> m.onBlockUpdate(pos, oldState, newState));
	}

	public void onSound(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z, float volume) {
		each(m -> m.onSound(sound, source, x, y, z, volume));
	}

	public void onBlockEvent(BlockPos pos, Block block, int type, int data) {
		each(m -> m.onBlockEvent(pos, block, type, data));
	}

	public void onLevelEvent(int type, BlockPos pos, int data, boolean global) {
		each(m -> m.onLevelEvent(type, pos, data, global));
	}

	public void onEntityLoad(net.minecraft.world.entity.Entity entity) {
		each(m -> m.onEntityLoad(entity));
	}
}
