package com.example.handtrack;

import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Две руки целиком (плечо -> локоть -> кисть), полностью заменяют ванильные руки.
 * Кисть ставится туда, где её видит камера (положение запястья + размер ладони = глубина),
 * локоть считается двухзвенным IK от плеча игрока. Всё считается в МИРОВЫХ координатах
 * (позиция игрока + его взгляд по горизонтали), поэтому в 1-м и 3-м лице руки одинаковые.
 *
 * Индексы в массиве суставов: 0..20 - кисть (как у MediaPipe), 21 - плечо, 22 - локоть.
 */
public final class HandAttach {
    // ---- настройки (подкручивайте под себя) ----
    /** Длина одного звена руки (плечо-локоть = локоть-запястье), блоков. Ванильная рука ~0.31, тут чуть длиннее. */
    public static final double ARM_SEG = 0.35;
    public static final double SHOULDER_SIDE = 0.31, SHOULDER_SIDE_SLIM = 0.28;
    public static final double SHOULDER_Y = 1.376, SHOULDER_Y_CROUCH = 1.10;
    /** Высота и центр "рабочей зоны" перед грудью (когда запястье в центре кадра). */
    public static final double CHEST_Y = 1.25, FWD_BASE = 0.35;
    /** Усиление: сдвиг руки в кадре -> сдвиг руки в игре. */
    public static final double GAIN_X = 1.4, GAIN_Y = 1.6;
    /** Глубина: размер ладони в кадре (0.10 = "обычное" расстояние). Больше ладонь -> рука дальше вперёд. */
    public static final double REF_PALM = 0.10, GAIN_D = 5.0;

    public static final int J_SHOULDER = 21, J_ELBOW = 22;
    private static final long MAX_AGE_MS = 250;

    public record WorldHand(Vec3[] joints, long time) {}

    private static final AtomicReferenceArray<WorldHand> WORLD = new AtomicReferenceArray<>(2);

    /** Суставы в мировых координатах или null, если рука сейчас не рисуется/не отслеживается. */
    public static WorldHand get(int h) {
        WorldHand w = WORLD.get(h);
        if (w == null || HandTracker.pose(h) == null) return null;
        return System.currentTimeMillis() - w.time() > MAX_AGE_MS ? null : w;
    }

    public static void publish(int h, Vec3[] world) {
        WORLD.set(h, new WorldHand(world, System.currentTimeMillis()));
    }

    /** Считает всю руку h (0 левая, 1 правая) в мировых координатах. */
    public static Vec3[] solve(HandTracker.HandPose pose, boolean left, boolean slim,
                               AbstractClientPlayer player, float pt) {
        double yaw = Math.toRadians(player.getViewYRot(pt));
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 base = player.getPosition(pt);

        double side = (left ? -1 : 1) * (slim ? SHOULDER_SIDE_SLIM : SHOULDER_SIDE);
        Vec3 shoulder = base.add(0, player.isCrouching() ? SHOULDER_Y_CROUCH : SHOULDER_Y, 0).add(right.scale(side));

        Vec3 w = pose.wrist(); // x вправо, y вверх (от центра кадра), z = размер ладони в кадре
        double depth = FWD_BASE + Mth.clamp((w.z - REF_PALM) * GAIN_D, -0.25, 0.45);
        Vec3 target = base.add(0, CHEST_Y, 0)
                .add(right.scale(w.x * GAIN_X))
                .add(up.scale(w.y * GAIN_Y))
                .add(fwd.scale(depth));

        // двухзвенный IK: запястье не дальше, чем достаёт рука
        Vec3 v = target.subtract(shoulder);
        double d = v.length();
        double max = 2 * ARM_SEG * 0.999;
        if (d < 1e-4) { v = fwd; d = 1e-4; }
        Vec3 dir = v.scale(1.0 / d);
        if (d > max) d = max;
        Vec3 wrist = shoulder.add(dir.scale(d));

        // локоть: вниз, чуть наружу и назад
        Vec3 outward = right.scale(left ? -1 : 1);
        Vec3 pole = new Vec3(0, -1, 0).add(outward.scale(0.5)).add(fwd.scale(-0.4));
        pole = pole.subtract(dir.scale(pole.dot(dir)));
        if (pole.lengthSqr() < 1e-8) pole = new Vec3(0, -1, 0);
        pole = pole.normalize();
        double half = d / 2;
        double hgt = Math.sqrt(Math.max(0, ARM_SEG * ARM_SEG - half * half));
        Vec3 elbow = shoulder.add(dir.scale(half)).add(pole.scale(hgt));

        Vec3[] out = new Vec3[23];
        for (int i = 0; i < 21; i++) {
            Vec3 p = pose.hand()[i]; // в осях камеры: x вправо, y вверх, z от камеры (к камере = вперёд)
            out[i] = wrist.add(right.scale(p.x)).add(up.scale(p.y)).add(fwd.scale(-p.z));
        }
        out[J_SHOULDER] = shoulder;
        out[J_ELBOW] = elbow;
        return out;
    }

    /** От первого лица: мир -> пространство камеры (x вправо, y вверх, z назад). */
    public static Vec3[] toCameraSpace(Vec3[] w, Camera cam) {
        Vector3f look = cam.getLookVector(), up = cam.getUpVector(), left = cam.getLeftVector();
        Vec3 c = cam.getPosition();
        Vec3[] out = new Vec3[w.length];
        for (int i = 0; i < w.length; i++) {
            double dx = w[i].x - c.x, dy = w[i].y - c.y, dz = w[i].z - c.z;
            out[i] = new Vec3(
                    -(dx * left.x + dy * left.y + dz * left.z),
                    dx * up.x + dy * up.y + dz * up.z,
                    -(dx * look.x + dy * look.y + dz * look.z));
        }
        return out;
    }

    /** От третьего лица: мир -> система координат, в которой сейчас стоит PoseStack (poseMatrix = ps.last().pose()). */
    public static Vec3[] toModelSpace(Vec3[] w, Matrix4f poseMatrix, Vec3 camPos) {
        Matrix4f inv = new Matrix4f(poseMatrix).invert();
        Vec3[] out = new Vec3[w.length];
        Vector3f v = new Vector3f();
        for (int i = 0; i < w.length; i++) {
            v.set((float) (w[i].x - camPos.x), (float) (w[i].y - camPos.y), (float) (w[i].z - camPos.z));
            inv.transformPosition(v);
            out[i] = new Vec3(v.x, v.y, v.z);
        }
        return out;
    }
}
