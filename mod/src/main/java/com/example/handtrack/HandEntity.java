package com.example.handtrack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Рука как сущность. Стоит на споне мира и никуда не двигается; скелет пальцев берётся из HandTracker
 * и разворачивается по повороту сущности (yaw запоминается при создании).
 * Хитбокс = коробка вокруг всех 21 сустава, обновляется каждый тик (виден по F3+B).
 */
public class HandEntity extends Entity {
    /** Высота "глаз" над позицией сущности: от этой точки рука отсчитывается вперёд. */
    public static final double ORIGIN_Y = 1.5;
    private static final double BOX_PAD = 0.04;

    private static final EntityDataAccessor<Integer> HAND_INDEX =
            SynchedEntityData.defineId(HandEntity.class, EntityDataSerializers.INT);

    public HandEntity(EntityType<? extends HandEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public int handIndex() {
        return Mth.clamp(this.entityData.get(HAND_INDEX), 0, 1);
    }

    public void setHandIndex(int i) {
        this.entityData.set(HAND_INDEX, i);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(HAND_INDEX, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setHandIndex(tag.getInt("HandIndex"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("HandIndex", handIndex());
    }

    /** Точка из системы руки (вправо, вверх, вперёд) -> смещение от позиции сущности в мировых осях. */
    private Vec3 toWorld(Vec3 c) {
        Vec3 f = Vec3.directionFromRotation(0f, getYRot());
        Vec3 u = new Vec3(0, 1, 0);
        Vec3 r = f.cross(u);
        return new Vec3(0, ORIGIN_Y, 0).add(r.scale(c.x)).add(u.scale(c.y)).add(f.scale(c.z));
    }

    /** Смещения 21 сустава от позиции сущности (в мировых осях). */
    public Vec3[] offsets(HandTracker.HandPose pose) {
        Vec3 f = Vec3.directionFromRotation(0f, getYRot());
        Vec3 u = new Vec3(0, 1, 0);
        Vec3 r = f.cross(u);
        Vec3 o = new Vec3(0, ORIGIN_Y, 0);
        Vec3[] out = new Vec3[21];
        for (int i = 0; i < 21; i++) {
            Vec3 c = pose.cs()[i];
            out[i] = o.add(r.scale(c.x)).add(u.scale(c.y)).add(f.scale(c.z));
        }
        return out;
    }

    /** Смещение (оценённого) локтя от позиции сущности в мировых осях. */
    public Vec3 elbowOffset(HandTracker.HandPose pose) {
        return toWorld(pose.elbow());
    }

    @Override
    public void tick() {
        super.tick();
        updateHitbox();
    }

    private void updateHitbox() {
        HandTracker.HandPose pose = HandTracker.pose(handIndex());
        Vec3 pos = position();
        if (pose == null) {
            Vec3 c = pos.add(0, ORIGIN_Y, 0);
            setBoundingBox(new AABB(c, c).inflate(0.1));
            return;
        }
        Vec3[] o = offsets(pose);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 v : o) {
            minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
        }
        setBoundingBox(new AABB(pos.x + minX, pos.y + minY, pos.z + minZ,
                pos.x + maxX, pos.y + maxY, pos.z + maxZ).inflate(BOX_PAD));
    }

    private boolean active() {
        return HandTracker.pose(handIndex()) != null;
    }

    /** Пока руку видно - в неё можно целиться/попадать, и она твёрдая. */
    @Override public boolean isPickable() { return active(); }
    @Override public boolean canBeCollidedWith() { return active(); }
    @Override public boolean isPushable() { return false; }
    @Override public PushReaction getPistonPushReaction() { return PushReaction.IGNORE; }
    /** Рука стоит на месте: поршни, взрывы и вода её не двигают. */
    @Override public void move(MoverType type, Vec3 movement) {}
    @Override public boolean shouldRenderAtSqrDistance(double distSqr) { return distSqr < 256.0 * 256.0; }
}
