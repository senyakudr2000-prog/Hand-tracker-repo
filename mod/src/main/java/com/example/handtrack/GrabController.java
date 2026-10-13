package com.example.handtrack;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Щипок: большой (4) + указательный (8).
 *  - Блок: превращается в физическое тело Sable (sub-level) и "притягивается" к пальцам по скорости,
 *    поэтому сталкивается с миром, вращается и красиво улетает при броске.
 *  - Существо: удерживается телепортом, отпускается с броском.
 * Клиентская часть считает щипок; серверная (поток встроенного сервера) двигает объекты.
 */
public final class GrabController {
    // --- настройки ---
    /** Во сколько раз скорость тел Sable больше "блоков за тик". Если Sable считает в блоках/сек - 20. Если броски слишком слабые/сильные или тело дрожит - меняйте. */
    static final double PHYS_VEL_PER_BLOCK_PER_TICK = 20.0;
    static final double HOLD_GAIN = 0.5;            // какую долю расстояния до пальцев закрывать за тик
    static final double MAX_HOLD_SPEED = 1.0;       // блоков/тик
    static final double ANGULAR_DAMP = 0.5;         // гашение вращения при удержании (0..1)
    static final double THROW_SCALE = 1.0;
    static final double MAX_THROW = 1.5;            // блоков/тик
    static final double PINCH_ON = 0.30, PINCH_OFF = 0.50;
    static final double BLOCK_MARGIN = 0.15, ENTITY_MARGIN = 0.15, SUBLEVEL_RADIUS = 0.75;
    static final int MAX_LOOSE = 40;                // лимит физических блоков (нагрузка на телефон)

    // --- клиентская сторона ---
    public static final boolean[] pinching = new boolean[2];
    private static final Vec3[] last = new Vec3[2];
    private static final Vec3[] velEma = {Vec3.ZERO, Vec3.ZERO};

    private static final class Shared {
        volatile boolean pinch;
        volatile Vec3 point = Vec3.ZERO;
        volatile Vec3 vel = Vec3.ZERO;
    }
    private static final Shared[] SHARED = {new Shared(), new Shared()};
    private static volatile UUID playerId;

    public static void clientTick(Minecraft mc) {
        if (mc.player == null || mc.getSingleplayerServer() == null) {
            for (int h = 0; h < 2; h++) { pinching[h] = false; SHARED[h].pinch = false; }
            return;
        }
        playerId = mc.player.getUUID();
        for (int h = 0; h < 2; h++) {
            if (!HandTracker.visible[h] || HandTracker.world[h][0] == null) {
                pinching[h] = false; SHARED[h].pinch = false; last[h] = null; velEma[h] = Vec3.ZERO;
                continue;
            }
            Vec3[] w = HandTracker.world[h];
            double size = Math.max(1e-4, w[0].distanceTo(w[9]));
            double ratio = w[4].distanceTo(w[8]) / size;
            boolean was = pinching[h];
            pinching[h] = was ? ratio < PINCH_OFF : ratio < PINCH_ON;

            Vec3 p = w[4].add(w[8]).scale(0.5);
            Vec3 raw = last[h] == null ? Vec3.ZERO : p.subtract(last[h]);
            last[h] = p;
            velEma[h] = velEma[h].scale(0.5).add(raw.scale(0.5));

            Shared s = SHARED[h];
            s.point = p;
            s.vel = velEma[h];
            s.pinch = pinching[h];
        }
    }

    // --- серверная сторона (поток встроенного сервера) ---
    private static final class Held {
        ServerSubLevel sub;
        Entity entity;
        boolean entityWasNoGravity;
        ServerLevel level;
    }
    private static final Held[] held = new Held[2];
    private static final boolean[] latch = new boolean[2];
    private static final List<ServerSubLevel> loose = new ArrayList<>();

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        UUID pid = playerId;
        if (pid == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(pid);
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        loose.removeIf(ServerSubLevel::isRemoved);

        for (int h = 0; h < 2; h++) {
            Shared s = SHARED[h];
            if (held[h] != null && held[h].level != level) { release(held[h], Vec3.ZERO); held[h] = null; }

            if (s.pinch) {
                if (held[h] == null && !latch[h]) {
                    latch[h] = true;               // одна попытка захвата на один щипок
                    held[h] = tryGrab(level, player, s.point);
                } else if (held[h] != null) {
                    hold(held[h], s.point);
                }
            } else {
                latch[h] = false;
                if (held[h] != null) { release(held[h], s.vel); held[h] = null; }
            }
        }
    }

    private static Held tryGrab(ServerLevel level, ServerPlayer player, Vec3 p) {
        try {
            // 1) ранее взятые физические блоки
            ServerSubLevel bestSub = null;
            double best = SUBLEVEL_RADIUS * SUBLEVEL_RADIUS;
            for (ServerSubLevel s : loose) {
                Vector3d sp = s.logicalPose().position();
                double d = p.distanceToSqr(sp.x, sp.y, sp.z);
                if (d < best) { best = d; bestSub = s; }
            }
            if (bestSub != null) return heldSub(bestSub, level);

            // 2) существа
            Entity target = null;
            double bd = Double.MAX_VALUE;
            for (Entity en : level.getEntities((Entity) player, new AABB(p, p).inflate(1.5),
                    en -> !(en instanceof Player) && en.getBoundingBox().inflate(ENTITY_MARGIN).contains(p))) {
                double d = en.getBoundingBox().getCenter().distanceToSqr(p);
                if (d < bd) { bd = d; target = en; }
            }
            if (target != null) {
                Held h = new Held();
                h.entity = target; h.level = level; h.entityWasNoGravity = target.isNoGravity();
                return h;
            }

            // 3) блок -> физическое тело Sable
            BlockPos bp = BlockPos.containing(p);
            BlockState st = level.getBlockState(bp);
            if (!st.isAir() && st.getDestroySpeed(level, bp) >= 0 && !st.hasBlockEntity() && st.getFluidState().isEmpty()) {
                var shape = st.getShape(level, bp);
                if (!shape.isEmpty() && shape.bounds().move(bp).inflate(BLOCK_MARGIN).contains(p)) {
                    if (loose.size() >= MAX_LOOSE) {
                        player.displayClientMessage(Component.literal("Слишком много физических блоков (" + MAX_LOOSE + ")"), true);
                        return null;
                    }
                    ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, bp, List.of(bp),
                            new BoundingBox3i(bp.getX(), bp.getY(), bp.getZ(), bp.getX(), bp.getY(), bp.getZ()));
                    loose.add(sub);
                    return heldSub(sub, level);
                }
            }
        } catch (Throwable t) {
            System.err.println("[HandTrack] grab failed: " + t);
            t.printStackTrace();
        }
        return null;
    }

    private static Held heldSub(ServerSubLevel sub, ServerLevel level) {
        Held h = new Held();
        h.sub = sub; h.level = level;
        return h;
    }

    private static void hold(Held h, Vec3 p) {
        try {
            if (h.sub != null) {
                if (h.sub.isRemoved()) return;
                ServerSubLevelContainer c = SubLevelContainer.getContainer(h.level);
                if (c == null) return;
                SubLevelPhysicsSystem sys = c.physicsSystem();
                RigidBodyHandle handle = sys.getPhysicsHandle(h.sub);
                PhysicsPipeline pipe = sys.getPipeline();

                Vector3d pos = new Vector3d(h.sub.logicalPose().position());
                Vector3d v = handle.getLinearVelocity(new Vector3d());
                Vector3d w = handle.getAngularVelocity(new Vector3d());

                Vector3d wantStep = new Vector3d(p.x - pos.x, p.y - pos.y, p.z - pos.z).mul(HOLD_GAIN);
                double len = wantStep.length();
                if (len > MAX_HOLD_SPEED) wantStep.mul(MAX_HOLD_SPEED / len);
                Vector3d wantVel = wantStep.mul(PHYS_VEL_PER_BLOCK_PER_TICK);

                Vector3d dv = wantVel.sub(v, new Vector3d());
                Vector3d dw = w.mul(-ANGULAR_DAMP, new Vector3d());
                pipe.addLinearAndAngularVelocity(h.sub, dv, dw);
            } else if (h.entity != null && !h.entity.isRemoved()) {
                Entity e = h.entity;
                e.setNoGravity(true);
                e.setDeltaMovement(Vec3.ZERO);
                e.fallDistance = 0;
                if (e instanceof Mob m) m.getNavigation().stop();
                e.moveTo(p.x, p.y - e.getBbHeight() / 2.0, p.z, e.getYRot(), e.getXRot());
                e.hurtMarked = true;
            }
        } catch (Throwable t) {
            System.err.println("[HandTrack] hold failed: " + t);
        }
    }

    private static void release(Held h, Vec3 handVel) {
        try {
            Vec3 v = handVel.scale(THROW_SCALE);
            if (v.length() > MAX_THROW) v = v.normalize().scale(MAX_THROW);
            if (h.sub != null) {
                if (h.sub.isRemoved()) return;
                ServerSubLevelContainer c = SubLevelContainer.getContainer(h.level);
                if (c == null) return;
                SubLevelPhysicsSystem sys = c.physicsSystem();
                RigidBodyHandle handle = sys.getPhysicsHandle(h.sub);
                Vector3d cur = handle.getLinearVelocity(new Vector3d());
                Vector3d want = new Vector3d(v.x, v.y, v.z).mul(PHYS_VEL_PER_BLOCK_PER_TICK);
                sys.getPipeline().addLinearAndAngularVelocity(h.sub, want.sub(cur, new Vector3d()), new Vector3d());
            } else if (h.entity != null && !h.entity.isRemoved()) {
                h.entity.setNoGravity(h.entityWasNoGravity);
                h.entity.setDeltaMovement(v);
                h.entity.hurtMarked = true;
            }
        } catch (Throwable t) {
            System.err.println("[HandTrack] release failed: " + t);
        }
    }
}
