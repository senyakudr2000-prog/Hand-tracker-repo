package com.example.handtrack;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Крепление кисти к руке игрока.
 * Позу пальцев даёт HandTracker (в системе кисти), а место и поворот запястья берутся из настоящей руки игрока
 * (ModelPart руки) - поэтому кисть продолжает руку и ходит за игроком.
 * Мировые координаты суставов, посчитанные при отрисовке, публикуются сюда для хитбокса и захвата (серверный поток).
 */
public final class HandAttach {
    /** true - зеркалит кисть (если левая и правая получились наоборот / ладонь смотрит не туда). */
    public static final boolean MIRROR = false;
    private static final long MAX_AGE_MS = 250;

    public record WorldHand(Vec3[] joints, long time) {}

    private static final AtomicReferenceArray<WorldHand> WORLD = new AtomicReferenceArray<>(2);

    /** Суставы в мировых координатах или null, если кисть сейчас не рисуется/не отслеживается. */
    public static WorldHand get(int h) {
        WorldHand w = WORLD.get(h);
        if (w == null || HandTracker.pose(h) == null) return null;
        return System.currentTimeMillis() - w.time() > MAX_AGE_MS ? null : w;
    }

    /**
     * Суставы в системе координат части модели руки (блоки; +y вдоль руки к кисти).
     * Запястье - нижний торец руки: x = центр руки, y = 10 пикселей, z = 0.
     * Система кисти (x к большому пальцу, y к пальцам, z нормаль ладони) переводится так:
     * большой палец смотрит вперёд, пальцы продолжают руку, ладонь - к бедру.
     */
    public static Vec3[] armLocal(HandTracker.HandPose pose, boolean left, boolean slim) {
        double cx = (left ? 1.0 : -1.0) * (slim ? 0.5 : 1.0);
        Vec3 wrist = new Vec3(cx / 16.0, 10.0 / 16.0, 0);
        Vec3[] out = new Vec3[21];
        for (int i = 0; i < 21; i++) {
            Vec3 p = pose.hand()[i];
            out[i] = wrist.add(p.z, p.y, MIRROR ? p.x : -p.x);
        }
        return out;
    }

    /** От первого лица: матрица m переводит координаты руки в пространство камеры (x вправо, y вверх, z назад). */
    public static void publishFirstPerson(int h, Vec3[] armLocal, Matrix4f m, Camera cam) {
        Vector3f look = cam.getLookVector(), up = cam.getUpVector(), left = cam.getLeftVector();
        Vec3 c = cam.getPosition();
        Vec3[] out = new Vec3[21];
        Vector3f v = new Vector3f();
        for (int i = 0; i < 21; i++) {
            v.set((float) armLocal[i].x, (float) armLocal[i].y, (float) armLocal[i].z);
            m.transformPosition(v);
            out[i] = new Vec3(
                    c.x - v.x * left.x + v.y * up.x - v.z * look.x,
                    c.y - v.x * left.y + v.y * up.y - v.z * look.y,
                    c.z - v.x * left.z + v.y * up.z - v.z * look.z);
        }
        WORLD.set(h, new WorldHand(out, System.currentTimeMillis()));
    }

    /** От третьего лица: матрица m переводит координаты руки в мир относительно камеры. */
    public static void publishWorld(int h, Vec3[] armLocal, Matrix4f m, Vec3 camPos) {
        Vec3[] out = new Vec3[21];
        Vector3f v = new Vector3f();
        for (int i = 0; i < 21; i++) {
            v.set((float) armLocal[i].x, (float) armLocal[i].y, (float) armLocal[i].z);
            m.transformPosition(v);
            out[i] = new Vec3(camPos.x + v.x, camPos.y + v.y, camPos.z + v.z);
        }
        WORLD.set(h, new WorldHand(out, System.currentTimeMillis()));
    }
}
