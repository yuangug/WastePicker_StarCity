package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state machine that clears every trash page.
 *
 * The server requires trash items to enter the player's inventory before they
 * can be discarded. Each batch therefore uses at most one empty inventory slot
 * per trash stack: all transfers are sent as one batch, then all batch slots
 * are discarded as one batch after the transfer is synchronized.
 */
public final class TrashCleaner {

    private static final int TOTAL_SLOTS = TrashPageInfo.TOTAL_SLOTS;
    private static final int CONTENT_SLOTS = TrashPageInfo.CONTENT_SLOTS;
    private static final int INVENTORY_START = TrashPageInfo.INVENTORY_START;
    private static final int INVENTORY_END = TrashPageInfo.INVENTORY_END;
    private static final int VERIFY_MIN_TICKS = 2;
    private static final int VERIFY_MAX_TICKS = 15;
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

    private static final class BatchMove {
        final int sourceSlot;
        final int inventorySlot;
        final String expectedSignature;

        BatchMove(int sourceSlot, int inventorySlot, String expectedSignature) {
            this.sourceSlot = sourceSlot;
            this.inventorySlot = inventorySlot;
            this.expectedSignature = expectedSignature;
        }
    }

    private static boolean active;
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
    private static final List<BatchMove> currentBatch = new ArrayList<>();
    private static int pagesFlipped;
    private static int externalListRefreshTicks;

    private TrashCleaner() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void begin() {
        active = true;
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
            // A server correction can arrive one or two ticks after the phase
            // changes. Pause without clicking until the cursor is synchronized.
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
        // The information item reports the total page count. The current page
        // is tracked locally because this GUI does not expose a current-page
        // number in the item lore.
        if (currentPage < 1) {
            currentPage = 1;
        }

        currentBatch.clear();

        List<Integer> emptyInventorySlots = findEmptyInventorySlots(handler);
        int inventoryIndex = 0;
        for (int sourceSlot = 0; sourceSlot < CONTENT_SLOTS
            && inventoryIndex < emptyInventorySlots.size(); sourceSlot++) {
            // Never include the operation row (36-53) in a clear batch.
            ItemStack stack = handler.getSlot(sourceSlot).getStack();
            if (!stack.isEmpty() && TrashClearFilter.shouldDiscard(stack)) {
                currentBatch.add(new BatchMove(
                    sourceSlot,
                    emptyInventorySlots.get(inventoryIndex++),
                    TrashPageInfo.stackSignature(stack)
                ));
            }
        }

        if (!currentBatch.isEmpty()) {
            transferBatch(client, handler);
            return;
        }

        if (hasDiscardableContent(handler) && emptyInventorySlots.isEmpty()) {
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

    private static void transferBatch(MinecraftClient client, ScreenHandler handler) {
        // Each pair leaves the cursor empty and keeps all source/target slots stable.
        for (BatchMove move : currentBatch) {
            click(client, handler, move.sourceSlot, 0, SlotActionType.PICKUP);
            click(client, handler, move.inventorySlot, 0, SlotActionType.PICKUP);
        }
        phase = Phase.WAIT_TRANSFER;
        waitTicks = 0;
    }

    private static void tickTransfer(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) return;

        // PICKUP(source) followed by PICKUP(empty inventory slot) is a
        // two-click transfer. A delayed server update can leave the source
        // stack on the cursor for a few ticks. Do not inspect or discard the
        // batch until that cursor transaction has settled.
        if (!handler.getCursorStack().isEmpty()) {
            retryTransferCursor(client, handler);
            if (waitTicks >= CURSOR_WAIT_MAX_TICKS) {
                abort(client, "垃圾桶物品转移超时，光标上的物品未能归还到背包");
            }
            return;
        }

        for (BatchMove move : currentBatch) {
            ItemStack source = handler.getSlot(move.sourceSlot).getStack();
            ItemStack destination = handler.getSlot(move.inventorySlot).getStack();
            if (!source.isEmpty() || !TrashPageInfo.stackSignature(destination).equals(move.expectedSignature)) {
                if (waitTicks >= VERIFY_MAX_TICKS) {
                    abort(client, "垃圾桶物品未能完整转入背包，已停止");
                }
                return;
            }
        }

        // The batch is now in the player's inventory. Discard only these known targets.
        for (BatchMove move : currentBatch) {
            click(client, handler, move.inventorySlot, 1, SlotActionType.THROW);
        }
        phase = Phase.WAIT_DISCARD;
        waitTicks = 0;
    }

    private static void tickDiscard(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) return;

        for (BatchMove move : currentBatch) {
            if (!handler.getSlot(move.inventorySlot).getStack().isEmpty()) {
                if (waitTicks >= VERIFY_MAX_TICKS) {
                    abort(client, "背包中的垃圾桶物品未能丢弃，已停止");
                }
                return;
            }
        }

        currentBatch.clear();
        phase = Phase.SCAN;
    }

    private static void tickFlip(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < FLIP_MIN_TICKS) return;

        // The next-page control is a server-side item. Its click may leave the
        // item on the predicted cursor until the page update arrives; this is
        // part of navigation and must not be treated as user interference.
        if (!handler.getCursorStack().isEmpty()) {
            if (flipButtonSlot >= 0 && (waitTicks & 2) == 0) {
                ItemStack buttonStack = handler.getSlot(flipButtonSlot).getStack();
                String cursorSignature = TrashPageInfo.stackSignature(handler.getCursorStack());
                if (cursorSignature.equals(expectedFlipCursorSignature)
                    && buttonStack.isEmpty()) {
                    // Put the predicted navigation item back into its control
                    // slot. The page update is handled on the next tick.
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

        // The info item reports the total number of item pages, not necessarily
        // the current page. The local counter is authoritative. For non-empty
        // pages, wait for a changed signature and one stable follow-up tick. An
        // empty page can legitimately equal the already-cleared previous page,
        // so accept that case only at the timeout after the server had time to
        // process the page click.
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
            // Keep a short settle window: the server may send the cursor
            // correction one tick after the page contents.
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

    private static void retryTransferCursor(MinecraftClient client, ScreenHandler handler) {
        if ((waitTicks & 3) != 0) {
            return;
        }
        String cursorSignature = TrashPageInfo.stackSignature(handler.getCursorStack());
        for (BatchMove move : currentBatch) {
            if (!move.expectedSignature.equals(cursorSignature)) {
                continue;
            }
            if (handler.getSlot(move.inventorySlot).getStack().isEmpty()) {
                // The second pickup may have been rejected while the first
                // click was accepted. Retry only the matching destination slot.
                click(client, handler, move.inventorySlot, 0, SlotActionType.PICKUP);
            }
            return;
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

    private static void click(MinecraftClient client, ScreenHandler handler, int slot, int button, SlotActionType type) {
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
