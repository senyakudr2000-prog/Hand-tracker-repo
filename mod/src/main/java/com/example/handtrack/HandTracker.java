package com.example.handtrack;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Принимает UDP-пакеты: "idx,x0,y0,z0,...,x20,y20,z20".
 * idx: 0 = левая рука, 1 = правая. x,y в 0..1 (x растёт вправо от пользователя, y вниз), z как у MediaPipe.
 *
 * Теперь трекер НЕ привязан к камере: он отдаёт позу руки в собственной системе координат
 * (x вправо, y вверх, z вперёд, в блоках). В мир её переводит HandEntity (стоит на споне).
 */
public final class HandTracker {
    public static final int PORT = 25566;
    public static final double REACH_SCALE = 1.6;
    public static final long TIMEOUT_MS = 300;
    public static final double PINCH_ON = 0.30, PINCH_OFF = 0.50;
    /** Во сколько раз увеличить кисть (вокруг центра ладони), чтобы она была размером с руку игрока. */
    public static final double HAND_SCALE = 2.0;

    /** Снимок позы: cs[i] = (вправо, вверх, вперёд) для сустава i; pinch = щипок большим+указательным. */
    public record HandPose(Vec3[] cs, boolean pinch) {}

    private static final float[][] raw = new float[2][63];
    private static final long[] seen = new long[2];
    private static final float[][] smooth = new float[2][63];
    private static final boolean[] fresh = {true, true};
    private static final boolean[] pinchState = new boolean[2];
    private static final AtomicReferenceArray<HandPose> POSES = new AtomicReferenceArray<>(2);
    private static volatile boolean started;

    /** Текущая поза руки h (0/1) или null, если руку не видно. Безопасно читать из любого потока. */
    public static HandPose pose(int h) {
        return POSES.get(h);
    }

    public static void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(PORT)) {
                byte[] buf = new byte[4096];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (true) {
                    p.setLength(buf.length);
                    s.receive(p);
                    parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                System.err.println("[HandTrack] UDP error: " + e);
            }
        }, "HandTrack-UDP");
        t.setDaemon(true);
        t.start();
    }

    private static void parse(String msg) {
        try {
            String[] a = msg.trim().split(",");
            if (a.length < 64) return;
            int idx = Integer.parseInt(a[0]);
            if (idx < 0 || idx > 1) return;
            synchronized (raw) {
                for (int i = 0; i < 63; i++) raw[idx][i] = Float.parseFloat(a[i + 1]);
                seen[idx] = System.currentTimeMillis();
            }
        } catch (Exception ignored) {}
    }

    /** Каждый кадр (клиентский поток): сглаживание + перевод в систему координат руки. */
    public static void update() {
        long now = System.currentTimeMillis();
        for (int h = 0; h < 2; h++) {
            synchronized (raw) {
                boolean visible = now - seen[h] < TIMEOUT_MS;
                if (!visible) {
                    fresh[h] = true;
                    pinchState[h] = false;
                    POSES.set(h, null);
                    continue;
                }
                for (int i = 0; i < 63; i++) {
                    smooth[h][i] = fresh[h] ? raw[h][i] : Mth.lerp(0.5f, smooth[h][i], raw[h][i]);
                }
                fresh[h] = false;
            }
            float[] s = smooth[h];
            double size = Math.max(0.02, Math.hypot(s[0] - s[27], s[1] - s[28])); // кисть -> основание среднего пальца
            double depth = Mth.clamp(0.1 / size, 0.5, 3.0);
            double w = depth * REACH_SCALE;
            Vec3[] cs = new Vec3[21];
            for (int i = 0; i < 21; i++) {
                double x = s[i * 3], y = s[i * 3 + 1], z = s[i * 3 + 2];
                cs[i] = new Vec3((x - 0.5) * w, (0.5 - y) * w * 0.75, depth + z * w);
            }
            Vec3 c0 = cs[9];
            for (int i = 0; i < 21; i++) cs[i] = c0.add(cs[i].subtract(c0).scale(HAND_SCALE));
            double palm = Math.max(1e-4, cs[0].distanceTo(cs[9]));
            double ratio = cs[4].distanceTo(cs[8]) / palm;
            pinchState[h] = pinchState[h] ? ratio < PINCH_OFF : ratio < PINCH_ON;
            POSES.set(h, new HandPose(cs, pinchState[h]));
        }
    }
}
