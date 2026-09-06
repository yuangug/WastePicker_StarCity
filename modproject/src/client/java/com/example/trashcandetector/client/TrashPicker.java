package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 垃圾桶自动翻页拾取状态机（由 //pick start 触发，或 //pick auto 开启时由刷新检测自动接续）：
 * 1. 在 0-35 正文格中查找命中搜索列表的物品，shift 点击优先进背包
 * 2. 若物品仍在原格（背包已满/被拒），改用 Ctrl+Q 整组丢到身边
 * 3. 每页处理完后点击“下一页”按钮；翻页前记住本页内容，
 *    若翻页后内容与翻页前相同则说明已到最后一页，遍历结束
 * 4. 结束后关闭 GUI、汇报成果，并以玩家身份在服务器聊天栏播报
 */
public final class TrashPicker {

    /** 垃圾桶 GUI 总槽位（垃圾内容 36 + 操作区 18 + 玩家背包 36）。 */
    private static final int TOTAL_SLOTS = TrashPageInfo.TOTAL_SLOTS;
    /** 0-35 为垃圾桶正文内容。 */
    private static final int CONTENT_SLOTS = TrashPageInfo.CONTENT_SLOTS;

    /** 拾取/丢出点击后先等这几 tick 再轮询：原版点击会同步做客户端预测，下一 tick 即可读到点击结果 */
    private static final int VERIFY_MIN_TICKS = 1;
    /** 拾取确认上限（tick）：物品始终仍在原格才判定失败——背包满转丢出 / 丢出被拒跳过 */
    private static final int VERIFY_MAX_TICKS = 10;
    /** 点击“下一页”后先等这几 tick 再轮询：新页内容由服务器下发，只留 1 tick 防止读到旧页 */
    private static final int FLIP_MIN_TICKS = 2;
    /** 翻页等待上限（tick）：内容始终未变才判定已到最后一页（保留 1 秒兜底，防慢服误判翻到底） */
    private static final int FLIP_MAX_TICKS = 20;
    /** 安全上限：最多翻多少页，防止极端情况死循环 */
    private static final int MAX_PAGES = 200;
    /** 连续拾取失败多少次后放弃 */
    private static final int MAX_CONSECUTIVE_FAILURES = 10;

    private enum Phase {
        SCAN, VERIFY_MOVE, VERIFY_THROW, WAIT_FLIP
    }

    private static boolean active;
    private static Phase phase = Phase.SCAN;
    private static int waitTicks;
    /** 当前扫描到的正文格（0-35） */
    private static int scanSlot;
    /** 正在等待确认的点击目标 */
    private static int actionSlot;
    private static String actionItemId;
    private static String actionName;
    private static int actionCount;
    /** 翻页前的整页内容签名，用于翻到底检测 */
    private static String pageSignature;
    /** 翻页等待期间最近一次读到的内容签名，要求连续两 tick 一致才视为新页同步完成 */
    private static String flipLastSig;
    private static int pagesFlipped;
    private static int consecutiveFailures;

    /** 拾取成果，key = 物品ID|显示名 */
    private static final Map<String, Tallied> RESULTS = new LinkedHashMap<>();

    private static final class Tallied {
        final String id;
        final String name;
        int invCount;
        int droppedCount;

        Tallied(String id, String name) {
            this.id = id;
            this.name = name;
        }

        int totalCount() {
            return invCount + droppedCount;
        }
    }

    private TrashPicker() {
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * 垃圾桶 GUI 已打开且首页数据同步完成后调用，开始拾取
     */
    public static void begin() {
        active = true;
        phase = Phase.SCAN;
        waitTicks = 0;
        scanSlot = 0;
        pagesFlipped = 0;
        consecutiveFailures = 0;
        pageSignature = null;
        RESULTS.clear();
        TrashCanDetectorClient.feedback("开始搜索垃圾桶，待搜索 " + PickList.size() + " 项物品...");
    }

    /**
     * 每帧调用（客户端主线程）
     */
    public static void tick(MinecraftClient client) {
        if (!active) {
            return;
        }
        if (client.player == null) {
            abort(client, "玩家已离线");
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            abort(client, "垃圾桶界面被关闭");
            return;
        }
        ScreenHandler handler = handled.getScreenHandler();
        if (handler.slots.size() != TOTAL_SLOTS) {
            abort(client, "当前界面不是垃圾桶（6 行容器）");
            return;
        }

        switch (phase) {
            case SCAN -> tickScan(client, handler);
            case VERIFY_MOVE, VERIFY_THROW -> tickVerify(client, handler);
            case WAIT_FLIP -> tickFlip(client, handler);
        }
    }

    /**
     * 扫描当前页：先取正文格中命中列表的物品，取完后翻页
     */
    private static void tickScan(MinecraftClient client, ScreenHandler handler) {
        // 1) 在 0-35 正文格中查找下一个命中物品
        while (scanSlot < CONTENT_SLOTS) {
            ItemStack stack = handler.getSlot(scanSlot).getStack();
            if (!stack.isEmpty() && PickList.matches(stack)) {
                actionSlot = scanSlot;
                actionItemId = Registries.ITEM.getId(stack.getItem()).toString();
                actionName = stack.getName().getString();
                actionCount = stack.getCount();
                // shift 点击：优先进玩家背包
                click(client, handler, actionSlot, 0, SlotActionType.QUICK_MOVE);
                phase = Phase.VERIFY_MOVE;
                waitTicks = 0;
                return;
            }
            scanSlot++;
        }

        // 2) 本页处理完，准备翻页
        if (pagesFlipped >= MAX_PAGES) {
            finish(client, "已达到最大翻页数 " + MAX_PAGES + "，为安全起见提前结束");
            return;
        }
        int button = TrashPageInfo.findNextPageButton(handler);
        if (button < 0) {
            finish(client, pagesFlipped == 0
                ? "本页未找到[下一页]按钮，单页遍历完成"
                : "未找到[下一页]按钮，遍历完成");
            return;
        }

        // 翻页前记住本页内容（用于翻到底检测）
        pageSignature = TrashPageInfo.signature(handler);
        flipLastSig = null;
        click(client, handler, button, 0, SlotActionType.PICKUP);
        pagesFlipped++;
        if (pagesFlipped % 10 == 0) {
            TrashCanDetectorClient.feedback("已翻 " + pagesFlipped + " 页...");
        }
        phase = Phase.WAIT_FLIP;
        waitTicks = 0;
    }

    /**
     * 确认拾取结果：最小等待后逐 tick 轮询，物品一从原格消失立即确认成功（无需等满上限）；
     * 物品始终仍在才在等满 VERIFY_MAX_TICKS 后判定失败（背包满转丢出 / 丢出被拒跳过），
     * 保留兜底上限防止把“回包慢”误判成“背包满”而错误丢出。
     */
    private static void tickVerify(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) {
            return;
        }

        boolean stillPresent = isSameStackAt(handler, actionSlot, actionItemId);

        if (phase == Phase.VERIFY_MOVE) {
            if (!stillPresent) {
                // 已进背包
                tally(true);
                consecutiveFailures = 0;
                scanSlot++;
                phase = Phase.SCAN;
            } else if (waitTicks >= VERIFY_MAX_TICKS) {
                // 等满上限物品仍在 → 背包已满或被拒绝 → 整组丢到身边（等同 Ctrl+Q）
                click(client, handler, actionSlot, 1, SlotActionType.THROW);
                phase = Phase.VERIFY_THROW;
                waitTicks = 0;
            }
        } else {
            if (!stillPresent) {
                // 已丢出到身边
                tally(false);
                consecutiveFailures = 0;
                scanSlot++;
                phase = Phase.SCAN;
            } else if (waitTicks >= VERIFY_MAX_TICKS) {
                // 等满上限仍在 → 丢出被拒，跳过该格
                consecutiveFailures++;
                TrashCanDetectorClient.feedback("拾取失败：" + actionName + " 仍在垃圾桶中，跳过");
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    abort(client, "连续 " + MAX_CONSECUTIVE_FAILURES + " 次拾取失败");
                    return;
                }
                scanSlot++;
                phase = Phase.SCAN;
            }
        }
    }

    /**
     * 等待翻页内容同步：与翻页前内容相同 → 已翻到底，遍历结束。
     * 最小等待后逐 tick 轮询，内容一旦变化且连续两 tick 稳定即提前进入新页，无需等满上限；
     * 只有内容一直不变时才等到 FLIP_MAX_TICKS 判定翻到底（防止慢服同步慢导致误判）。
     */
    private static void tickFlip(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < FLIP_MIN_TICKS) {
            return;
        }

        String sig = TrashPageInfo.signature(handler);

        if (sig.equals(pageSignature)) {
            // 内容仍是旧页：等满上限仍未变化 → 已翻到最后一页
            if (waitTicks >= FLIP_MAX_TICKS) {
                finish(client, "已翻到最后一页，共遍历 " + pagesFlipped + " 页");
            }
            return;
        }

        // 内容已不同于旧页：再确认一个 tick 保持稳定，避免读到“半同步”的页面
        if (sig.equals(flipLastSig)) {
            // 新的一页，从第 0 格重新扫描
            scanSlot = 0;
            phase = Phase.SCAN;
        } else {
            flipLastSig = sig;
        }
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slotId, int button, SlotActionType type) {
        client.interactionManager.clickSlot(handler.syncId, slotId, button, type, client.player);
    }

    private static boolean isSameStackAt(ScreenHandler handler, int slotId, String itemId) {
        if (slotId >= handler.slots.size()) {
            return false;
        }
        ItemStack stack = handler.getSlot(slotId).getStack();
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId);
    }

    private static void tally(boolean toInventory) {
        String key = actionItemId + "|" + actionName;
        Tallied tallied = RESULTS.computeIfAbsent(key, k -> new Tallied(actionItemId, actionName));
        if (toInventory) {
            tallied.invCount += actionCount;
        } else {
            tallied.droppedCount += actionCount;
        }
    }

    private static void abort(MinecraftClient client, String reason) {
        active = false;
        closeGui(client);
        report(client, "已中止：" + reason);
    }

    private static void finish(MinecraftClient client, String reason) {
        active = false;
        closeGui(client);
        report(client, reason);
    }

    private static void closeGui(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen) {
            // closeHandledScreen 会发送关闭容器的封包并关闭界面
            client.player.closeHandledScreen();
        } else if (client.currentScreen != null) {
            client.setScreen(null);
        }
    }

    /**
     * 汇报搜垃圾成果，并以玩家身份在服务器聊天栏播报
     */
    private static void report(MinecraftClient client, String summary) {
        TrashCanDetectorClient.feedback("—— 搜垃圾成果 ——");
        TrashCanDetectorClient.feedback(summary);

        int kinds = RESULTS.size();
        if (kinds == 0) {
            TrashCanDetectorClient.feedback("本次没有找到搜索列表中的物品");
            return;
        }

        int totalItems = 0;
        for (Tallied tallied : RESULTS.values()) {
            totalItems += tallied.totalCount();
            TrashCanDetectorClient.feedback(tallied.name + " x" + tallied.totalCount()
                + "（背包 " + tallied.invCount + " / 丢出 " + tallied.droppedCount + "）");
        }
        TrashCanDetectorClient.feedback("共捡到 " + kinds + " 种 / " + totalItems + " 件物品");

        // 以玩家身份在服务器聊天栏播报（超出 256 字符时截断物品列表）
        String playerName = client.player != null ? client.player.getName().getString() : "";
        String tail = "，你也来试试吧！";
        StringBuilder sb = new StringBuilder("我").append(playerName)
            .append("在服务器垃圾桶百亿补贴活动中赢得了");
        int listed = 0;
        boolean truncated = false;
        for (Tallied tallied : RESULTS.values()) {
            String piece = tallied.name + "x" + tallied.totalCount();
            if (sb.length() + piece.length() + tail.length() + 8 > 256) {
                truncated = true;
                break;
            }
            if (listed > 0) {
                sb.append("、");
            }
            sb.append(piece);
            listed++;
        }
        if (truncated) {
            sb.append("等").append(kinds).append("种物品");
        }
        sb.append(tail);

        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(sb.toString());
        }
    }
}
