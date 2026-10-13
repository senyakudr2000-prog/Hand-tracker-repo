package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Рисует скелет рук линиями и кубиками суставов. Только визуал - коллизий нет. */
public final class HandRenderer {
    private static final int[][] BONES = {
            {0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{5,9},{9,10},{10,11},{11,12},
            {9,13},{13,14},{14,15},{15,16},{13,17},{17,18},{18,19},{19,20},{0,17}};

    public static void render(RenderLevelStageEvent e) {
        MultiBufferSource.BufferSource buf = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack ps = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        VertexConsumer vc = buf.getBuffer(RenderType.lines());
        PoseStack.Pose pose = ps.last();

        for (int h = 0; h < 2; h++) {
            if (!HandTracker.visible[h] || HandTracker.world[h][0] == null) continue;
            boolean pinch = GrabController.pinching[h];
            float r = pinch ? 1f : 0.95f, g = pinch ? 0.4f : 0.8f, b = pinch ? 0.2f : 0.7f;
            Vec3[] w = HandTracker.world[h];
            for (int[] bone : BONES) line(vc, pose, w[bone[0]].subtract(cam), w[bone[1]].subtract(cam), r, g, b);
            for (int i = 0; i < 21; i++) {
                Vec3 p = w[i].subtract(cam);
                double s = (i == 4 || i == 8) ? 0.02 : 0.012;
                LevelRenderer.renderLineBox(ps, vc, p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s, r, g, b, 1f);
            }
        }
        buf.endBatch(RenderType.lines());
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 b, float r, float g, float bl) {
        Vec3 d = b.subtract(a).normalize();
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, 1f)
                .setNormal(pose, (float) d.x, (float) d.y, (float) d.z);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, 1f)
                .setNormal(pose, (float) d.x, (float) d.y, (float) d.z);
    }
}
