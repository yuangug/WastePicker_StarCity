package com.example.trashcandetector.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.util.Optional;

/** Adapts both the QuickShulker client mod and its Paper/Leaves server protocol port. */
final class QuickShulkerAdapter {
    private static final String MOD_ID = "quickshulker";
    private static final String CLIENT_UTIL = "net.kyrptonaught.quickshulker.client.ClientUtil";
    private static final String OPEN_PACKET = "net.kyrptonaught.quickshulker.network.OpenShulkerPacket";

    private static Method checkAndSendMethod;
    private static Method sendPacketMethod;
    private static boolean initialized;
    private static boolean directProtocolRegistered;
    private static String detectedVersion;
    private static String unavailableReason;

    private QuickShulkerAdapter() {
    }

    static void initialize() {
        if (initialized) return;
        initialized = true;

        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (container.isPresent()) {
            detectedVersion = container.get().getMetadata().getVersion().getFriendlyString();
            resolveClientModApi();
        } else {
            registerDirectProtocol();
        }
    }

    static boolean isAvailable() {
        initialize();
        return checkAndSendMethod != null || sendPacketMethod != null || directProtocolRegistered;
    }

    static String unavailableReason() {
        initialize();
        if (unavailableReason != null) return unavailableReason;
        return "QuickShulker 客户端 API 与服务端协议均不可用";
    }

    static boolean open(ItemStack stack, int windowSlot) {
        initialize();
        if (!isOpenable(stack)) return false;

        if (checkAndSendMethod != null) {
            try {
                if (Boolean.TRUE.equals(checkAndSendMethod.invoke(null, stack, windowSlot))) {
                    return true;
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                TrashCanDetectorClient.LOGGER.warn(
                    "QuickShulker {} CheckAndSend failed, trying packet API", detectedVersion, e);
            }
        }

        if (sendPacketMethod != null) {
            try {
                sendPacketMethod.invoke(null, windowSlot);
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                unavailableReason = "QuickShulker " + detectedVersion + " 原生发包 API 调用失败";
                TrashCanDetectorClient.LOGGER.error("QuickShulker packet API invocation failed", e);
                return false;
            }
        }

        if (!directProtocolRegistered || !ClientPlayNetworking.canSend(OpenShulkerPayload.ID)) {
            unavailableReason = "服务器未提供 quickshulker:open_shulker_packet 协议";
            return false;
        }

        ClientPlayNetworking.send(new OpenShulkerPayload(windowSlot));
        return true;
    }

    static boolean isOpenable(ItemStack stack) {
        return !stack.isEmpty() && stack.isIn(ItemTags.SHULKER_BOXES) && stack.getCount() == 1;
    }

    private static void resolveClientModApi() {
        try {
            Class<?> clientUtil = Class.forName(CLIENT_UTIL);
            checkAndSendMethod = clientUtil.getMethod("CheckAndSend", ItemStack.class, int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            TrashCanDetectorClient.LOGGER.warn(
                "QuickShulker {} does not expose ClientUtil.CheckAndSend", detectedVersion);
        }

        try {
            Class<?> packet = Class.forName(OPEN_PACKET);
            sendPacketMethod = packet.getMethod("sendOpenPacket", int.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            TrashCanDetectorClient.LOGGER.warn(
                "QuickShulker {} does not expose OpenShulkerPacket.sendOpenPacket", detectedVersion);
        }

        if (checkAndSendMethod == null && sendPacketMethod == null) {
            unavailableReason = "QuickShulker " + detectedVersion + " 缺少兼容的打开 API";
        } else {
            TrashCanDetectorClient.LOGGER.info("已适配 QuickShulker {}", detectedVersion);
        }
    }

    private static void registerDirectProtocol() {
        try {
            PayloadTypeRegistry.playC2S().register(OpenShulkerPayload.ID, OpenShulkerPayload.CODEC);
            directProtocolRegistered = true;
            TrashCanDetectorClient.LOGGER.info(
                "未安装 QuickShulker 客户端，启用服务端 open_shulker_packet 直连适配");
        } catch (IllegalArgumentException | IllegalStateException e) {
            unavailableReason = "无法注册 QuickShulker 服务端协议适配";
            TrashCanDetectorClient.LOGGER.error("Unable to register QuickShulker protocol adapter", e);
        }
    }

    private record OpenShulkerPayload(int windowSlot) implements CustomPayload {
        private static final Id<OpenShulkerPayload> ID = new Id<>(
            Identifier.of(MOD_ID, "open_shulker_packet")
        );
        private static final PacketCodec<RegistryByteBuf, OpenShulkerPayload> CODEC =
            CustomPayload.codecOf(
                (payload, buffer) -> buffer.writeInt(payload.windowSlot),
                buffer -> new OpenShulkerPayload(buffer.readInt())
            );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
