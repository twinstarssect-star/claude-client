package dev.claudeclient.mixin;

import dev.claudeclient.module.player.SpeedMine;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	// Speed Mine's Server Safe Haste: boost your mining speed, capped at what the server accepts.
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void claudeclient$speedMine(BlockState state, CallbackInfoReturnable<Float> cir) {
		float v = cir.getReturnValueF();
		float out = SpeedMine.serverSafeSpeed((Player) (Object) this, state, v);
		if (out != v) cir.setReturnValue(out);
	}
}
