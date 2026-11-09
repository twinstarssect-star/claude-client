package dev.claudeclient.mixin;

import dev.claudeclient.module.player.Freecam;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	// Freecam with Keep Mining off: the attack button does nothing while you fly around.
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void claudeclient$continueAttack(boolean down, CallbackInfo ci) {
		if (Freecam.active() && !Freecam.get().keepMining()) ci.cancel();
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void claudeclient$startAttack(CallbackInfoReturnable<Boolean> cir) {
		if (Freecam.active() && !Freecam.get().keepMining()) cir.setReturnValue(false);
	}
}
