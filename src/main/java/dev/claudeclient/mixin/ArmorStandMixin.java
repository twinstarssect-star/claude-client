package dev.claudeclient.mixin;

import dev.claudeclient.module.finders.ArmorStandBypass;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
	// Armor Stand Bypass: never hide an armor stand for being far away.
	@Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
	private void claudeclient$renderFar(double distSq, CallbackInfoReturnable<Boolean> cir) {
		if (ArmorStandBypass.active()) cir.setReturnValue(true);
	}
}
