package dev.claudeclient.mixin;

import dev.claudeclient.module.player.Freecam;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	// No floating first-person hand while the camera is away from your body.
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void claudeclient$hideHand(float tickDelta, boolean sleeping, Matrix4f projection, CallbackInfo ci) {
		if (Freecam.active()) ci.cancel();
	}
}
