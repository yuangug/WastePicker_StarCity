package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;

/** Sends one chat message whenever the local player enters a sleeping state. */
public final class SleepChatSender {

    private static boolean sentForCurrentSleep;

    private SleepChatSender() {
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            sentForCurrentSleep = false;
            return;
        }

        if (!client.player.isSleeping()) {
            sentForCurrentSleep = false;
            return;
        }

        if (!TrashCanDetectorConfigs.AUTO_SEND_ZZZ_WHILE_SLEEPING.getBooleanValue()
            || sentForCurrentSleep) {
            return;
        }

        client.getNetworkHandler().sendChatMessage("zzz");
        sentForCurrentSleep = true;
    }
}
