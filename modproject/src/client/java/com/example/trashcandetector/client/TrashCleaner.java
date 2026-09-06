package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键清空垃圾桶的客户端状态机。
 *
 * 直接对垃圾桶正文格（0-35）发送整组丢弃点击（button=1 的 THROW，等同 Ctrl+Q），
 * 不再先转入玩家背包。每批丢弃后等待服务器同步确认，本页丢完再翻下一页。
 */
public final class TrashCleaner {

    private static final int TOTAL_SLOTS = TrashPageInfo.TOTAL_SLOTS;
    private static final int CONTENT_SLOTS = TrashPageInfo.CONTENT_SLOTS;
    private static final int VERIFY_MIN_TICKS = 2;
    private static final int VERIFY_MAX_TICKS = 15;
    /** 等待确认期间，对仍未丢出的格子每隔这么多 tick 重发一次丢弃点击 */
    private static final int RETRY_INTERVAL_TICKS = 4;
    private static final int CURSOR_WAIT_MAX_TICKS = 60;
    private static final int FLIP_MIN_TICKS = 3;
    private static final int FLIP_MAX_TICKS = 30;
    private static final int MAX_PAGES = 200;
    private static final int PAGE_INFO_WAIT_MAX_TICKS = 20 * 5;
    private static final int EXTERNAL_LIST_REFRESH_TICKS = 40;
    private enum Phase {
        SCAN,
        WAIT_DISCARD,
        WAIT_FLIP,
        WAIT_FLIP_CURSOR
    }

    /** 一批待直接丢弃的正文格：记录扫描时的内容签名，用于同步确认与防误丢校验 */
    private static final class BatchThrow {
        final int sourceSlot;
        final String expectedSignature;

        BatchThrow(int sourceSlot, String expectedSignature) {
            this.sourceSlot = sourceSlot;
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
    private static final List<BatchThrow> currentBatch = new ArrayList<>();
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

        for (int sourceSlot = 0; sourceSlot < CONTENT_SLOTS; sourceSlot++) {
            // 操作栏（36-53）永远不参与清空
            ItemStack stack = handler.getSlot(sourceSlot).getStack();
            if (!stack.isEmpty() && TrashClearFilter.shouldDiscard(stack)) {
                currentBatch.add(new BatchThrow(
                    sourceSlot,
                    TrashPageInfo.stackSignature(stack)
                ));
            }
        }

        if (!currentBatch.isEmpty()) {
            throwBatch(client, handler);
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

    private static void throwBatch(MinecraftClient client, ScreenHandler handler) {
        for (BatchThrow move : currentBatch) {
            // 点击前重读签名：扫描后该格已被服务器替换时跳过，避免误丢未过滤的物品
            if (!move.expectedSignature.equals(
                TrashPageInfo.stackSignature(handler.getSlot(move.sourceSlot).getStack()))) {
                continue;
            }
            // button=1：整组直接从垃圾桶丢出，光标保持为空
            click(client, handler, move.sourceSlot, 1, SlotActionType.THROW);
        }
        phase = Phase.WAIT_DISCARD;
        waitTicks = 0;
    }

    private static void tickDiscard(MinecraftClient client, ScreenHandler handler) {
        waitTicks++;
        if (waitTicks < VERIFY_MIN_TICKS) return;

        boolean pending = false;
        for (BatchThrow move : currentBatch) {
            ItemStack stack = handler.getSlot(move.sourceSlot).getStack();
            if (stack.isEmpty()
                || !move.expectedSignature.equals(TrashPageInfo.stackSignature(stack))) {
                // 原物品已离开该格：丢弃成功，或被服务器替换（交给下一轮扫描处理）
                continue;
            }
            // 该格仍是扫描时的同一组物品：每隔几 tick 重试一次丢弃
            if (waitTicks % RETRY_INTERVAL_TICKS == 0) {
                click(client, handler, move.sourceSlot, 1, SlotActionType.THROW);
            }
            pending = true;
        }

        if (!pending) {
            currentBatch.clear();
            phase = Phase.SCAN;
            return;
        }
        if (waitTicks >= VERIFY_MAX_TICKS) {
            abort(client, "垃圾桶物品未能直接丢弃，已停止");
        }
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
