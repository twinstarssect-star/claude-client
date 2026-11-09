package dev.claudeclient.module.player;

import dev.claudeclient.mixin.MultiPlayerGameModeAccessor;
import dev.claudeclient.module.Category;
import dev.claudeclient.module.Module;
import dev.claudeclient.setting.BoolSetting;
import dev.claudeclient.setting.ModeSetting;
import dev.claudeclient.setting.NumberSetting;
import dev.claudeclient.setting.Setting;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Two kinds of client-side Haste:
 * <ul>
 * <li><b>Speed Haste</b>: gives you a real Haste effect of the level you pick, on your client only (the effect icon
 * shows, your arm swings faster, blocks break at that speed). Servers that check break times may put blocks back.</li>
 * <li><b>Server Safe Haste</b>: the fastest the server accepts. When you finish a block, a vanilla/Paper server
 * checks the break with its own speed and accepts it if it's at least 70% done, so this keeps your speed under
 * ~1.38x what the server expects and never turns a 2-tick block into an instant break it would refuse.</li>
 * </ul>
 */
public final class SpeedMine extends Module {
	private static SpeedMine instance;
	/** 1 / 0.7 is the server's limit; stay under it with room for tick rounding and lag. */
	private static final float SERVER_SAFE_MAX = 1.38f;

	private final ModeSetting mode = add(new ModeSetting("Haste Type",
			"Speed Haste: a client-side Haste effect at the level you pick. Server Safe Haste: as fast as the server will accept.",
			"Server Safe Haste", "Server Safe Haste", "Speed Haste"));
	private final NumberSetting level = add(new NumberSetting("Haste Level", "Which Haste level you get (Haste II = 2). +20% mining speed per level.",
			3, 1, 10, 1));
	private final BoolSetting noDelay = add(new BoolSetting("No Break Delay", "Skip the short pause between breaking one block and starting the next.", true));

	private boolean gaveEffect;

	public SpeedMine() {
		super("Speed Mine", "Client-side Haste with an adjustable level. Pick Speed Haste for raw speed or Server Safe Haste "
				+ "for the fastest the server accepts.", Category.PLAYER, false);
		instance = this;
	}

	@Override
	public boolean isSettingVisible(Setting<?> s) {
		return s != level || mode.is("Speed Haste");
	}

	@Override
	public String hudInfo() {
		return mode.is("Speed Haste") ? "Haste " + level.getInt() : "Safe";
	}

	private boolean speedMode() {
		return mode.is("Speed Haste");
	}

	@Override
	public void onTick() {
		if (noDelay.get() && mc.gameMode != null) ((MultiPlayerGameModeAccessor) mc.gameMode).claudeclient$setDestroyDelay(0);
		Holder<MobEffect> haste = MobEffects.HASTE;
		if (speedMode()) {
			int amp = level.getInt() - 1;
			MobEffectInstance cur = mc.player.getEffect(haste);
			if (cur == null || (gaveEffect && cur.getAmplifier() != amp) || (!gaveEffect && cur.getAmplifier() < amp)) {
				mc.player.addEffect(new MobEffectInstance(haste, MobEffectInstance.INFINITE_DURATION, amp, true, false, true));
				gaveEffect = true;
			}
		} else {
			removeOurEffect();
		}
	}

	@Override
	protected void onDisable() {
		removeOurEffect();
	}

	@Override
	public void onWorldChange() {
		gaveEffect = false;
	}

	private void removeOurEffect() {
		if (!gaveEffect || mc.player == null) {
			gaveEffect = false;
			return;
		}
		MobEffectInstance cur = mc.player.getEffect(MobEffects.HASTE);
		if (cur != null && cur.isInfiniteDuration()) mc.player.removeEffect(MobEffects.HASTE);
		gaveEffect = false;
	}

	/**
	 * Server Safe Haste multiplier for your own player's mining speed (called from PlayerMixin). {@code vanillaSpeed} is
	 * what the server will also compute; the boost is capped so the server accepts the break.
	 */
	public static float serverSafeSpeed(Player player, BlockState state, float vanillaSpeed) {
		SpeedMine m = instance;
		if (m == null || !m.isEnabled() || m.speedMode() || player != mc.player) return vanillaSpeed;
		float boosted = vanillaSpeed * SERVER_SAFE_MAX;
		float hardness = state.getBlock().defaultDestroyTime();
		if (hardness > 0) {
			float div = player.hasCorrectToolForDrops(state) ? 30f : 100f;
			float vanillaPerTick = vanillaSpeed / hardness / div;
			float boostedPerTick = boosted / hardness / div;
			// Don't make a block the server needs 2+ ticks for break instantly: it would refuse and leave a ghost block.
			if (vanillaPerTick < 1f && boostedPerTick >= 1f) boosted = vanillaSpeed * Math.max(1f, 0.99f / vanillaPerTick);
		}
		return boosted;
	}
}
