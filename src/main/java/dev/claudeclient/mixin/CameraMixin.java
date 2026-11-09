package dev.claudeclient.mixin;

import dev.claudeclient.module.player.Freecam;
import dev.claudeclient.module.player.Freelook;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	// Third person: swap in the freelook angles right before the camera is pulled back, so it orbits your head.
	@Inject(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;move(FFF)V", ordinal = 0))
	private void claudeclient$freelookThirdPerson(Level level, Entity entity, boolean detached, boolean mirrored, float tickDelta, CallbackInfo ci) {
		if (Freelook.active()) setRotation(Freelook.get().yaw(mirrored), Freelook.get().pitch(mirrored));
	}

	@Inject(method = "setup", at = @At("TAIL"))
	private void claudeclient$cameraOverrides(Level level, Entity entity, boolean detached, boolean mirrored, float tickDelta, CallbackInfo ci) {
		if (Freecam.active()) {
			Freecam f = Freecam.get();
			setRotation(f.yaw(), f.pitch());
			setPosition(f.position(tickDelta));
			// Detached so your own body is drawn where it is.
			this.detached = true;
			return;
		}
		if (Freelook.active() && !detached) setRotation(Freelook.get().yaw(false), Freelook.get().pitch(false));
	}
}
