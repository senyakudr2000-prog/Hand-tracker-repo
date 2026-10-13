package com.example.handtrack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
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
        HandTracker.start();
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onClientTick);
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
}
