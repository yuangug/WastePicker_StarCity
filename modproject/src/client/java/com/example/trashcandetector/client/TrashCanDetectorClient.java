package com.example.trashcandetector.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import fi.dy.masa.malilib.event.InitializationHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端入口点：
 * 1. 监听垃圾桶刷新消息，自动打开垃圾桶 GUI 并导出内容（//pick start 也会走到这里）
 * 2. 通过 //pick 指令驱动 TrashPicker 自动翻页拾取（指令解析见 PickCommandHandler）；
 *    //pick auto 开启后，检测到刷新时导出完成也会自动接续翻页拾取
 */
public class TrashCanDetectorClient implements ClientModInitializer {

    public static final String MOD_ID = "trashcandetector";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final String PREFIX = "[垃圾桶探测器] ";
    private static final int READ_DELAY_TICKS = 20;

    /** 已检测到垃圾桶消息或 //pick start，正在等待 /trash 打开容器 GUI */
    private static boolean waitingForTrashScreen;
    /** 容器 GUI 已打开，等待服务器同步槽位数据 */
    private static boolean pendingRead;
    private static HandledScreen<?> pendingScreen;
    private static int pendingTicks;
    /** 导出完成后自动开始拾取（//pick start，或 //pick auto 开启时由刷新消息触发） */
    private static boolean pickRequested;
    /** 导出完成后自动清空（刷新消息或 //trash clear 触发） */
    private static boolean clearRequested;
    @Override
    public void onInitializeClient() {
        LOGGER.info("TrashCan Detector 已加载，开始监听垃圾桶刷新消息");
        InitializationHandler.getInstance().registerInitializationHandler(new TrashCanDetectorMalilib());

        // 1) 聊天消息监听：检测到垃圾桶提示后自动发送 /trash（自动拾取任务运行中时忽略）
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;

            String text = message.getString();
            InfiniteFlightDeviceManager.handleChatMessage(text);
            String stripped = stripPunctuation(text);

            if (containsTrashKeywords(stripped)) {
                if (isBusy()) {
                    LOGGER.info("检测到垃圾桶刷新消息，但当前有任务进行中，忽略");
                    return true;
                }

                boolean autoClear = TrashCanDetectorConfigs.AUTO_CLEAR_TRASH.getBooleanValue();
                boolean autoPick = TrashCanDetectorConfigs.AUTO_PICK_ON_REFRESH.getBooleanValue();

                // 未开启自动模式时，不提醒、不打开垃圾桶页面
                if (!autoClear && !autoPick) {
                    LOGGER.info("检测到垃圾桶刷新消息，但自动模式未开启，忽略");
                    return true;
                }

                LOGGER.info("检测到垃圾桶刷新消息: {}", text);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null && client.getNetworkHandler() != null) {
                    client.player.sendMessage(
                        Text.literal(PREFIX + "检测到垃圾桶刷新，正在自动打开..."),
                        false
                    );
                    // 发送 /trash 指令打开垃圾桶插件 GUI
                    client.getNetworkHandler().sendChatCommand("trash");
                    waitingForTrashScreen = true;
                    clearRequested = autoClear;
                    // 自动清空开启时优先清空，避免与拾取状态机同时点击 GUI。
                    pickRequested = !clearRequested && autoPick;
                    if (pickRequested && PickList.isEmpty()) {
                        feedback("自动拾取已开启，但搜索列表为空（//pick add 添加物品），本次仅导出");
                        pickRequested = false;
                    }
                }
            }

            return true;
        });

        // 2) GUI 打开监听：检测容器屏幕出现，准备读取
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!waitingForTrashScreen) return;
            if (!(screen instanceof HandledScreen<?> handled)) return;

            waitingForTrashScreen = false;
            pendingScreen = handled;
            pendingTicks = 0;
            pendingRead = true;
            LOGGER.info("检测到容器 GUI 已打开，等待槽位数据同步...");
        });

        // 3) 每 tick 检查：延迟后读取容器内容并导出；随后驱动拾取状态机
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null || client.world == null) {
                cancelPendingTrashRequest();
            }

            if (pendingRead && pendingScreen != null) {
                if (client.player == null || client.getNetworkHandler() == null
                    || client.currentScreen != pendingScreen) {
                    pendingRead = false;
                    pendingScreen = null;
                    pickRequested = false;
                    clearRequested = false;
                    feedback("垃圾桶界面已关闭，待处理操作已取消");
                }
            }

            if (pendingRead && pendingScreen != null) {
                pendingTicks++;
                if (pendingTicks >= READ_DELAY_TICKS) {
                    // 延迟结束，读取并导出首页内容
                    pendingRead = false;
                    boolean doPick = pickRequested;
                    boolean doClear = clearRequested;
                    pickRequested = false;
                    clearRequested = false;
                    readAndExportContainer(client, pendingScreen);
                    pendingScreen = null;

                    if (doClear) {
                        TrashCleaner.begin();
                    } else if (doPick) {
                        TrashPicker.begin();
                    }
                }
            }

            if (TrashCleaner.isActive()) {
                TrashCleaner.tick(client);
            } else {
                TrashPicker.tick(client);
            }
            PointBuyer.tick(client);
            InfiniteFlightDeviceManager.tick(client);
        });
    }

    static boolean isBusy() {
        return waitingForTrashScreen || pendingRead || TrashPicker.isActive() || TrashCleaner.isActive();
    }

    private static void cancelPendingTrashRequest() {
        if (!waitingForTrashScreen && !pendingRead && pendingScreen == null
            && !pickRequested && !clearRequested) {
            return;
        }
        waitingForTrashScreen = false;
        pendingRead = false;
        pendingScreen = null;
        pendingTicks = 0;
        pickRequested = false;
        clearRequested = false;
    }

    /**
     * 由 PickCommandHandler 调用（//pick auto）：开关自动拾取模式，返回切换后的状态
     */
    static boolean toggleAutoPick() {
        boolean enabled = !TrashCanDetectorConfigs.AUTO_PICK_ON_REFRESH.getBooleanValue();
        TrashCanDetectorConfigs.AUTO_PICK_ON_REFRESH.setBooleanValue(enabled);
        TrashCanDetectorConfigs.saveNow();
        return enabled;
    }

    /**
     * 由 PickCommandHandler 调用（//pick start）：请求开始自动拾取
     */
    static void requestPickStart() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            feedback("请先进入游戏服务器后再使用 //pick start");
            return;
        }
        if (PickList.isEmpty()) {
            feedback("搜索列表为空，请先用 //pick add <物品ID> 添加物品（//pick list 查看）");
            return;
        }
        if (TrashPicker.isActive() || TrashCleaner.isActive()) {
            feedback("正在搜索中，请等待当前任务完成");
            return;
        }
        if (pendingRead || waitingForTrashScreen) {
            // 垃圾桶已经打开/正在打开，导出完成后直接开始搜索
            pickRequested = true;
            feedback("垃圾桶正在打开，导出后将自动开始搜索...");
            return;
        }

        pickRequested = true;
        clearRequested = false;
        // 关闭当前打开的界面，避免 /trash 打不开
        if (client.currentScreen != null) {
            if (client.currentScreen instanceof HandledScreen) {
                client.player.closeHandledScreen();
            } else {
                client.setScreen(null);
            }
        }
        feedback("正在自动打开垃圾桶（待搜索 " + PickList.size() + " 项物品）...");
        client.getNetworkHandler().sendChatCommand("trash");
        waitingForTrashScreen = true;
    }

    /** 由本地 //trash clear 命令或清空快捷键调用。 */
    static void requestTrashClear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            feedback("请先进入游戏服务器后再使用 //trash clear");
            return;
        }
        if (TrashPicker.isActive() || TrashCleaner.isActive()) {
            feedback("已有垃圾桶自动化任务正在运行");
            return;
        }
        if (pendingRead || waitingForTrashScreen) {
            clearRequested = true;
            pickRequested = false;
            feedback("垃圾桶正在打开，数据同步后将自动清空...");
            return;
        }

        clearRequested = true;
        pickRequested = false;
        if (client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        } else if (client.currentScreen != null) {
            client.setScreen(null);
        }
        feedback("正在打开垃圾桶并准备清空...");
        client.getNetworkHandler().sendChatCommand("trash");
        waitingForTrashScreen = true;
    }

    /**
     * 发送本地反馈消息到聊天栏（仅自己可见）
     */
    static void feedback(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(PREFIX + message), false);
        }
    }

    /** Shows a short, non-chat status tip for one-shot automation events. */
    static void tip(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null && message != null && !message.isBlank()) {
            client.inGameHud.setOverlayMessage(Text.literal(message), false);
        }
    }

    /**
     * 从容器 GUI 中读取所有物品槽位，导出为 JSON 和 Markdown 文件
     */
    private static void readAndExportContainer(MinecraftClient client, HandledScreen<?> handled) {
        ScreenHandler handler = handled.getScreenHandler();
        List<Map<String, Object>> items = new ArrayList<>();
        int totalCount = 0;

        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slot", slot.id);
                item.put("item", stack.getItem().toString());
                item.put("name", stack.getName().getString());
                item.put("count", stack.getCount());
                if (stack.contains(DataComponentTypes.CUSTOM_DATA)) {
                    item.put("custom_data", stack.get(DataComponentTypes.CUSTOM_DATA).toString());
                }
                items.add(item);
                totalCount += stack.getCount();
            }
        }

        if (items.isEmpty()) {
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal(PREFIX + "容器为空或数据未同步，无内容可导出"),
                    false
                );
            }
            return;
        }

        try {
            Path outDir = client.runDirectory.toPath().resolve("trashcan-detector");
            Files.createDirectories(outDir);

            // 两个文件共用同一时间戳，保证文件名一致
            String ts = timestamp();

            // 写入 JSON
            Path jsonPath = outDir.resolve("trashcan_" + ts + ".json");
            Files.writeString(jsonPath, toJson(items, totalCount), StandardCharsets.UTF_8);

            // 写入 Markdown 表格
            Path mdPath = outDir.resolve("trashcan_" + ts + ".md");
            Files.writeString(mdPath, toMarkdown(items, totalCount), StandardCharsets.UTF_8);

            LOGGER.info("垃圾桶内容已导出: {} / {}", jsonPath, mdPath);

        } catch (IOException e) {
            LOGGER.error("导出垃圾桶内容失败", e);
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal(PREFIX + "导出失败: " + e.getMessage()),
                    false
                );
            }
        }
    }

    /**
     * 生成 JSON 格式输出（供 AI 读取）
     */
    private static String toJson(List<Map<String, Object>> items, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"type\": \"trashcan_contents\",\n");
        sb.append("  \"total_items\": ").append(totalCount).append(",\n");
        sb.append("  \"unique_items\": ").append(items.size()).append(",\n");
        sb.append("  \"items\": [\n");
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            sb.append("    { ");
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    fields.add("\"" + entry.getKey() + "\": " + v);
                } else {
                    fields.add("\"" + entry.getKey() + "\": \"" + escapeJson(v.toString()) + "\"");
                }
            }
            sb.append(String.join(", ", fields));
            sb.append(" }");
            if (i < items.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成 Markdown 表格（供人类阅读）
     */
    private static String toMarkdown(List<Map<String, Object>> items, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 垃圾桶内容\n\n");
        sb.append("- **总物品数**: ").append(totalCount).append("\n");
        sb.append("- **物品种类**: ").append(items.size()).append("\n\n");
        sb.append("| 槽位 | 物品ID | 名称 | 数量 |\n");
        sb.append("|------|--------|------|------|\n");
        for (Map<String, Object> item : items) {
            sb.append("| ").append(item.get("slot"))
              .append(" | ").append(item.get("item"))
              .append(" | ").append(item.get("name"))
              .append(" | ").append(item.get("count"))
              .append(" |\n");
        }
        return sb.toString();
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String stripPunctuation(String text) {
        return text.replaceAll("[\\p{P}\\p{S}\\s]", "");
    }

    private static boolean containsTrashKeywords(String text) {
        return text.contains("物品被意外清理")
            && text.contains("公共垃圾桶")
            && text.contains("领回");
    }
}
