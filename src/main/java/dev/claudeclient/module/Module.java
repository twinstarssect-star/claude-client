package dev.claudeclient.module;

import dev.claudeclient.gui.Row;
import dev.claudeclient.render.Render3D;
import dev.claudeclient.setting.KeybindSetting;
import dev.claudeclient.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
	/** One line a module shows in the top-left HUD stack: a dim label then a bright value. */
	public record HudLine(String label, String value) {}

	protected static final Minecraft mc = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final KeybindSetting keybind;
	private boolean enabled;

	protected Module(String name, String description, Category category, boolean enabledByDefault) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.enabled = enabledByDefault;
		this.keybind = new KeybindSetting("Keybind", "A key that turns " + name + " on and off. Click, then press a key "
				+ "(Esc or Backspace clears it).", GLFW.GLFW_KEY_UNKNOWN);
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public Category category() {
		return category;
	}

	/** Module settings, with the keybind last. */
	public List<Setting<?>> settings() {
		List<Setting<?>> all = new ArrayList<>(settings);
		all.add(keybind);
		return Collections.unmodifiableList(all);
	}

	public KeybindSetting keybind() {
		return keybind;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean on) {
		if (on == enabled) return;
		enabled = on;
		if (on) onEnable();
		else onDisable();
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	/** Loading a config sets the flag without firing onEnable/onDisable. */
	public void setEnabledSilently(boolean on) {
		enabled = on;
	}

	protected boolean inWorld() {
		return mc.player != null && mc.level != null;
	}

	protected void onEnable() {}

	protected void onDisable() {}

	// ---------------------------------------------------------------- events (only called while enabled, in a world)

	public void onTick() {}

	public void onRender3D(Render3D render) {}

	/** Joined a world, left one, or changed dimension. Called even while disabled. */
	public void onWorldChange() {}

	public void onChunkLoad(LevelChunk chunk) {}

	public void onChunkUnload(LevelChunk chunk) {}

	/** A block changed from a server packet (single or section update). {@code oldState} is what the client had. */
	public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {}

	/** A positioned sound from the server (other players' actions, mobs, machines). */
	public void onSound(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z, float volume) {}

	/** Block events: pistons moving, chests/barrels/shulkers opening, note blocks, bells. Sent up to 64 blocks away. */
	public void onBlockEvent(BlockPos pos, Block block, int type, int data) {}

	/** Level events: doors, trapdoors, dispensers, blocks broken, composters, bone meal... Sent up to 64 blocks away. */
	public void onLevelEvent(int type, BlockPos pos, int data, boolean global) {}

	/** An entity the server just started sending (spawned, or came into tracking range). */
	public void onEntityLoad(net.minecraft.world.entity.Entity entity) {}

	// ---------------------------------------------------------------- HUD + menu

	/** Lines for the top-left HUD stack while the module is enabled. */
	public List<HudLine> hudLines() {
		return List.of();
	}

	/** Whether toggling with the keybind shows an on/off toast. */
	public boolean toastOnToggle() {
		return true;
	}

	/** Short text shown after the module's name in the HUD module list, or null. */
	public String hudInfo() {
		return null;
	}

	/** Rows the menu shows right under the description, above the settings. */
	public List<Row> headerRows(int width) {
		return List.of();
	}

	/** Extra rows the menu shows under the module's settings. */
	public List<Row> extraRows(int width) {
		return List.of();
	}

	/** Which settings the menu shows right now (e.g. hide options that don't apply to the current mode). */
	public boolean isSettingVisible(Setting<?> setting) {
		return true;
	}
}
