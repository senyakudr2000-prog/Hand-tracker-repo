package com.example.handtrack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.Camera;
import net.minecraft.world.InteractionHand;
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
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
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
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onRenderHand);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onPlayerPre);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onPlayerPost);
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
     * Первое лицо: ванильные руки и предметы в руках не рисуем, вместо них - две руки целиком.
     * Рисуем один раз за кадр (в событии основной руки), отменяем оба события.
     */
    private static void onRenderHand(RenderHandEvent e) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) return;
        if (HandTracker.pose(0) == null && HandTracker.pose(1) == null) return;
        e.setCanceled(true);
        if (e.getHand() != InteractionHand.MAIN_HAND) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        PlayerSkin skin = player.getSkin();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        PoseStack ps = e.getPoseStack();
        VertexConsumer vc = e.getMultiBufferSource().getBuffer(RenderType.entityCutoutNoCull(skin.texture()));
        float pt = cam.getPartialTickTime();
        for (int h = 0; h < 2; h++) {
            HandTracker.HandPose pose = HandTracker.pose(h);
            if (pose == null) continue;
            boolean left = h == 0;
            Vec3[] world = HandAttach.solve(pose, left, slim, player, pt);
            HandAttach.publish(h, world);
            Vec3[] j = HandAttach.toCameraSpace(world, cam);
            HandModel.drawArm(ps, vc, e.getPackedLight(), j, left, slim);
            HandModel.draw(ps, vc, e.getPackedLight(), j, left, pose.pinch());
        }
    }

    /** Третье лицо: прячем ванильные руки (и рукава) модели игрока, пока рука отслеживается. */
    private static void onPlayerPre(RenderPlayerEvent.Pre e) { hideArms(e, true); }

    private static void onPlayerPost(RenderPlayerEvent.Post e) { hideArms(e, false); }

    private static void hideArms(RenderPlayerEvent e, boolean hide) {
        if (e.getEntity() != Minecraft.getInstance().player) return;
        PlayerModel<AbstractClientPlayer> m = e.getRenderer().getModel();
        boolean l = hide && HandTracker.pose(0) != null;
        boolean r = hide && HandTracker.pose(1) != null;
        m.leftArm.skipDraw = l;
        m.leftSleeve.skipDraw = l;
        m.rightArm.skipDraw = r;
        m.rightSleeve.skipDraw = r;
    }
}
