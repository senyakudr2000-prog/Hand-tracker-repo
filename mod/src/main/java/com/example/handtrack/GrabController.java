package com.example.handtrack;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Щипок: большой (4) + указательный (8) пальцы кисти, прикреплённой к руке игрока (HandEntity ходит за игроком).
 *  - Физическое тело Sable (любой sub-level: и взятый ранее блок, и собранное командой/кораблём) -
 *    "притягивается" к пальцам по скорости, сталкивается с миром и красиво улетает при броске.
 *  - Существо: удерживается телепортом, отпускается с броском.
 *  - Обычный блок мира: превращается в sub-level Sable и берётся как выше.
 * Всё считается на потоке встроенного сервера.
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
    static final double BLOCK_MARGIN = 0.15, ENTITY_MARGIN = 0.15, SUBLEVEL_PROBE = 0.5;
    static final boolean ALLOW_BLOCK_ENTITIES = true; // брать и блоки с блок-сущностями (сундуки, блоки из модов, в т.ч. Sable)
    static final int MAX_LOOSE = 40;                // лимит физических блоков (нагрузка на телефон)
    static final int ENSURE_PERIOD = 40;            // раз в сколько тиков проверять, что обе руки существуют

    // --- клиентская сторона: только запоминаем, кто играет ---
    private static volatile UUID playerId;

    public static void clientTick(Minecraft mc) {
        playerId = (mc.player == null || mc.getSingleplayerServer() == null) ? null : mc.player.getUUID();
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
    private static final Vec3[] last = new Vec3[2];
    private static final Vec3[] velEma = {Vec3.ZERO, Vec3.ZERO};
    private static final HandEntity[] hands = new HandEntity[2];
    private static final List<ServerSubLevel> loose = new ArrayList<>();
    private static int ticker;

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        UUID pid = playerId;
        if (pid == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(pid);
        if (player == null) return;
        ServerLevel level = player.serverLevel();

        if (ticker++ % ENSURE_PERIOD == 0 || hands[0] == null || hands[1] == null) ensureHands(level, player);
        loose.removeIf(ServerSubLevel::isRemoved);

        for (int h = 0; h < 2; h++) {
            HandEntity he = hands[h];
            if (he == null || he.isRemoved() || he.level() != level) {
                hands[h] = null;
                dropHeld(h);
                continue;
            }
            if (held[h] != null && held[h].level != level) dropHeld(h);

            HandTracker.HandPose pose = HandTracker.pose(h);
            HandAttach.WorldHand wh = pose == null ? null : HandAttach.get(h);
            if (wh == null) { // руку не видно / она сейчас не рисуется
                latch[h] = false; last[h] = null; velEma[h] = Vec3.ZERO;
                dropHeld(h);
                he.setPos(player.getX(), player.getY(), player.getZ());
                continue;
            }

            Vec3[] j = wh.joints();
            he.setPos(j[9].x, j[9].y, j[9].z);
            Vec3 p = j[4].add(j[8]).scale(0.5);
            Vec3 raw = last[h] == null ? Vec3.ZERO : p.subtract(last[h]);
            last[h] = p;
            velEma[h] = velEma[h].scale(0.5).add(raw.scale(0.5));

            if (pose.pinch()) {
                if (held[h] == null && !latch[h]) {
                    latch[h] = true;               // одна попытка захвата на один щипок
                    held[h] = tryGrab(level, player, p, h);
                } else if (held[h] != null) {
                    hold(held[h], p);
                }
            } else {
                latch[h] = false;
                if (held[h] != null) { release(held[h], velEma[h]); held[h] = null; }
            }
        }
    }

    /** По одной кисти-сущности на руку; живут рядом с игроком, при смене измерения создаются заново. */
    private static void ensureHands(ServerLevel level, ServerPlayer player) {
        List<? extends HandEntity> found = level.getEntities(EntityTypeTest.forClass(HandEntity.class), en -> !en.isRemoved());
        for (HandEntity he : found) {
            if (he != hands[0] && he != hands[1]) he.discard(); // старые/лишние (в т.ч. из прошлых версий)
        }
        for (int h = 0; h < 2; h++) {
            if (hands[h] != null && !hands[h].isRemoved() && hands[h].level() == level) continue;
            if (hands[h] != null) hands[h].discard();
            HandEntity he = HandTrackingMod.HAND.get().create(level);
            if (he == null) { hands[h] = null; continue; }
            he.setHandIndex(h);
            he.setPos(player.getX(), player.getY(), player.getZ());
            level.addFreshEntity(he);
            hands[h] = he;
        }
    }

    private static void dropHeld(int h) {
        if (held[h] != null) { release(held[h], Vec3.ZERO); held[h] = null; }
    }

    private static boolean isHeldByOther(ServerSubLevel sub, int hand) {
        for (int o = 0; o < 2; o++) if (o != hand && held[o] != null && held[o].sub == sub) return true;
        return false;
    }

    private static Held tryGrab(ServerLevel level, ServerPlayer player, Vec3 p, int hand) {
        try {
            // 1) любое физическое тело Sable рядом с пальцами (взятые ранее блоки, собранные командой, корабли)
            ServerSubLevel sub = findSubLevel(level, p, hand);
            if (sub != null) return heldSub(sub, level);

            // 2) существа (саму руку не берём)
            Entity target = null;
            double bd = Double.MAX_VALUE;
            for (Entity en : level.getEntities((Entity) player, new AABB(p, p).inflate(1.5),
                    en -> !(en instanceof Player) && !(en instanceof HandEntity)
                            && en.getBoundingBox().inflate(ENTITY_MARGIN).contains(p))) {
                double d = en.getBoundingBox().getCenter().distanceToSqr(p);
                if (d < bd) { bd = d; target = en; }
            }
            if (target != null) {
                Held h = new Held();
                h.entity = target; h.level = level; h.entityWasNoGravity = target.isNoGravity();
                return h;
            }

            // 3) обычный блок мира -> физическое тело Sable
            BlockPos bp = BlockPos.containing(p);
            BlockState st = level.getBlockState(bp);
            if (grabbable(st, level, bp)) {
                VoxelShape shape = st.getShape(level, bp);
                if (!shape.isEmpty() && shape.bounds().move(bp).inflate(BLOCK_MARGIN).contains(p)) {
                    if (loose.size() >= MAX_LOOSE) {
                        player.displayClientMessage(Component.literal("Слишком много физических блоков (" + MAX_LOOSE + ")"), true);
                        return null;
                    }
                    ServerSubLevel made = SubLevelAssemblyHelper.assembleBlocks(level, bp, List.of(bp),
                            new BoundingBox3i(bp.getX(), bp.getY(), bp.getZ(), bp.getX(), bp.getY(), bp.getZ()));
                    loose.add(made);
                    return heldSub(made, level);
                }
            }
        } catch (Throwable t) {
            System.err.println("[HandTrack] grab failed: " + t);
            t.printStackTrace();
        }
        return null;
    }

    private static boolean grabbable(BlockState st, ServerLevel level, BlockPos bp) {
        return !st.isAir()
                && st.getDestroySpeed(level, bp) >= 0
                && (ALLOW_BLOCK_ENTITIES || !st.hasBlockEntity())
                && st.getFluidState().isEmpty();
    }

    /**
     * Блоки sub-level лежат в "плоте" далеко от мира, поэтому level.getBlockState(мировая точка) их не видит.
     * Берём sub-level, чья рамка задевает точку, переводим точку в координаты плота и ищем блок рядом.
     */
    private static ServerSubLevel findSubLevel(ServerLevel level, Vec3 p, int hand) {
        ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
        if (c == null) return null;
        BoundingBox3d probe = new BoundingBox3d(p.x - SUBLEVEL_PROBE, p.y - SUBLEVEL_PROBE, p.z - SUBLEVEL_PROBE,
                p.x + SUBLEVEL_PROBE, p.y + SUBLEVEL_PROBE, p.z + SUBLEVEL_PROBE);
        ServerSubLevel best = null;
        double bestD = Double.MAX_VALUE;
        for (SubLevel sl : c.queryIntersecting(probe)) {
            if (!(sl instanceof ServerSubLevel sub) || sub.isRemoved() || isHeldByOther(sub, hand)) continue;
            Vec3 lp = sub.logicalPose().transformPositionInverse(p);
            double d = nearestBlockDistSqr(level, lp);
            if (d < bestD) { bestD = d; best = sub; }
        }
        return best;
    }

    /** Расстояние^2 до центра ближайшего блока (в координатах плота), в чьей рамке (+запас) лежит точка; иначе MAX_VALUE. */
    private static double nearestBlockDistSqr(ServerLevel level, Vec3 lp) {
        BlockPos base = BlockPos.containing(lp);
        double best = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos bp = base.offset(dx, dy, dz);
            BlockState st = level.getBlockState(bp);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            VoxelShape shape = st.getShape(level, bp);
            if (shape.isEmpty()) continue;
            AABB bb = shape.bounds().move(bp).inflate(BLOCK_MARGIN);
            if (!bb.contains(lp)) continue;
            best = Math.min(best, bb.getCenter().distanceToSqr(lp));
        }
        return best;
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
