package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * Кисть "как у игрока": ладонь и пальцы-кубики цвета кожи (берётся из скина игрока).
 * Предплечье не рисуется - это настоящая рука игрока, кисть просто продолжает её.
 * Суставы приходят уже в системе координат части модели руки.
 */
public final class HandModel {
    /** Цепочки суставов: большой палец (от запястья), затем указательный..мизинец (от костяшек). */
    private static final int[][] FINGERS = {
            {0, 1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};

    public static void draw(PoseStack ps, VertexConsumer vc, int light, Vec3[] w, boolean left, boolean pinch) {
        float g = pinch ? 0.85f : 1f; // при щипке чуть краснеет

        Vec3 wrist = w[0];
        Vec3 up = w[9].subtract(wrist);
        double palmLen = up.length();
        if (palmLen < 1e-4) return;
        Vec3 y = up.scale(1.0 / palmLen);
        Vec3 z = w[5].subtract(w[17]).cross(y);
        if (z.lengthSqr() < 1e-10) return;
        z = z.normalize();
        Vec3 x = y.cross(z).normalize();

        float[] uv = skinUv(left);

        float pl = (float) palmLen;
        float pw = Mth.clamp((float) w[5].distanceTo(w[17]) * 1.25f, 0.14f, 0.24f);
        float pt = pl * 0.55f;

        // --- ладонь: система координат (x поперёк, y к пальцам, z от ладони) ---
        Quaternionf q = new Quaternionf().setFromNormalized(new Matrix3f(
                (float) x.x, (float) x.y, (float) x.z,
                (float) y.x, (float) y.y, (float) y.z,
                (float) z.x, (float) z.y, (float) z.z));
        ps.pushPose();
        ps.translate(wrist.x, wrist.y, wrist.z);
        ps.mulPose(q);
        emitBox(ps.last(), vc, light, 1f, g, g, -pw / 2, -0.01f, -pt / 2, pw / 2, pl, pt / 2, uv);
        ps.popPose();

        // --- пальцы ---
        float ft = pl * 0.2f;
        for (int c = 0; c < FINGERS.length; c++) {
            int[] chain = FINGERS[c];
            float t = c == 0 ? ft * 1.15f : ft;
            for (int k = 0; k + 1 < chain.length; k++) {
                segment(ps, vc, light, g, w[chain[k]], w[chain[k + 1]], t, uv);
            }
        }
    }

    private static void segment(PoseStack ps, VertexConsumer vc, int light, float g,
                                Vec3 a, Vec3 b, float t, float[] uv) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1e-5) return;
        ps.pushPose();
        ps.translate(a.x, a.y, a.z);
        ps.mulPose(new Quaternionf().rotationTo(0f, 1f, 0f,
                (float) (d.x / len), (float) (d.y / len), (float) (d.z / len)));
        float e = t * 0.5f; // заходим за концы, чтобы в суставах не было щелей
        emitBox(ps.last(), vc, light, 1f, g, g, -t / 2, -e, -t / 2, t / 2, (float) len + e, t / 2, uv);
        ps.popPose();
    }

    /** Одноцветный участок кожи в области кисти руки скина 64x64. */
    private static float[] skinUv(boolean left) {
        float cx = left ? 38f : 46f, cy = left ? 62f : 30f;
        return new float[]{(cx - 0.5f) / 64f, (cy - 0.5f) / 64f, (cx + 0.5f) / 64f, (cy + 0.5f) / 64f};
    }

    private static void emitBox(PoseStack.Pose p, VertexConsumer vc, int light, float r, float g, float b,
                                float x0, float y0, float z0, float x1, float y1, float z1, float[] uv) {
        face(p, vc, light, r, g, b, uv, 0, 0, -1, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        face(p, vc, light, r, g, b, uv, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(p, vc, light, r, g, b, uv, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(p, vc, light, r, g, b, uv, 1, 0, 0, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        face(p, vc, light, r, g, b, uv, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(p, vc, light, r, g, b, uv, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void face(PoseStack.Pose p, VertexConsumer vc, int light, float r, float g, float b,
                             float[] uv, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        vert(p, vc, light, r, g, b, ax, ay, az, uv[0], uv[1], nx, ny, nz);
        vert(p, vc, light, r, g, b, bx, by, bz, uv[2], uv[1], nx, ny, nz);
        vert(p, vc, light, r, g, b, cx, cy, cz, uv[2], uv[3], nx, ny, nz);
        vert(p, vc, light, r, g, b, dx, dy, dz, uv[0], uv[3], nx, ny, nz);
    }

    private static void vert(PoseStack.Pose p, VertexConsumer vc, int light, float r, float g, float b,
                             float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        vc.addVertex(p, x, y, z).setColor(r, g, b, 1f).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
    }
}
