package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Рисует скелет руки линиями и кубиками суставов (PoseStack уже в позиции сущности). */
public class HandEntityRenderer extends EntityRenderer<HandEntity> {
    private static final int[][] BONES = {
            {0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{5,9},{9,10},{10,11},{11,12},
            {9,13},{13,14},{14,15},{15,16},{13,17},{17,18},{18,19},{19,20},{0,17}};

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
        HandTracker.HandPose pose = HandTracker.pose(e.handIndex());
        if (pose == null) return;
        Vec3[] w = e.offsets(pose);

        boolean pinch = pose.pinch();
        float r = pinch ? 1f : 0.95f, g = pinch ? 0.4f : 0.8f, b = pinch ? 0.2f : 0.7f;
        VertexConsumer vc = buf.getBuffer(RenderType.lines());
        PoseStack.Pose last = ps.last();

        for (int[] bone : BONES) line(vc, last, w[bone[0]], w[bone[1]], r, g, b);
        for (int i = 0; i < 21; i++) {
            Vec3 p = w[i];
            double s = (i == 4 || i == 8) ? 0.02 : 0.012;
            LevelRenderer.renderLineBox(ps, vc, p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s, r, g, b, 1f);
        }
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, float r, float g, float bl) {
        Vec3 d = b.subtract(a).normalize();
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, 1f)
                .setNormal(pose, (float) d.x, (float) d.y, (float) d.z);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, 1f)
                .setNormal(pose, (float) d.x, (float) d.y, (float) d.z);
    }
}
