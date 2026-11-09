package dev.claudeclient.module.player;

import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Look around without turning your player: the mouse moves the camera while your body keeps facing (and walking)
 * the same way. With Hold on, it only lasts while you hold its keybind.
 */
public final class Freelook extends Module {
	private static Freelook instance;

	private final BoolSetting thirdPerson = add(new BoolSetting("Third Person", "Switch to third person while it's on.", true));
	private final BoolSetting hold = add(new BoolSetting("Hold", "Only while you hold the keybind (otherwise the key toggles it).", true));

	private float yaw, pitch;
	private CameraType previousCamera;

	public Freelook() {
		super("Freelook", "Rotate the camera around you without changing where you face or walk. Give it a keybind.", Category.PLAYER, false);
		instance = this;
		keybind().set(GLFW.GLFW_KEY_LEFT_ALT);
	}

	@Override
	public boolean toastOnToggle() {
		return !hold.get();
	}

	public static Freelook get() {
		return instance;
	}

	public static boolean active() {
		return instance != null && instance.isEnabled() && !Freecam.active();
	}

	@Override
	protected void onEnable() {
		if (!inWorld()) {
			setEnabled(false);
			return;
		}
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		previousCamera = mc.options.getCameraType();
		if (thirdPerson.get() && previousCamera.isFirstPerson()) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	@Override
	protected void onDisable() {
		if (previousCamera != null) {
			mc.options.setCameraType(previousCamera);
			previousCamera = null;
		}
	}

	@Override
	public void onWorldChange() {
		if (isEnabled()) setEnabled(false);
	}

	@Override
	public void onTick() {
		if (hold.get() && keybind().isBound() && !com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), keybind().get())) {
			setEnabled(false);
		}
	}

	public void turn(double dx, double dy) {
		yaw += (float) dx * 0.15f;
		pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
	}

	/** Camera yaw; front-facing third person looks back at you, like vanilla's mirrored view. */
	public float yaw(boolean mirrored) {
		return mirrored ? yaw + 180f : yaw;
	}

	public float pitch(boolean mirrored) {
		return mirrored ? -pitch : pitch;
	}
}
