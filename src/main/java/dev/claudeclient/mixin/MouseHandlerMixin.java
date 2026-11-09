package dev.claudeclient.mixin;

import dev.claudeclient.module.player.Freecam;
import dev.claudeclient.module.player.Freelook;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	// While Freecam or Freelook is on, the mouse turns the camera instead of your player.
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void claudeclient$turn(LocalPlayer player, double dx, double dy) {
		if (Freecam.active()) {
			Freecam.get().turn(dx, dy);
			return;
		}
		if (Freelook.active()) {
			Freelook.get().turn(dx, dy);
			return;
		}
		player.turn(dx, dy);
	}
}
