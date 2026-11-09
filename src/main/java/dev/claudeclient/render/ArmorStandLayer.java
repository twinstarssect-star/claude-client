package dev.claudeclient.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.claudeclient.module.finders.ArmorStandBypass;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** A render layer on the armor stand renderer that hands the posed model to Armor Stand Bypass. */
public final class ArmorStandLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	@SuppressWarnings({"unchecked", "rawtypes"})
	private ArmorStandLayer(Object renderer) {
		super((RenderLayerParent) renderer);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void register() {
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof ArmorStandRenderer) helper.register((RenderLayer) new ArmorStandLayer(renderer));
		});
	}

	@Override
	public void submit(PoseStack ps, SubmitNodeCollector collector, int light, LivingEntityRenderState state, float yRot, float xRot) {
		if (state.isInvisible) return;
		ArmorStandBypass.capture(ps, state, getParentModel());
	}
}
