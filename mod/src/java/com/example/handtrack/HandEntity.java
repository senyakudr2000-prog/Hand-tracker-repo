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
 * Кисть как сущность: ходит за игроком вместе с его рукой (позицию ставит GrabController).
 * Хитбокс = коробка вокруг всех 21 сустава, обновляется каждый тик (виден по F3+B).
 * Не сохраняется в мир - создаётся заново при входе.
 */
public class HandEntity extends Entity {
    /**
     * true - хитбокс твёрдый: в него можно целиться и он мешает ходить. Для своей же руки это неудобно
     * (прицел упирается в собственную кисть), поэтому по умолчанию выключено.
     */
    public static final boolean SOLID_HITBOX = false;
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

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        updateHitbox();
    }

    private void updateHitbox() {
        HandAttach.WorldHand wh = HandAttach.get(handIndex());
        if (wh == null) {
            setBoundingBox(new AABB(position(), position()).inflate(0.1));
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        Vec3[] js = wh.joints();
        for (int i = 0; i < 21 && i < js.length; i++) {
            Vec3 v = js[i];
            minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
        }
        setBoundingBox(new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(BOX_PAD));
    }

    private boolean solid() {
        return SOLID_HITBOX && HandAttach.get(handIndex()) != null;
    }

    @Override public boolean isPickable() { return solid(); }
    @Override public boolean canBeCollidedWith() { return solid(); }
    @Override public boolean isPushable() { return false; }
    @Override public PushReaction getPistonPushReaction() { return PushReaction.IGNORE; }
    /** Сущность двигает только мод (setPos); поршни, взрывы и вода её не трогают. */
    @Override public void move(MoverType type, Vec3 movement) {}
}
