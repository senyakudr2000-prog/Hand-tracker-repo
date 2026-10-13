package com.example.handtrack;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/**
 * Принимает UDP-пакеты: "idx,x0,y0,z0,...,x20,y20,z20".
 * idx: 0 = левая рука, 1 = правая. x,y в 0..1 (x растёт вправо от пользователя, y вниз), z как у MediaPipe.
 */
public final class HandTracker {
    public static final int PORT = 25566;
    public static final double REACH_SCALE = 1.6;
    public static final long TIMEOUT_MS = 300;

    private static final float[][] raw = new float[2][63];
    private static final long[] seen = new long[2];
    private static final float[][] smooth = new float[2][63];
    private static final boolean[] fresh = {true, true};

    public static final Vec3[][] world = new Vec3[2][21];
    public static final boolean[] visible = new boolean[2];

    public static void start() {
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

    /** Каждый кадр: сглаживание + перевод в мировые координаты перед камерой. */
    public static void update(Camera cam) {
        Vec3 eye = cam.getPosition();
        float yaw = cam.getYRot(), pitch = cam.getXRot();
        Vec3 f = Vec3.directionFromRotation(pitch, yaw);
        Vec3 u = Vec3.directionFromRotation(pitch - 90f, yaw);
        Vec3 r = f.cross(u);
        long now = System.currentTimeMillis();

        for (int h = 0; h < 2; h++) {
            synchronized (raw) {
                visible[h] = now - seen[h] < TIMEOUT_MS;
                if (!visible[h]) { fresh[h] = true; continue; }
                for (int i = 0; i < 63; i++) {
                    smooth[h][i] = fresh[h] ? raw[h][i] : Mth.lerp(0.5f, smooth[h][i], raw[h][i]);
                }
                fresh[h] = false;
            }
            float[] s = smooth[h];
            double size = Math.max(0.02, Math.hypot(s[0] - s[27], s[1] - s[28])); // кисть -> основание среднего пальца
            double depth = Mth.clamp(0.1 / size, 0.5, 3.0);
            double w = depth * REACH_SCALE;
            for (int i = 0; i < 21; i++) {
                double x = s[i * 3], y = s[i * 3 + 1], z = s[i * 3 + 2];
                world[h][i] = eye
                        .add(f.scale(depth + z * w))
                        .add(r.scale((x - 0.5) * w))
                        .add(u.scale((0.5 - y) * w * 0.75));
            }
        }
    }
}
