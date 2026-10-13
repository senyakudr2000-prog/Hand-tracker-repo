package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/** Вид от третьего лица: две руки целиком (ванильные руки скрыты в HandTrackingMod). */
public class HandLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public HandLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack ps, MultiBufferSource buf, int light, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player || player.isInvisible()) return;
        PlayerSkin skin = player.getSkin();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(skin.texture()));
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();

        for (int h = 0; h < 2; h++) {
            HandTracker.HandPose pose = HandTracker.pose(h);
            if (pose == null) continue;
            boolean left = h == 0;
            Vec3[] world = HandAttach.solve(pose, left, slim, player, partialTick);
            HandAttach.publish(h, world);
            Vec3[] j = HandAttach.toModelSpace(world, ps.last().pose(), camPos);
            HandModel.drawArm(ps, vc, light, j, left, slim);
            HandModel.draw(ps, vc, light, j, left, pose.pinch());
        }
    }
}
