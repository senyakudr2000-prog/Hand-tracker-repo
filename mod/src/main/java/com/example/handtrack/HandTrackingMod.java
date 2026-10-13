package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(value = HandTrackingMod.MOD_ID, dist = Dist.CLIENT)
public class HandTrackingMod {
    public static final String MOD_ID = "handtrack";

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<HandEntity>> HAND =
            ENTITIES.register("hand", () -> EntityType.Builder.<HandEntity>of(HandEntity::new, MobCategory.MISC)
                    .sized(0.3f, 0.3f)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .build("hand"));

    public HandTrackingMod(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(HandTrackingMod::registerRenderers);
        modBus.addListener(HandTrackingMod::addLayers);
        HandTracker.start();
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onRenderArm);
        // логика физики и спавн рук идут на потоке встроенного сервера
        NeoForge.EVENT_BUS.addListener(GrabController::onServerTick);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(HAND.get(), HandEntityRenderer::new);
    }

    private static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        HandTracker.update();
    }

    private static void onClientTick(ClientTickEvent.Post e) {
        GrabController.clientTick(Minecraft.getInstance());
    }

    /** Третье лицо: кисти на концах рук модели игрока. */
    private static void addLayers(EntityRenderersEvent.AddLayers e) {
        for (PlayerSkin.Model m : e.getSkins()) {
            EntityRenderer<? extends Player> r = e.getSkin(m);
            if (r instanceof PlayerRenderer pr) pr.addLayer(new HandLayer(pr));
        }
    }

    /**
     * Первое лицо: вызывается прямо перед тем, как игра рисует свою руку (только рука-основная и пустая).
     * PoseStack уже стоит в точке руки; мы встаём в систему координат части модели руки так же, как это делает
     * сама игра, и дорисовываем кисть на конце. Руку игры не отменяем и не меняем.
     */
    private static void onRenderArm(RenderArmEvent e) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = e.getPlayer();
        if (player != mc.player) return;
        boolean left = e.getArm() == HumanoidArm.LEFT;
        int h = left ? 0 : 1;
        HandTracker.HandPose pose = HandTracker.pose(h);
        if (pose == null) return;

        PlayerRenderer pr = (PlayerRenderer) mc.getEntityRenderDispatcher().getRenderer(player);
        PlayerModel<AbstractClientPlayer> model = pr.getModel();
        model.attackTime = 0;
        model.crouching = false;
        model.swimAmount = 0;
        model.setupAnim(player, 0, 0, 0, 0, 0);
        ModelPart arm = left ? model.leftArm : model.rightArm;
        arm.xRot = 0;

        PlayerSkin skin = player.getSkin();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        Vec3[] j = HandAttach.armLocal(pose, left, slim);
        PoseStack ps = e.getPoseStack();
        VertexConsumer vc = e.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(skin.texture()));
        ps.pushPose();
        arm.translateAndRotate(ps);
        HandModel.draw(ps, vc, e.getPackedLight(), j, left, pose.pinch());
        HandAttach.publishFirstPerson(h, j, ps.last().pose(), mc.gameRenderer.getMainCamera());
        ps.popPose();
    }
}
