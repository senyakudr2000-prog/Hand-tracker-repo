package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Сама сущность ничего не рисует: кисть рисуется на руке игрока (RenderArmEvent / HandLayer). Хитбокс - по F3+B. */
public class HandEntityRenderer extends EntityRenderer<HandEntity> {
    public HandEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(HandEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }

    @Override
    public void render(HandEntity e, float entityYaw, float partialTick, PoseStack ps,
                       MultiBufferSource buf, int packedLight) {
        // намеренно пусто
    }
}
