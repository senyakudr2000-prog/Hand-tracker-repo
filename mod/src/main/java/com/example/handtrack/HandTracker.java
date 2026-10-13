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
 * Конвейер сглаживания:
 *  1) UDP-поток: отбрасывание скачков запястья + фильтр One Euro (убирает дрожь, почти не добавляет задержку
 *     при быстром движении) + оценка скорости каждой точки;
 *  2) каждый кадр рендера: экстраполяция вперёд на время с последнего пакета (не больше EXTRAP_MAX) и
 *     догоняющее сглаживание, не зависящее от FPS. Благодаря этому рука двигается плавно на каждом кадре,
 *     а не "ступеньками" с частотой камеры.
 *
 * Трекер НЕ привязан к камере: он отдаёт позу руки в собственной системе координат
 * (x вправо, y вверх, z вперёд, в блоках). В мир её переводит HandEntity (стоит на споне).
 * Локоть камера не видит, поэтому он оценивается (см. elbow()).
 */
public final class HandTracker {
    public static final int PORT = 25566;
    public static final double REACH_SCALE = 1.6;
    /** Сколько руку не видно, прежде чем она исчезнет. Короткие пропуски детекции просто "удерживаются". */
    public static final long TIMEOUT_MS = 450;
    public static final double PINCH_ON = 0.30, PINCH_OFF = 0.50;
    /** Во сколько раз увеличить кисть (вокруг центра ладони), чтобы она была размером с руку игрока. */
    public static final double HAND_SCALE = 2.0;
    /** Длина предплечья в длинах ладони. */
    public static final double ARM_LEN_PER_PALM = 3.2;

    // ---- сглаживание (если рука дрожит - уменьши MIN_CUTOFF; если отстаёт при быстрых движениях - увеличь BETA) ----
    private static final double MIN_CUTOFF = 1.5;   // Гц, срез на неподвижной руке
    private static final double BETA = 6.0;         // на сколько растёт срез со скоростью движения
    private static final double D_CUTOFF = 1.0;     // Гц, срез фильтра скорости
    private static final double JUMP_BASE = 0.20;   // макс. смещение запястья между пакетами (доля кадра) ...
    private static final double JUMP_SPEED = 4.0;   // ... плюс столько на каждую секунду между пакетами
    private static final int JUMP_REJECT = 2;       // сколько подряд "прыжков" считать выбросом, потом принять
    private static final double EXTRAP_MAX = 0.05;  // с, максимум экстраполяции вперёд
    private static final double EXTRAP_GAIN = 0.8;
    private static final double RENDER_TAU = 0.030; // с, догоняющее сглаживание на кадре рендера
    private static final double DEPTH_TAU = 0.12;   // с, сглаживание "глубины" (размер кисти шумит сильнее всего)
    private static final int PINCH_FRAMES = 2;      // сколько кадров подряд должен держаться щипок/отпускание

    // ---- предплечье ----
    /** Плечи в системе руки (вправо, вверх, вперёд): индекс 0 = левая рука, 1 = правая. Локоть тянется в их сторону. */
    private static final double[][] SHOULDER = {{-0.30, -0.45, -0.10}, {0.30, -0.45, -0.10}};
    private static final double W_SHOULDER = 0.55, W_HAND = 0.45, ELBOW_DROP = 0.25;
    private static final double ELBOW_TAU = 0.15;

    /** Снимок позы: cs[i] = (вправо, вверх, вперёд) для сустава i; pinch = щипок; elbow = оценка локтя. */
    public record HandPose(Vec3[] cs, boolean pinch, Vec3 elbow) {}

    // --- общее состояние: пишет UDP-поток, читает рендер; всё под LOCK ---
    private static final Object LOCK = new Object();
    private static final double[][] xHat = new double[2][63];
    private static final double[][] dxHat = new double[2][63];
    private static final long[] tPrevNs = new long[2];
    private static final long[] seenMs = new long[2];
    private static final boolean[] hasFilter = new boolean[2];
    private static final int[] jumpCount = new int[2];

    // --- состояние только потока рендера ---
    private static final double[][] target = new double[2][63];
    private static final double[][] cur = new double[2][63];
    private static final double[] depthS = new double[2];
    private static final Vec3[] elbowDir = new Vec3[2];
    private static final long[] lastUpdNs = new long[2];
    private static final boolean[] fresh = {true, true};
    private static final boolean[] pinchState = new boolean[2];
    private static final int[] pinchFlip = new int[2];

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
            int idx = Integer.parseInt(a[0].trim());
            if (idx < 0 || idx > 1) return;
            double[] v = new double[63];
            for (int i = 0; i < 63; i++) {
                v[i] = Double.parseDouble(a[i + 1]);
                if (!Double.isFinite(v[i])) return;
            }
            accept(idx, v, System.nanoTime());
        } catch (Exception ignored) {}
    }

    private static double alpha(double cutoff, double dt) {
        double tau = 1.0 / (2.0 * Math.PI * cutoff);
        return 1.0 / (1.0 + tau / dt);
    }

    private static void resetFilter(int h, double[] v, long nowNs) {
        System.arraycopy(v, 0, xHat[h], 0, 63);
        java.util.Arrays.fill(dxHat[h], 0.0);
        tPrevNs[h] = nowNs;
        hasFilter[h] = true;
        jumpCount[h] = 0;
    }

    /** UDP-поток: новый пакет -> отбрасывание выбросов + фильтр One Euro. */
    private static void accept(int h, double[] v, long nowNs) {
        synchronized (LOCK) {
            seenMs[h] = System.currentTimeMillis();
            if (!hasFilter[h]) {
                resetFilter(h, v, nowNs);
                return;
            }
            double dt = Math.max((nowNs - tPrevNs[h]) / 1e9, 0.004);
            double jump = Math.hypot(v[0] - xHat[h][0], v[1] - xHat[h][1]);
            if (jump > JUMP_BASE + JUMP_SPEED * dt) {
                // слишком резкий скачок запястья (обычно перепутались руки или ложная детекция): пропускаем
                if (++jumpCount[h] <= JUMP_REJECT) return;
                resetFilter(h, v, nowNs); // скачок держится - значит рука правда там
                return;
            }
            jumpCount[h] = 0;
            double[] x = xHat[h], dx = dxHat[h];
            double aD = alpha(D_CUTOFF, dt);
            for (int i = 0; i < 63; i++) {
                double d = (v[i] - x[i]) / dt;
                dx[i] += aD * (d - dx[i]);
                x[i] += alpha(MIN_CUTOFF + BETA * Math.abs(dx[i]), dt) * (v[i] - x[i]);
            }
            tPrevNs[h] = nowNs;
        }
    }

    /** Каждый кадр (клиентский поток): экстраполяция + сглаживание + перевод в систему координат руки. */
    public static void update() {
        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        for (int h = 0; h < 2; h++) {
            synchronized (LOCK) {
                boolean visible = hasFilter[h] && nowMs - seenMs[h] < TIMEOUT_MS;
                if (!visible) {
                    hasFilter[h] = false;
                    fresh[h] = true;
                    pinchState[h] = false;
                    pinchFlip[h] = 0;
                    POSES.set(h, null);
                    continue;
                }
                double ahead = Math.min(Math.max((nowNs - tPrevNs[h]) / 1e9, 0.0), EXTRAP_MAX) * EXTRAP_GAIN;
                for (int i = 0; i < 63; i++) target[h][i] = xHat[h][i] + dxHat[h][i] * ahead;
            }

            double dt = fresh[h] ? 1.0 : Mth.clamp((nowNs - lastUpdNs[h]) / 1e9, 0.0, 0.1);
            lastUpdNs[h] = nowNs;
            double a = fresh[h] ? 1.0 : 1.0 - Math.exp(-dt / RENDER_TAU);
            double[] s = cur[h];
            for (int i = 0; i < 63; i++) s[i] += (target[h][i] - s[i]) * a;

            double size = Math.max(0.02, Math.hypot(s[0] - s[27], s[1] - s[28])); // кисть -> основание среднего пальца
            double depthRaw = Mth.clamp(0.1 / size, 0.5, 3.0);
            depthS[h] = fresh[h] ? depthRaw : depthS[h] + (depthRaw - depthS[h]) * (1.0 - Math.exp(-dt / DEPTH_TAU));
            double depth = depthS[h];
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
            boolean want = pinchState[h] ? ratio < PINCH_OFF : ratio < PINCH_ON;
            if (want != pinchState[h]) {
                if (++pinchFlip[h] >= PINCH_FRAMES) { pinchState[h] = want; pinchFlip[h] = 0; }
            } else {
                pinchFlip[h] = 0;
            }

            Vec3 elbow = elbow(h, cs, dt);
            fresh[h] = false;
            POSES.set(h, new HandPose(cs, pinchState[h], elbow));
        }
    }

    /**
     * Локоть камера не видит, поэтому он ОЦЕНИВАЕТСЯ: предплечье идёт от запястья назад в сторону плеча
     * (плечо - фиксированная точка рядом с "глазом"), смешанную с продолжением оси ладони назад и небольшим
     * провисанием вниз. Направление сглажено, длина постоянна (ARM_LEN_PER_PALM длин ладони).
     */
    private static Vec3 elbow(int h, Vec3[] cs, double dt) {
        Vec3 down = new Vec3(0, -1, 0);
        Vec3 wrist = cs[0];

        Vec3 back = wrist.subtract(cs[9]);
        back = back.lengthSqr() < 1e-10 ? down : back.normalize();

        Vec3 toShoulder = new Vec3(SHOULDER[h][0], SHOULDER[h][1], SHOULDER[h][2]).subtract(wrist);
        toShoulder = toShoulder.lengthSqr() < 1e-10 ? down : toShoulder.normalize();

        Vec3 want = toShoulder.scale(W_SHOULDER).add(back.scale(W_HAND)).add(0, -ELBOW_DROP, 0);
        want = want.lengthSqr() < 1e-10 ? down : want.normalize();

        Vec3 dir = elbowDir[h];
        if (fresh[h] || dir == null) {
            dir = want;
        } else {
            double k = 1.0 - Math.exp(-dt / ELBOW_TAU);
            Vec3 m = dir.add(want.subtract(dir).scale(k));
            dir = m.lengthSqr() < 1e-10 ? want : m.normalize();
        }
        elbowDir[h] = dir;

        double palm = cs[0].distanceTo(cs[9]);
        return wrist.add(dir.scale(palm * ARM_LEN_PER_PALM));
    }
}
