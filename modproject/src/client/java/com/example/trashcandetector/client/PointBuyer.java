package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/** Sends the configured server command at a fixed interval without opening a screen. */
public final class PointBuyer {

    private static final int PURCHASE_INTERVAL_TICKS = 20 * 60;

    private static int ticks;
    private static ClientPlayNetworkHandler lastNetworkHandler;

    private PointBuyer() {
    }

    public static void tick(MinecraftClient client) {
        if (!TrashCanDetectorConfigs.POINT_BUYER_ENABLED.getBooleanValue()
            || client.player == null
            || client.getNetworkHandler() == null
            || client.world == null) {
            reset();
            return;
        }

        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler != lastNetworkHandler) {
            lastNetworkHandler = networkHandler;
            ticks = 0;
        }

        if (++ticks >= PURCHASE_INTERVAL_TICKS) {
            networkHandler.sendChatCommand("botmanager buy " + TrashCanDetectorConfigs.pointsPerPurchase());
            ticks = 0;
        }
    }

    public static boolean isEnabled() {
        return TrashCanDetectorConfigs.POINT_BUYER_ENABLED.getBooleanValue();
    }

    public static void reset() {
        ticks = 0;
        lastNetworkHandler = null;
    }
}
