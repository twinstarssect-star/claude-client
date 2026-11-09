package dev.claudeclient.mixin;

import dev.claudeclient.module.player.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	// In Freecam the real movement keys fly the camera; your player gets the keys latched when Freecam turned on.
	@Inject(method = "tick", at = @At("TAIL"))
	private void claudeclient$freecamInput(CallbackInfo ci) {
		if (!Freecam.active()) return;
		Input in = Freecam.get().playerInput();
		this.keyPresses = in;
		this.moveVector = Freecam.moveVector(in);
	}
}
