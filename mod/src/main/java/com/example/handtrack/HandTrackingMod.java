package com.example.handtrack;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = HandTrackingMod.MOD_ID, dist = Dist.CLIENT)
public class HandTrackingMod {
    public static final String MOD_ID = "handtrack";

    public HandTrackingMod(IEventBus modBus) {
        HandTracker.start();
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HandTrackingMod::onClientTick);
        // логика физики идёт на потоке встроенного сервера
        NeoForge.EVENT_BUS.addListener(GrabController::onServerTick);
    }

    private static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        HandTracker.update(e.getCamera());
        HandRenderer.render(e);
    }

    private static void onClientTick(ClientTickEvent.Post e) {
        GrabController.clientTick(Minecraft.getInstance());
    }
}
