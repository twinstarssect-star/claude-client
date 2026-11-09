package dev.claudeclient.module.player;

import dev.claudeclient.ClaudeClient;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.NumberSetting;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Detaches the camera so you can fly it anywhere with the movement keys and mouse, while your real player carries on.
 * <p>
 * Keep Moving: whatever movement keys you were holding when you turned Freecam on (walk, strafe, jump, sneak, sprint)
 * stay held for your player, so it keeps walking/jumping/crouching while the camera flies around; moving the camera
 * never moves your player. Holding the attack button keeps mining what your player is looking at (its aim doesn't
 * follow the camera), so you can tunnel forward and look around at the same time.
 */
public final class Freecam extends Module {
	private static Freecam instance;

	private final NumberSetting speed = add(new NumberSetting("Speed", "Camera flying speed.", 1.0, 0.1, 6.0, 0.1));
	private final NumberSetting sprintMultiplier = add(new NumberSetting("Sprint Multiplier", "Camera speed while holding sprint.", 3.0, 1.0, 8.0, 0.5));
	private final BoolSetting keepMoving = add(new BoolSetting("Keep Moving",
			"Your player keeps doing whatever movement you were holding when Freecam turned on (walking, strafing, jumping, "
					+ "sneaking, sprinting). Off: your player stands still.", true));
	private final BoolSetting keepMining = add(new BoolSetting("Keep Mining",
			"Holding attack keeps mining what your player is looking at while you fly the camera.", true));

	private Vec3 pos = Vec3.ZERO, prevPos = Vec3.ZERO;
	private float yaw, pitch;
	private Input latched = Input.EMPTY;

	public Freecam() {
		super("Freecam", "Fly the camera anywhere while your player keeps moving and mining. Turn it on while holding W (and "
				+ "attack) and your player keeps going; the camera keys never move your player.", Category.PLAYER, false);
		instance = this;
	}

	public static Freecam get() {
		return instance;
	}

	public static boolean active() {
		return instance != null && instance.isEnabled();
	}

	@Override
	protected void onEnable() {
		if (!inWorld()) {
			setEnabled(false);
			return;
		}
		pos = prevPos = mc.gameRenderer.getMainCamera().position();
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		latched = keepMoving.get() ? mc.player.input.keyPresses : Input.EMPTY;
		// Occlusion culling assumes the camera is inside your player; flying through walls would hide chunks.
		mc.smartCull = false;
	}

	@Override
	protected void onDisable() {
		mc.smartCull = true;
		latched = Input.EMPTY;
	}

	@Override
	public void onWorldChange() {
		if (isEnabled()) setEnabled(false);
	}

	@Override
	public void onTick() {
		if (mc.player.isDeadOrDying()) {
			setEnabled(false);
			ClaudeClient.toast("Freecam off");
			return;
		}
		prevPos = pos;
		if (mc.screen != null) return;
		Options o = mc.options;
		double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
		double strafe = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
		double vertical = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
		double s = speed.get() * (o.keySprint.isDown() ? sprintMultiplier.get() : 1);
		double rad = Math.toRadians(yaw);
		double sin = Math.sin(rad), cos = Math.cos(rad);
		double dx = -sin * forward + cos * strafe;
		double dz = cos * forward + sin * strafe;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len > 1) {
			dx /= len;
			dz /= len;
		}
		pos = pos.add(dx * s, vertical * s, dz * s);
	}

	/** Mouse movement turns the camera (same sensitivity as turning your player). */
	public void turn(double dx, double dy) {
		yaw += (float) dx * 0.15f;
		pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	public Vec3 position(float tickDelta) {
		return prevPos.lerp(pos, tickDelta);
	}

	/** The movement your player gets while Freecam is on (the latched keys). */
	public Input playerInput() {
		return latched;
	}

	public static Vec2 moveVector(Input in) {
		float f = in.forward() == in.backward() ? 0 : in.forward() ? 1 : -1;
		float g = in.left() == in.right() ? 0 : in.left() ? 1 : -1;
		return new Vec2(g, f).normalized();
	}

	public boolean keepMining() {
		return keepMining.get();
	}
}
