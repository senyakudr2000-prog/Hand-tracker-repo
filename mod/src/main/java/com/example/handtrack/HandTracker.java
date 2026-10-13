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
 * Трекер отдаёт только ПОЗУ ПАЛЬЦЕВ в системе координат самой кисти (x поперёк ладони к большому пальцу,
 * y от запястья к пальцам, z по нормали ладони), в блоках. Куда эту кисть поставить - решает HandAttach
 * (она крепится к руке игрока).
 */
public final class HandTracker {
    public static final int PORT = 25566;
    public static final long TIMEOUT_MS = 300;
    public static final double PINCH_ON = 0.30, PINCH_OFF = 0.50;
    /** Длина ладони (запястье -> основание среднего пальца) в блоках. У Стива рука шириной 0.25. */
    public static final double PALM_LEN = 0.20;

    /** hand[i] - сустав i относительно запястья (оси камеры); pinch = щипок; wrist = (x, y запястья в кадре, размер ладони). */
    public record HandPose(Vec3[] hand, boolean pinch, Vec3 wrist) {}

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

    /** Каждый кадр (клиентский поток): сглаживание + перевод в систему координат кисти. */
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
            // условные "метры" из кадра: масштаб не важен, дальше всё нормируется по длине ладони
            Vec3[] cs = new Vec3[21];
            for (int i = 0; i < 21; i++) {
                cs[i] = new Vec3((s[i * 3] - 0.5), (0.5 - s[i * 3 + 1]) * 0.75, s[i * 3 + 2]);
            }
            double dx = cs[9].x - cs[0].x, dy = cs[9].y - cs[0].y;
            double palm2d = Math.sqrt(dx * dx + dy * dy);
            double palm = cs[9].subtract(cs[0]).length();
            if (palm < 1e-5) continue;
            double k = PALM_LEN / palm;
            // суставы относительно запястья в осях КАМЕРЫ (x вправо, y вверх, z от камеры) - ориентация кисти сохраняется
            Vec3[] hand = new Vec3[21];
            for (int i = 0; i < 21; i++) hand[i] = cs[i].subtract(cs[0]).scale(k);
            double ratio = hand[4].distanceTo(hand[8]) / PALM_LEN;
            pinchState[h] = pinchState[h] ? ratio < PINCH_OFF : ratio < PINCH_ON;
            POSES.set(h, new HandPose(hand, pinchState[h], new Vec3(cs[0].x, cs[0].y, palm2d)));
        }
    }
}
