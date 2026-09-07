package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

/** Clears each trash page using the configured discard strategy. */
public final class TrashCleaner {

    private static final int TOTAL_SLOTS = TrashPageInfo.TOTAL_SLOTS;
    private static final int CONTENT_SLOTS = TrashPageInfo.CONTENT_SLOTS;
    private static final int INVENTORY_START = TrashPageInfo.INVENTORY_START;
    private static final int INVENTORY_END = TrashPageInfo.INVENTORY_END;
    private static final int VERIFY_MIN_TICKS = 2;
    private static final int VERIFY_MAX_TICKS = 15;
    private static final int RETRY_INTERVAL_TICKS = 4;
    private static final int CURSOR_WAIT_MAX_TICKS = 60;
    private static final int FLIP_MIN_TICKS = 3;
    private static final int FLIP_MAX_TICKS = 30;
    private static final int MAX_PAGES = 200;
    private static final int PAGE_INFO_WAIT_MAX_TICKS = 20 * 5;
    private static final int EXTERNAL_LIST_REFRESH_TICKS = 40;

    private enum Phase {
        SCAN,
        WAIT_TRANSFER,
        WAIT_DISCARD,
        WAIT_FLIP,
        WAIT_FLIP_CURSOR
    }

    private static final class BatchEntry {
        final int sourceSlot;
        final int inventorySlot;
        final String expectedSignature;

        BatchEntry(int sourceSlot, int inventorySlot, String expectedSignature) {
            this.sourceSlot = sourceSlot;
            this.inventorySlot = inventorySlot;
            this.expectedSignature = expectedSignature;
        }
    }

    private static boolean active;
    private static boolean directDiscard;
    private static Phase phase = Phase.SCAN;
    private static int waitTicks;
    private static int currentPage;
    private static int totalPages;
    private static String pageBeforeFlipSignature;
    private static String flipLastSignature;
    private static boolean flipSawChangedSignature;
    private static int cursorWaitTicks;
    private static int pageInfoWaitTicks;
    private static int flipButtonSlot = -1;
    private static String expectedFlipCursorSignature;
    private static final List<BatchEntry> currentBatch = new ArrayList<>();
    private static int pagesFlipped;
    private static int externalListRefreshTicks;

    private TrashCleaner() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void begin() {
        active = true;
        directDiscard = TrashCanDetectorConfigs.DIRECT_TRASH_DISCARD.getBooleanValue();
        phase = Phase.SCAN;
        waitTicks = 0;
        currentPage = -1;
        totalPages = -1;
        pageBeforeFlipSignature = null;
        flipLastSignature = null;
        flipSawChangedSignature = false;
        cursorWaitTicks = 0;
        pageInfoWaitTicks = 0;
        flipButtonSlot = -1;
        expectedFlipCursorSignature = null;
        currentBatch.clear();
        pagesFlipped = 0;
        externalListRefreshTicks = 0;
        TrashClearFilter.reload();
        TrashCanDetectorClient.feedback("开始自动清空垃圾桶...");
    }

    public static void tick(MinecraftClient client) {
        if (!active) return;
        if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null) {
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
        if (!handler.getCursorStack().isEmpty()
            && phase != Phase.WAIT_TRANSFER
            && phase != Phase.WAIT_FLIP
            && phase != Phase.WAIT_FLIP_CURSOR) {
            if (++cursorWaitTicks >= CURSOR_WAIT_MAX_TICKS) {
                abort(client, "鼠标物品同步超时，已停止以避免误操作");
            }
            return;
        }
        if (!handler.getCursorStack().isEmpty() && phase == Phase.WAIT_FLIP
            && expectedFlipCursorSignature != null
            && !expectedFlipCursorSignature.equals(
                TrashPageInfo.stackSignature(handler.getCursorStack()))) {
            abort(client, "检测到非翻页操作产生的光标物品，为避免误操作已停止");
            return;
        }
        if (handler.getCursorStack().isEmpty()) {
            cursorWaitTicks = 0;
        }

        if (++externalListRefreshTicks >= EXTERNAL_LIST_REFRESH_TICKS) {
            externalListRefreshTicks = 0;
            TrashClearFilter.reload();
        }

        switch (phase) {
            case SCAN -> tickScan(client, handler);
            case WAIT_TRANSFER -> tickTransfer(client, handler);
            case WAIT_DISCARD -> tickDiscard(client, handler);
            case WAIT_FLIP -> tickFlip(client, handler);
            case WAIT_FLIP_CURSOR -> tickFlipCursor(client, handler);
        }
    }

    private static void tickScan(MinecraftClient client, ScreenHandler handler) {
        TrashPageInfo.PageInfo pageInfo = TrashPageInfo.findPageInfo(
            handler, currentPage > 0 ? currentPage : 1
        );
        if (pageInfo == null) {
            if (++pageInfoWaitTicks >= PAGE_INFO_WAIT_MAX_TICKS) {
                abort(client, "未能从查看数据中读取垃圾桶页数");
            }
            return;
        }
        pageInfoWaitTicks = 0;
        if (totalPages < 0) {
            if (pageInfo.total > MAX_PAGES) {
                abort(client, "垃圾桶页数超过安全上限 " + MAX_PAGES);
                return;
            }
            totalPages = pageInfo.total;
        } else if (pageInfo.total != totalPages) {
            abort(client, "垃圾桶总页数发生变化");
            return;
        }
        if (currentPage < 1) {
            currentPage = 1;
        }

        currentBatch.clear();
        if (directDiscard) {
            scanDirectBatch(handler);
        } else {
            scanTransferBatch(handler);
        }

        if (!currentBatch.isEmpty()) {
            if (directDiscard) {
                throwBatch(client, handler);
            } else {
                transferBatch(client, handler);
            }
            return;
        }

        if (!directDiscard && hasDiscardableContent(handler)
            && findEmptyInventorySlots(handler).isEmpty()) {
            abort(client, "玩家背包没有空格，无法先取出垃圾桶物品");
            return;
        }

        if (currentPage >= totalPages) {
            finish(client, "垃圾桶已清空，共处理 " + totalPages + " 页");
            return;
        }
        if (pagesFlipped >= MAX_PAGES) {
            finish(client, "达到最大翻页数 " + MAX_PAGES + "，已停止");
            return;
        }

        int button = TrashPageInfo.findNextPageButton(handler);
        if (button < 0) {
            finish(client, pagesFlipped == 0
                ? "垃圾桶已清空"
                : "垃圾桶已清空，共处理 " + (pagesFlipped + 1) + " 页");
            return;
        }

        pageBeforeFlipSignature = TrashPageInfo.signature(handler);
        flipLastSignature = null;
        flipSawChangedSignature = false;
        flipButtonSlot = button;
        expectedFlipCursorSignature = TrashPageInfo.stackSignature(handler.getSlot(button).getStack());
        click(client, handler, button, 0, SlotActionType.PICKUP);
        pagesFlipped++;
        phase = Phase.WAIT_FLIP;
        waitTicks = 0;
    }

    private static void scanDirectBatch(ScreenHandler handler) {
        for (int sourceSlot = 0; sourceSlot < CONTENT_SLOTS; sourceSlot++) {
            ItemStack stack = handler.getSlot(sourceSlot).getStack();
            if (!stack.isEmpty() && TrashClearFilter.shouldDiscard(stack)) {
                currentBatch.add(new BatchEntry(
                    sourceSlot, -1, TrashPageInfo.stackSignature(stack)
                ));
            }
        }
    }

    private static void scanTransferBatch(ScreenHandler handler) {
        List<Integer> emptyInventorySlots = findEmptyInventorySlots(handler);
        int inventoryIndex = 0;
        for (int sourceSlot = 0; sourceSlot < CONTENT_SLOTS
            && inventoryIndex < emptyInventorySlots.size(); sourceSlot++) {
            ItemStack stack = handler.getSlot(sourceSlot).getStack();
            if (!stack.isEmpty() && TrashClearFilter.shouldDiscard(stack)) {
                currentBatch.add(new BatchEntry(
                    sourceSlot, emptyInventorySlots.get(inventoryIndex++),
                    TrashPageInfo.stackSignature(stack)
                ));
            }
        }
    }

    private static void throwBatch(MinecraftClient client, ScreenHandler handler) {
        for (BatchEntry entry : currentBatch) {
            if (!entry.expectedSignature.equals(
                TrashPageInfo.stackSignature(handler.getSlot(entry.sourceSlot).getStack()))) {
                continue;
            }
            click(client, handler, entry.sourceSlot, 1, SlotActionType.THROW);
        }
        phase = Phase.WAIT_DISCARD;
        waitTicks = 0;
    }

    private static void transferBatch(MinecraftClient client, ScreenHandler handler) {
        for (BatchEntry entry : currentBatch) {
            click(client, handler, entry.sourceSlot, 0, SlotActionType.PICKUP);
            click(client, handler, entry.inventorySlot, 0, SlotActionType.PICKUP);
        }
        phase = Phase.WAIT_TRANSFER;
        waitTicks = 0;
    }

    private static void tickTransfer(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) return;

        if (!handler.getCursorStack().isEmpty()) {
            retryTransferCursor(client, handler);
            if (waitTicks >= CURSOR_WAIT_MAX_TICKS) {
                abort(client, "垃圾桶物品转移超时，光标上的物品未能归还到背包");
            }
            return;
        }

        for (BatchEntry entry : currentBatch) {
            ItemStack source = handler.getSlot(entry.sourceSlot).getStack();
            ItemStack destination = handler.getSlot(entry.inventorySlot).getStack();
            if (!source.isEmpty()
                || !entry.expectedSignature.equals(TrashPageInfo.stackSignature(destination))) {
                if (waitTicks >= VERIFY_MAX_TICKS) {
                    abort(client, "垃圾桶物品未能完整转入背包，已停止");
                }
                return;
            }
        }

        for (BatchEntry entry : currentBatch) {
            click(client, handler, entry.inventorySlot, 1, SlotActionType.THROW);
        }
        phase = Phase.WAIT_DISCARD;
        waitTicks = 0;
    }

    private static void tickDiscard(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) return;

        boolean pending = false;
        for (BatchEntry entry : currentBatch) {
            int slot = directDiscard ? entry.sourceSlot : entry.inventorySlot;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()
                || !entry.expectedSignature.equals(TrashPageInfo.stackSignature(stack))) {
                continue;
            }
            if (waitTicks % RETRY_INTERVAL_TICKS == 0) {
                click(client, handler, slot, 1, SlotActionType.THROW);
            }
            pending = true;
        }

        if (!pending) {
            currentBatch.clear();
            phase = Phase.SCAN;
            return;
        }
        if (waitTicks >= VERIFY_MAX_TICKS) {
            abort(client, directDiscard
                ? "垃圾桶物品未能直接丢弃，已停止"
                : "背包中的垃圾桶物品未能丢弃，已停止");
        }
    }

    private static void retryTransferCursor(MinecraftClient client, ScreenHandler handler) {
        if ((waitTicks & 3) != 0) return;
        String cursorSignature = TrashPageInfo.stackSignature(handler.getCursorStack());
        for (BatchEntry entry : currentBatch) {
            if (!entry.expectedSignature.equals(cursorSignature)) continue;
            if (handler.getSlot(entry.inventorySlot).getStack().isEmpty()) {
                click(client, handler, entry.inventorySlot, 0, SlotActionType.PICKUP);
            }
            return;
        }
    }

    private static void tickFlip(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < FLIP_MIN_TICKS) return;

        if (!handler.getCursorStack().isEmpty()) {
            if (flipButtonSlot >= 0 && (waitTicks & 2) == 0) {
                ItemStack buttonStack = handler.getSlot(flipButtonSlot).getStack();
                String cursorSignature = TrashPageInfo.stackSignature(handler.getCursorStack());
                if (cursorSignature.equals(expectedFlipCursorSignature) && buttonStack.isEmpty()) {
                    click(client, handler, flipButtonSlot, 0, SlotActionType.PICKUP);
                }
            }
            if (waitTicks >= CURSOR_WAIT_MAX_TICKS) {
                abort(client, "翻页同步超时，下一页按钮物品未能归还");
            }
            return;
        }

        int expectedPage = currentPage + 1;
        TrashPageInfo.PageInfo pageInfo = TrashPageInfo.findPageInfo(handler, expectedPage);
        if (pageInfo == null || pageInfo.total != totalPages) {
            if (waitTicks >= FLIP_MAX_TICKS) {
                abort(client, "翻页后未能读取垃圾桶页数");
            }
            return;
        }
        String currentSignature = TrashPageInfo.signature(handler);
        if (expectedPage > totalPages) {
            abort(client, "翻页后的页码超过总页数");
            return;
        }

        if (!currentSignature.equals(pageBeforeFlipSignature)) {
            flipSawChangedSignature = true;
        }
        if (flipSawChangedSignature && currentSignature.equals(flipLastSignature)) {
            currentPage = expectedPage;
            phase = Phase.WAIT_FLIP_CURSOR;
            waitTicks = 0;
            return;
        }
        if (flipSawChangedSignature) {
            flipLastSignature = currentSignature;
        } else if (waitTicks >= FLIP_MAX_TICKS) {
            currentPage = expectedPage;
            phase = Phase.WAIT_FLIP_CURSOR;
            waitTicks = 0;
        }
    }

    private static void tickFlipCursor(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (handler.getCursorStack().isEmpty()) {
            if (waitTicks >= 3) {
                phase = Phase.SCAN;
                flipButtonSlot = -1;
                expectedFlipCursorSignature = null;
                cursorWaitTicks = 0;
            }
            return;
        }

        String cursorSignature = TrashPageInfo.stackSignature(handler.getCursorStack());
        if (!cursorSignature.equals(expectedFlipCursorSignature)) {
            abort(client, "检测到非翻页操作产生的光标物品，为避免误操作已停止");
            return;
        }
        if (flipButtonSlot >= 0 && (waitTicks & 3) == 0
            && handler.getSlot(flipButtonSlot).getStack().isEmpty()) {
            click(client, handler, flipButtonSlot, 0, SlotActionType.PICKUP);
        }
        if (waitTicks >= CURSOR_WAIT_MAX_TICKS) {
            abort(client, "下一页按钮物品未能归还到操作栏");
        }
    }

    private static List<Integer> findEmptyInventorySlots(ScreenHandler handler) {
        List<Integer> result = new ArrayList<>();
        for (int slot = INVENTORY_START; slot < INVENTORY_END; slot++) {
            if (handler.getSlot(slot).getStack().isEmpty()) {
                result.add(slot);
            }
        }
        return result;
    }

    private static boolean hasDiscardableContent(ScreenHandler handler) {
        for (int slot = 0; slot < CONTENT_SLOTS; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && TrashClearFilter.shouldDiscard(stack)) return true;
        }
        return false;
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slot, int button,
                              SlotActionType type) {
        client.interactionManager.clickSlot(handler.syncId, slot, button, type, client.player);
    }

    private static void abort(MinecraftClient client, String reason) {
        active = false;
        currentBatch.clear();
        closeGui(client);
        TrashCanDetectorClient.feedback("自动清空已中止：" + reason);
    }

    private static void finish(MinecraftClient client, String reason) {
        active = false;
        currentBatch.clear();
        closeGui(client);
        TrashCanDetectorClient.feedback(reason);
    }

    private static void closeGui(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
    }
}
