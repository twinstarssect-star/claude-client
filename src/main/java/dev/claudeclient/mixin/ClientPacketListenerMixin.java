package dev.claudeclient.mixin;

import dev.claudeclient.ClaudeClient;
import dev.claudeclient.module.world.HashedSeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet hooks. Every handler is first called on the network thread, throws to re-queue itself, then runs again on the
 * game thread; the HEAD hooks below only act on that second, game-thread pass (before the packet is applied, so the
 * old block is still readable). Login/respawn each have a single return, so TAIL is game-thread-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void claudeclient$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
		capture(packet.commonPlayerSpawnInfo(), true);
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void claudeclient$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		capture(packet.commonPlayerSpawnInfo(), false);
	}

	private static void capture(CommonPlayerSpawnInfo info, boolean login) {
		HashedSeed module = HashedSeed.get();
		if (module != null) module.onSpawnInfo(info.dimension().identifier().toString(), info.seed(), login);
	}

	private static boolean ready() {
		Minecraft mc = Minecraft.getInstance();
		return mc.isSameThread() && mc.level != null && mc.player != null;
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void claudeclient$onSound(ClientboundSoundPacket p, CallbackInfo ci) {
		if (ready()) ClaudeClient.MODULES.onSound(p.getSound(), p.getSource(), p.getX(), p.getY(), p.getZ(), p.getVolume());
	}

	@Inject(method = "handleBlockEvent", at = @At("HEAD"))
	private void claudeclient$onBlockEvent(ClientboundBlockEventPacket p, CallbackInfo ci) {
		if (ready()) ClaudeClient.MODULES.onBlockEvent(p.getPos(), p.getBlock(), p.getB0(), p.getB1());
	}

	@Inject(method = "handleLevelEvent", at = @At("HEAD"))
	private void claudeclient$onLevelEvent(ClientboundLevelEventPacket p, CallbackInfo ci) {
		if (ready()) ClaudeClient.MODULES.onLevelEvent(p.getType(), p.getPos(), p.getData(), p.isGlobalEvent());
	}

	@Inject(method = "handleBlockUpdate", at = @At("HEAD"))
	private void claudeclient$onBlockUpdate(ClientboundBlockUpdatePacket p, CallbackInfo ci) {
		if (!ready()) return;
		Minecraft mc = Minecraft.getInstance();
		ClaudeClient.MODULES.onBlockUpdate(p.getPos(), mc.level.getBlockState(p.getPos()), p.getBlockState());
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
	private void claudeclient$onSectionUpdate(ClientboundSectionBlocksUpdatePacket p, CallbackInfo ci) {
		if (!ready()) return;
		Minecraft mc = Minecraft.getInstance();
		p.runUpdates((pos, state) -> ClaudeClient.MODULES.onBlockUpdate(pos.immutable(), mc.level.getBlockState(pos), state));
	}
}
