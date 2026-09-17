package com.example.trashcandetector.client;

import com.example.trashcandetector.client.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** QuickShulker-backed, server-confirmed shulker organizer. */
public final class ShulkerOrganizer {
    private static final int BOX_SLOTS = 27;
    private static final int SHULKER_HANDLER_SLOTS = 63;
    private static final int MIN_ACTION_DELAY = 1;
    private static final int ACTION_TIMEOUT = 40;
    private static final int CLOSE_SETTLE_TICKS = 8;

    private enum Phase {
        IDLE, WAIT_OPEN, SORT, EXTRACT, WAIT_SOURCE_CLOSE, DEPOSIT,
        WAIT_DEST_CLOSE, RETURN_ITEM, WAIT_RETURN_CLOSE, FINISH
    }

    private enum OpenPurpose { SORT, SOURCE, DESTINATION, RETURN }

    private record BoxRef(int inventoryIndex, int windowSlot, String displayName,
                          ItemStack initialStack) {
    }

    private static final class StackKey {
        final ItemStack prototype;

        StackKey(ItemStack stack) {
            this.prototype = stack.copyWithCount(1);
        }

        boolean matches(ItemStack stack) {
            return !stack.isEmpty()
                && ItemStack.areItemsAndComponentsEqual(prototype, stack);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof StackKey other
                && ItemStack.areItemsAndComponentsEqual(prototype, other.prototype);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashCode(prototype);
        }
    }

    private record TransferTask(BoxRef sourceBox, int sourceBoxSlot, int playerSourceIndex,
                                 StackKey key, List<BoxRef> destinations, boolean mergeOnly) {
        static TransferTask fromBox(BoxRef source, int sourceSlot, ItemStack stack,
                                     List<BoxRef> destinations, boolean mergeOnly) {
            return new TransferTask(source, sourceSlot, -1, new StackKey(stack),
                List.copyOf(destinations), mergeOnly);
        }

        static TransferTask fromPlayer(int inventoryIndex, ItemStack stack,
                                       List<BoxRef> destinations) {
            return new TransferTask(null, -1, inventoryIndex, new StackKey(stack),
                List.copyOf(destinations), false);
        }

        boolean fromPlayer() {
            return playerSourceIndex >= 0;
        }
    }

    private static final class BatchEntry {
        final TransferTask task;
        final int tempInventoryIndex;
        int destinationIndex;
        int beforeCount;

        BatchEntry(TransferTask task, int tempInventoryIndex) {
            this.task = task;
            this.tempInventoryIndex = tempInventoryIndex;
        }

        BoxRef destination() {
            return destinationIndex < task.destinations().size()
                ? task.destinations().get(destinationIndex) : null;
        }
    }

    private record PlanResult(List<BoxRef> destinations, int reservedItems) {
    }

    private static final class PendingMove {
        final BoxRef source;
        final int sourceSlot;
        final ItemStack stack;
        final List<BoxRef> destinations = new ArrayList<>();
        int remaining;

        PendingMove(BoxRef source, int sourceSlot, ItemStack stack) {
            this.source = source;
            this.sourceSlot = sourceSlot;
            this.stack = stack.copy();
            this.remaining = stack.getCount();
        }
    }

    private record TargetScore(BoxRef box, int completedStacks, int movedItems) {
    }

    private record PlanCost(int transferStacks, int sourceOpens,
                            int destinationOpens, int steps) {
    }

    private record CompactionCandidate(List<TransferTask> tasks,
                                       List<BoxRef> targets, PlanCost cost) {
    }

    private record ClickStep(int slot, int button, SlotActionType action,
                             boolean cursorEmptyBefore) {
    }

    private static boolean active;
    private static boolean manualRecovery;
    private static boolean cancelRequested;
    private static ShulkerOrganizerMode mode;
    private static Phase phase = Phase.IDLE;
    private static OpenPurpose openPurpose;
    private static List<BoxRef> boxes = List.of();
    private static final Deque<BoxRef> sortBoxes = new ArrayDeque<>();
    private static final Deque<TransferTask> tasks = new ArrayDeque<>();
    private static final Deque<ClickStep> clicks = new ArrayDeque<>();
    private static final List<BatchEntry> batch = new ArrayList<>();
    private static final List<Integer> reservedTempSlots = new ArrayList<>();
    private static TransferTask task;
    private static int destinationIndex;
    private static BoxRef batchSource;
    private static BoxRef batchDestination;
    private static int batchCursor;
    private static int step;
    private static int waitTicks;
    private static int timeoutTicks;
    private static int beforeCount;
    private static int movedStacks;
    private static int movedItems;
    private static int mergedStacks;
    private static int skippedItems;
    private static int usedBoxes;
    private static int emptyBoxes;
    private static String finishReason;
    private static boolean stopAfterReturn;
    private static int minimumRequiredBoxes;
    private static int plannedUsedBoxes;
    private static int plannedTransferStacks;
    private static int plannedSourceOpens;
    private static int plannedDestinationOpens;
    private static int plannedSteps;
    private static ClientPlayNetworkHandler startNetworkHandler;
    private static ClientWorld startWorld;
    private static RegistryKey<World> startDimension;

    private ShulkerOrganizer() {
    }

    public static boolean isActive() {
        return active || manualRecovery;
    }

    public static void requestCurrent() {
        if (active) {
            requestCancel();
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!canStart(client)) return;
        if (client.currentScreen instanceof HandledScreen<?> handled
            && isOpenShulkerScreen(client, handled)) {
            if (handled.getTitle().getString().contains("[锁定]")) {
                TrashCanDetectorClient.feedback("名称包含 [锁定] 的潜影盒不会参与整理");
                return;
            }
            startOpenCurrent();
            return;
        }
        BoxRef target = findCurrentTarget(client);
        if (target == null) {
            TrashCanDetectorClient.feedback("未找到当前、悬停、快捷栏或副手潜影盒");
            return;
        }
        start(client, ShulkerOrganizerMode.SINGLE_BOX_SORT, List.of(target), true);
    }

    public static void requestAll() {
        if (active) {
            requestCancel();
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!canStart(client)) return;
        List<BoxRef> eligible = findEligibleBoxes(client.player.getInventory());
        if (eligible.isEmpty()) {
            TrashCanDetectorClient.feedback("背包中没有可整理的潜影盒");
            return;
        }
        start(client, configuredMode(), eligible, false);
    }

    public static void cancel() {
        if (active) requestCancel();
        else TrashCanDetectorClient.feedback("当前没有潜影盒整理任务");
    }

    public static String status() {
        if (manualRecovery) return "等待手动清理鼠标或中转槽";
        return active ? "整理中（" + mode.getDisplayName() + "）" : "空闲";
    }

    public static void tick(MinecraftClient client) {
        if (manualRecovery) {
            if (client.player == null || (client.player.currentScreenHandler.getCursorStack().isEmpty()
                && !hasTemporaryItem(client))) {
                manualRecovery = false;
                reservedTempSlots.clear();
                TrashCanDetectorClient.tip("潜影盒整理安全锁已解除");
            }
            return;
        }
        if (!active) return;
        if (client.player == null || client.getNetworkHandler() == null
            || client.world == null || client.interactionManager == null) {
            hardStop("连接已断开");
            return;
        }
        if (client.getNetworkHandler() != startNetworkHandler || client.world != startWorld
            || !Objects.equals(client.world.getRegistryKey(), startDimension)) {
            if (client.currentScreen instanceof HandledScreen<?>) closeHandled(client);
            hardStop(hasTemporaryItem(client)
                ? "连接或维度已变化，中转物品保留在原空背包槽，请手动确认"
                : "连接或维度已变化");
            return;
        }

        if (cancelRequested && canCancelNow(client)) {
            if (hasTemporaryItem(client)) beginReturn(client, "已取消整理", true);
            else {
                finishReason = "已取消整理";
                closeAndFinish(client);
            }
            return;
        }

        switch (phase) {
            case WAIT_OPEN -> tickWaitOpen(client);
            case SORT -> tickSort(client);
            case EXTRACT -> tickExtract(client);
            case WAIT_SOURCE_CLOSE -> tickSourceClose(client);
            case DEPOSIT -> tickDeposit(client);
            case WAIT_DEST_CLOSE -> tickDestinationClose(client);
            case RETURN_ITEM -> tickReturn(client);
            case WAIT_RETURN_CLOSE -> tickReturnClose(client);
            case FINISH -> finish(client);
            case IDLE -> {
            }
        }
    }

    private static void startOpenCurrent() {
        active = true;
        manualRecovery = false;
        cancelRequested = false;
        mode = ShulkerOrganizerMode.SINGLE_BOX_SORT;
        boxes = List.of();
        sortBoxes.clear();
        tasks.clear();
        clicks.clear();
        resetStatistics();
        rememberConnection(MinecraftClient.getInstance());
        usedBoxes = 1;
        openPurpose = OpenPurpose.SORT;
        phase = Phase.SORT;
        TrashCanDetectorClient.feedback("开始整理当前潜影盒");
    }

    private static void start(MinecraftClient client, ShulkerOrganizerMode requestedMode,
                              List<BoxRef> eligible, boolean oneBox) {
        active = true;
        manualRecovery = false;
        cancelRequested = false;
        mode = requestedMode;
        boxes = List.copyOf(eligible);
        sortBoxes.clear();
        tasks.clear();
        clicks.clear();
        batch.clear();
        reservedTempSlots.clear();
        task = null;
        batchSource = null;
        batchDestination = null;
        resetStatistics();
        rememberConnection(client);

        if (mode == ShulkerOrganizerMode.SINGLE_BOX_SORT) {
            sortBoxes.addAll(oneBox ? boxes.subList(0, 1) : boxes);
            openNextSortBox(client);
        } else {
            if (requiresTemporarySlot(mode)
                && findEmptyPlayerSlots(client.player.getInventory()).isEmpty()) {
                hardStop("至少需要一个空背包槽作为安全中转");
                return;
            }
            if (mode == ShulkerOrganizerMode.MINIMUM_BOX_COMPACTION) {
                minimumRequiredBoxes = calculateMinimumBoxes();
                buildTasks(client);
                TrashCanDetectorClient.feedback("压缩规划：保留 " + plannedUsedBoxes
                    + " 个盒，搬运 " + plannedTransferStacks + " 组，预计开盒 "
                    + (plannedSourceOpens + plannedDestinationOpens) + " 次 / "
                    + plannedSteps + " 步");
                if (tasks.isEmpty()) {
                    finishReason = "无需跨盒压缩";
                    phase = Phase.FINISH;
                } else {
                    beginNextTask(client);
                }
            } else {
                buildTasks(client);
                if (tasks.isEmpty()) {
                    finishReason = skippedItems > 0 ? "没有可安全移动的物品" : "无需整理";
                    phase = Phase.FINISH;
                } else {
                    beginNextTask(client);
                }
            }
        }
        TrashCanDetectorClient.feedback("开始整理潜影盒：" + mode.getDisplayName());
    }

    private static void tickWaitOpen(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            if (++timeoutTicks > ACTION_TIMEOUT) abort(client, "潜影盒界面打开超时");
            return;
        }
        if (!isShulkerHandler(handled.getScreenHandler())) {
            if (++timeoutTicks > ACTION_TIMEOUT) abort(client, "QuickShulker 打开的不是 27 槽容器");
            return;
        }
        if (!handled.getScreenHandler().getCursorStack().isEmpty()) {
            abort(client, "打开潜影盒时鼠标上已有物品");
            return;
        }
        timeoutTicks = 0;
        waitTicks = 0;
        step = 0;
        phase = switch (openPurpose) {
            case SORT -> Phase.SORT;
            case SOURCE -> Phase.EXTRACT;
            case DESTINATION -> Phase.DEPOSIT;
            case RETURN -> Phase.RETURN_ITEM;
        };
    }

    private static void tickSort(MinecraftClient client) {
        ScreenHandler handler = shulkerHandler(client);
        if (handler == null) {
            abort(client, "潜影盒界面被关闭");
            return;
        }
        if (runClicks(client, handler)) return;
        if (!handler.getCursorStack().isEmpty()) {
            if (++timeoutTicks > ACTION_TIMEOUT) hardStop("鼠标物品未归位，请在当前界面手动处理");
            return;
        }

        List<ItemStack> stacks = containerStacks(handler);
        if (queueOneMerge(stacks) || queueOneSortSwap(stacks)) return;

        closeHandled(client);
        waitTicks = 0;
        phase = Phase.WAIT_DEST_CLOSE;
    }

    private static boolean queueOneMerge(List<ItemStack> stacks) {
        for (int target = 0; target < BOX_SLOTS; target++) {
            ItemStack left = stacks.get(target);
            if (left.isEmpty() || left.getCount() >= left.getMaxCount()) continue;
            for (int source = target + 1; source < BOX_SLOTS; source++) {
                ItemStack right = stacks.get(source);
                if (right.isEmpty() || !ItemStack.areItemsAndComponentsEqual(left, right)) continue;
                int room = left.getMaxCount() - left.getCount();
                clicks.add(new ClickStep(source, 0, SlotActionType.PICKUP, true));
                clicks.add(new ClickStep(target, 0, SlotActionType.PICKUP, false));
                if (right.getCount() > room) {
                    clicks.add(new ClickStep(source, 0, SlotActionType.PICKUP, false));
                }
                mergedStacks++;
                movedStacks++;
                movedItems += Math.min(room, right.getCount());
                return true;
            }
        }
        return false;
    }

    private static boolean queueOneSortSwap(List<ItemStack> stacks) {
        List<ItemStack> desired = stacks.stream().filter(stack -> !stack.isEmpty())
            .sorted(ShulkerCategoryRules.stackComparator()).map(ItemStack::copy).toList();
        for (int target = 0; target < BOX_SLOTS; target++) {
            ItemStack wanted = target < desired.size() ? desired.get(target) : ItemStack.EMPTY;
            ItemStack current = stacks.get(target);
            if (ItemStack.areEqual(current, wanted)) continue;
            int source = findEqualStack(stacks, wanted, target + 1);
            if (source < 0) continue;
            clicks.add(new ClickStep(source, 0, SlotActionType.PICKUP, true));
            clicks.add(new ClickStep(target, 0, SlotActionType.PICKUP, false));
            if (!current.isEmpty()) clicks.add(new ClickStep(source, 0, SlotActionType.PICKUP, false));
            movedStacks++;
            movedItems += wanted.getCount();
            return true;
        }
        return false;
    }

    private static void tickExtract(MinecraftClient client) {
        ScreenHandler handler = shulkerHandler(client);
        if (handler == null || batchSource == null || batch.isEmpty()) {
            abort(client, "来源潜影盒状态丢失");
            return;
        }
        if (batchCursor >= batch.size()) {
            closeHandled(client);
            phase = Phase.WAIT_SOURCE_CLOSE;
            waitTicks = 0;
            step = 0;
            return;
        }

        BatchEntry entry = batch.get(batchCursor);
        int tempSlot = findPlayerHandlerSlot(handler, entry.tempInventoryIndex);
        if (tempSlot < 0) {
            abort(client, "找不到安全中转槽");
            return;
        }

        if (step == 0) {
            if (!handler.getCursorStack().isEmpty() || !handler.getSlot(tempSlot).getStack().isEmpty()) {
                abort(client, "安全中转槽或鼠标被其他操作占用");
                return;
            }
            int sourceSlot = entry.task.sourceBoxSlot();
            if (sourceSlot < 0 || sourceSlot >= BOX_SLOTS
                || !entry.task.key().matches(handler.getSlot(sourceSlot).getStack())) {
                reservedTempSlots.remove(Integer.valueOf(entry.tempInventoryIndex));
                batch.remove(batchCursor);
                return;
            }
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0,
                SlotActionType.PICKUP, client.player);
            step = 1;
            waitTicks = 0;
            timeoutTicks = 0;
            return;
        }
        if (step == 1) {
            ItemStack cursor = handler.getCursorStack();
            if (entry.task.key().matches(cursor)) {
                client.interactionManager.clickSlot(handler.syncId, tempSlot, 0,
                    SlotActionType.PICKUP, client.player);
                step = 2;
                timeoutTicks = 0;
            } else if (++timeoutTicks > ACTION_TIMEOUT) {
                abort(client, "服务器未确认从来源盒取出物品");
            }
            return;
        }
        ItemStack temp = handler.getSlot(tempSlot).getStack();
        if (handler.getCursorStack().isEmpty() && entry.task.key().matches(temp)) {
            batchCursor++;
            step = 0;
            timeoutTicks = 0;
        } else if (++timeoutTicks > ACTION_TIMEOUT) {
            abort(client, "物品未能安全放入中转槽");
        }
    }

    private static void tickSourceClose(MinecraftClient client) {
        if (!waitUntilClosed(client)) return;
        if (openPurpose == OpenPurpose.RETURN) {
            openQuickShulker(client, batchSource);
            return;
        }
        if (batch.isEmpty()) finishBatch(client);
        else openNextBatchDestination(client);
    }

    private static void tickDeposit(MinecraftClient client) {
        ScreenHandler handler = shulkerHandler(client);
        if (handler == null || (task == null && batch.isEmpty())) {
            abort(client, "目标潜影盒状态丢失");
            return;
        }
        if (!batch.isEmpty()) tickBatchDeposit(client, handler);
        else tickPlayerDeposit(client, handler);
    }

    private static void tickPlayerDeposit(MinecraftClient client, ScreenHandler handler) {
        int sourceSlot = findPlayerHandlerSlot(handler, task.playerSourceIndex());
        if (sourceSlot < 0) {
            abort(client, "找不到玩家来源槽位");
            return;
        }
        ItemStack source = handler.getSlot(sourceSlot).getStack();
        if (!task.key().matches(source)) {
            closeDestination(client, true);
            return;
        }
        if (step == 0) {
            beforeCount = source.getCount();
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0,
                SlotActionType.QUICK_MOVE, client.player);
            step = 1;
            timeoutTicks = 0;
            return;
        }
        ItemStack after = handler.getSlot(sourceSlot).getStack();
        int afterCount = task.key().matches(after) ? after.getCount() : 0;
        if (afterCount < beforeCount) {
            movedItems += beforeCount - afterCount;
            if (afterCount == 0) movedStacks++;
            closeDestination(client, afterCount == 0);
        } else if (++timeoutTicks > ACTION_TIMEOUT) {
            closeDestination(client, false);
        }
    }

    private static void tickBatchDeposit(MinecraftClient client, ScreenHandler handler) {
        if (runClicks(client, handler)) return;
        if (step == 0 && mode == ShulkerOrganizerMode.MINIMUM_BOX_COMPACTION
            && queueOneMerge(containerStacks(handler))) {
            return;
        }

        BatchEntry entry = nextBatchEntryForDestination(client);
        if (entry == null) {
            closeHandled(client);
            phase = Phase.WAIT_DEST_CLOSE;
            waitTicks = 0;
            step = 0;
            timeoutTicks = 0;
            return;
        }

        int tempSlot = findPlayerHandlerSlot(handler, entry.tempInventoryIndex);
        if (tempSlot < 0) {
            abort(client, "找不到安全中转槽");
            return;
        }
        ItemStack temp = handler.getSlot(tempSlot).getStack();
        if (!temp.isEmpty() && !entry.task.key().matches(temp)) {
            beginReturn(client, "中转槽物品发生变化", true);
            return;
        }

        if (entry.task.mergeOnly()) {
            tickBatchMergeDeposit(client, handler, entry, tempSlot, temp);
        } else {
            tickBatchQuickDeposit(client, handler, entry, tempSlot, temp);
        }
    }

    private static BatchEntry nextBatchEntryForDestination(MinecraftClient client) {
        while (batchCursor < batch.size()) {
            BatchEntry entry = batch.get(batchCursor);
            ItemStack temp = client.player.getInventory().getStack(entry.tempInventoryIndex);
            if (temp.isEmpty()) {
                batchCursor++;
                step = 0;
                continue;
            }
            if (Objects.equals(entry.destination(), batchDestination)) return entry;
            batchCursor++;
            step = 0;
        }
        return null;
    }

    private static void tickBatchQuickDeposit(MinecraftClient client, ScreenHandler handler,
                                               BatchEntry entry, int tempSlot, ItemStack temp) {
        if (temp.isEmpty()) {
            batchCursor++;
            step = 0;
            return;
        }
        if (step == 0) {
            entry.beforeCount = temp.getCount();
            client.interactionManager.clickSlot(handler.syncId, tempSlot, 0,
                SlotActionType.QUICK_MOVE, client.player);
            step = 1;
            timeoutTicks = 0;
            return;
        }

        ItemStack after = handler.getSlot(tempSlot).getStack();
        int afterCount = entry.task.key().matches(after) ? after.getCount() : 0;
        if (afterCount < entry.beforeCount) {
            movedItems += entry.beforeCount - afterCount;
            if (afterCount == 0) movedStacks++;
            else entry.destinationIndex++;
            batchCursor++;
            step = 0;
            timeoutTicks = 0;
        } else if (++timeoutTicks > ACTION_TIMEOUT) {
            entry.destinationIndex++;
            batchCursor++;
            step = 0;
            timeoutTicks = 0;
        }
    }

    private static void tickBatchMergeDeposit(MinecraftClient client, ScreenHandler handler,
                                               BatchEntry entry, int tempSlot, ItemStack temp) {
        if (step == 1) {
            int afterCount = entry.task.key().matches(temp) ? temp.getCount() : 0;
            if (afterCount < entry.beforeCount) movedItems += entry.beforeCount - afterCount;
            entry.beforeCount = 0;
            step = 0;
            timeoutTicks = 0;
            if (afterCount == 0) {
                mergedStacks++;
                movedStacks++;
                batchCursor++;
                return;
            }
        }
        if (temp.isEmpty()) {
            batchCursor++;
            return;
        }

        int mergeSlot = findMergeTarget(handler, entry.task.key());
        if (mergeSlot < 0) {
            entry.destinationIndex++;
            batchCursor++;
            return;
        }
        ItemStack target = handler.getSlot(mergeSlot).getStack();
        int room = target.getMaxCount() - target.getCount();
        entry.beforeCount = temp.getCount();
        clicks.add(new ClickStep(tempSlot, 0, SlotActionType.PICKUP, true));
        clicks.add(new ClickStep(mergeSlot, 0, SlotActionType.PICKUP, false));
        if (entry.beforeCount > room) {
            clicks.add(new ClickStep(tempSlot, 0, SlotActionType.PICKUP, false));
        }
        step = 1;
    }

    private static void closeDestination(MinecraftClient client, boolean completed) {
        closeHandled(client);
        phase = Phase.WAIT_DEST_CLOSE;
        waitTicks = 0;
        step = completed ? 2 : 1;
        timeoutTicks = 0;
    }

    private static void tickDestinationClose(MinecraftClient client) {
        if (!waitUntilClosed(client)) return;
        if (mode == ShulkerOrganizerMode.SINGLE_BOX_SORT) {
            if (sortBoxes.isEmpty()) {
                finishReason = "整理完成";
                phase = Phase.FINISH;
            } else openNextSortBox(client);
            return;
        }
        if (!batch.isEmpty()) {
            if (cancelRequested && hasTemporaryItem(client)) {
                beginReturn(client, "已取消整理", true);
            } else {
                openNextBatchDestination(client);
            }
            return;
        }
        if (step == 2 || (task.fromPlayer()
            && !task.key().matches(client.player.getInventory().getStack(task.playerSourceIndex())))) {
            finishCurrentTask(client);
            return;
        }
        destinationIndex++;
        if (destinationIndex < task.destinations().size()) openNextDestination(client);
        else if (task.fromPlayer()) finishCurrentTask(client);
        else {
            beginReturn(client, "目标盒容量已满", false);
        }
    }

    private static void beginReturn(MinecraftClient client, String reason, boolean stopAfter) {
        if (batchSource == null || batch.isEmpty() || !hasTemporaryItem(client)) {
            if (stopAfter) {
                finishReason = reason.startsWith("已") ? reason : "已中止：" + reason;
                closeAndFinish(client);
            } else {
                finishBatch(client);
            }
            return;
        }
        stopAfterReturn = stopAfter;
        if (stopAfter) finishReason = cancelRequested ? "已取消整理" : "已中止：" + reason;
        openPurpose = OpenPurpose.RETURN;
        if (client.currentScreen instanceof HandledScreen<?>) {
            closeHandled(client);
            phase = Phase.WAIT_SOURCE_CLOSE;
            waitTicks = 0;
        } else {
            openQuickShulker(client, batchSource);
        }
    }

    private static void tickReturn(MinecraftClient client) {
        ScreenHandler handler = shulkerHandler(client);
        if (handler == null || batchSource == null || batch.isEmpty()) {
            hardStop("无法重新打开来源盒归还中转物品");
            return;
        }

        while (batchCursor < batch.size()
            && client.player.getInventory().getStack(batch.get(batchCursor).tempInventoryIndex).isEmpty()) {
            batchCursor++;
            step = 0;
        }
        if (batchCursor >= batch.size()) {
            closeHandled(client);
            phase = Phase.WAIT_RETURN_CLOSE;
            waitTicks = 0;
            return;
        }

        BatchEntry entry = batch.get(batchCursor);
        int tempSlot = findPlayerHandlerSlot(handler, entry.tempInventoryIndex);
        if (tempSlot < 0) {
            hardStop("找不到中转槽，无法归还物品");
            return;
        }
        int sourceSlot = entry.task.sourceBoxSlot();
        if (sourceSlot < 0 || sourceSlot >= BOX_SLOTS) {
            hardStop("来源盒槽位无效，无法归还中转物品");
            return;
        }
        ItemStack temp = handler.getSlot(tempSlot).getStack();
        if (step == 0) {
            if (!handler.getSlot(sourceSlot).getStack().isEmpty()) {
                hardStop("来源盒原槽位已被占用，中转物品保留在背包中");
                return;
            }
            client.interactionManager.clickSlot(handler.syncId, tempSlot, 0,
                SlotActionType.PICKUP, client.player);
            step = 1;
            timeoutTicks = 0;
            return;
        }
        if (step == 1) {
            if (handler.getSlot(tempSlot).getStack().isEmpty()
                && entry.task.key().matches(handler.getCursorStack())
                && handler.getSlot(sourceSlot).getStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0,
                    SlotActionType.PICKUP, client.player);
                step = 2;
                timeoutTicks = 0;
            } else if (++timeoutTicks > ACTION_TIMEOUT) {
                hardStop("服务器未确认拿起中转物品，请手动处理当前界面");
            }
            return;
        }
        if (handler.getCursorStack().isEmpty()
            && entry.task.key().matches(handler.getSlot(sourceSlot).getStack())) {
            batchCursor++;
            step = 0;
            timeoutTicks = 0;
        } else if (++timeoutTicks > ACTION_TIMEOUT) {
            hardStop("中转物品无法放回来源盒原槽位，请手动处理当前界面");
        }
    }

    private static void tickReturnClose(MinecraftClient client) {
        if (!waitUntilClosed(client)) return;
        if (stopAfterReturn) phase = Phase.FINISH;
        else finishBatch(client);
    }

    private static void beginNextTask(MinecraftClient client) {
        task = tasks.poll();
        destinationIndex = 0;
        step = 0;
        waitTicks = 0;
        timeoutTicks = 0;
        if (task == null) {
            finishReason = "整理完成";
            phase = Phase.FINISH;
            return;
        }
        if (task.fromPlayer()) {
            openNextDestination(client);
            return;
        }

        List<Integer> freeSlots = findEmptyPlayerSlots(client.player.getInventory());
        if (freeSlots.isEmpty()) {
            hardStop("没有空背包槽可用于安全中转");
            return;
        }
        TransferTask first = task;
        task = null;
        batch.clear();
        reservedTempSlots.clear();
        batchSource = first.sourceBox();
        batchDestination = null;
        batch.add(new BatchEntry(first, freeSlots.get(0)));
        reservedTempSlots.add(freeSlots.get(0));
        int freeIndex = 1;
        while (freeIndex < freeSlots.size() && !tasks.isEmpty()
            && Objects.equals(tasks.peek().sourceBox(), batchSource)) {
            TransferTask next = tasks.poll();
            batch.add(new BatchEntry(next, freeSlots.get(freeIndex)));
            reservedTempSlots.add(freeSlots.get(freeIndex));
            freeIndex++;
        }
        batchCursor = 0;
        openPurpose = OpenPurpose.SOURCE;
        openQuickShulker(client, batchSource);
    }

    private static void finishCurrentTask(MinecraftClient client) {
        task = null;
        step = 0;
        beginNextTask(client);
    }

    private static void finishBatch(MinecraftClient client) {
        if (hasTemporaryItem(client)) {
            beginReturn(client, "中转槽仍有物品", false);
            return;
        }
        batch.clear();
        reservedTempSlots.clear();
        batchSource = null;
        batchDestination = null;
        batchCursor = 0;
        step = 0;
        beginNextTask(client);
    }

    private static void openNextBatchDestination(MinecraftClient client) {
        BatchEntry selected = null;
        int selectedOrder = Integer.MAX_VALUE;
        for (BatchEntry entry : batch) {
            ItemStack temp = client.player.getInventory().getStack(entry.tempInventoryIndex);
            if (temp.isEmpty()) continue;
            BoxRef destination = entry.destination();
            if (destination == null) continue;
            int order = boxes.indexOf(destination);
            if (selected == null || order < selectedOrder) {
                selected = entry;
                selectedOrder = order;
            }
        }
        if (selected == null) {
            if (hasTemporaryItem(client)) beginReturn(client, "目标盒容量已满", false);
            else finishBatch(client);
            return;
        }
        batchDestination = selected.destination();
        batchCursor = 0;
        step = 0;
        openPurpose = OpenPurpose.DESTINATION;
        openQuickShulker(client, batchDestination);
    }

    private static void openNextDestination(MinecraftClient client) {
        if (task == null || destinationIndex >= task.destinations().size()) {
            if (task != null && !task.fromPlayer()) beginReturn(client, "没有可用目标盒", false);
            else finishCurrentTask(client);
            return;
        }
        openPurpose = OpenPurpose.DESTINATION;
        openQuickShulker(client, task.destinations().get(destinationIndex));
    }

    private static void openNextSortBox(MinecraftClient client) {
        BoxRef next = sortBoxes.poll();
        if (next == null) {
            finishReason = "整理完成";
            phase = Phase.FINISH;
            return;
        }
        openPurpose = OpenPurpose.SORT;
        openQuickShulker(client, next);
    }

    private static void openQuickShulker(MinecraftClient client, BoxRef box) {
        if (box == null) {
            abort(client, "潜影盒目标丢失");
            return;
        }
        if (client.currentScreen != null) {
            if (client.currentScreen instanceof HandledScreen<?>) closeHandled(client);
            else client.setScreen(null);
        }
        ItemStack current = client.player.getInventory().getStack(box.inventoryIndex());
        if (!QuickShulkerAdapter.isOpenable(current)
            || current.getItem() != box.initialStack().getItem()
            || !current.getName().getString().equals(box.displayName())) {
            abort(client, "潜影盒源槽位或名称已变化");
            return;
        }
        if (!QuickShulkerAdapter.open(current, box.windowSlot())) {
            abort(client, "QuickShulker 拒绝打开潜影盒");
            return;
        }
        phase = Phase.WAIT_OPEN;
        timeoutTicks = 0;
        waitTicks = 0;
        step = 0;
    }

    private static void buildTasks(MinecraftClient client) {
        Map<BoxRef, List<ItemStack>> contents = new LinkedHashMap<>();
        for (BoxRef box : boxes) contents.put(box, readContents(box.initialStack()));
        switch (mode) {
            case CROSS_BOX_MERGE -> buildMergeTasks(contents);
            case CATEGORY_DEPOSIT -> buildCategoryDepositTasks(client, contents);
            case CATEGORY_REBUILD -> buildCategoryRebuildTasks(contents);
            case MINIMUM_BOX_COMPACTION -> buildCompactionTasks(client, contents);
            case SINGLE_BOX_SORT -> {
            }
        }
    }

    private static int calculateMinimumBoxes() {
        Map<StackKey, Integer> totals = new HashMap<>();
        for (BoxRef box : boxes) {
            for (ItemStack stack : readContents(box.initialStack())) {
                if (!stack.isEmpty()) {
                    totals.merge(new StackKey(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        int requiredSlots = 0;
        for (Map.Entry<StackKey, Integer> entry : totals.entrySet()) {
            int max = entry.getKey().prototype.getMaxCount();
            requiredSlots += (entry.getValue() + max - 1) / max;
        }
        return (requiredSlots + BOX_SLOTS - 1) / BOX_SLOTS;
    }

    private static void buildMergeTasks(Map<BoxRef, List<ItemStack>> contents) {
        Map<BoxRef, List<ItemStack>> planned = copyContents(contents);
        for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
            BoxRef box = boxes.get(boxIndex);
            List<ItemStack> boxContents = contents.get(box);
            for (int slot = 0; slot < boxContents.size(); slot++) {
                ItemStack stack = boxContents.get(slot);
                if (stack.isEmpty()) continue;
                PlanResult plan = reserveDestinations(stack, stack.getCount(),
                    boxes.subList(0, boxIndex), planned, true, box);
                if (plan.reservedItems() > 0) {
                    tasks.add(TransferTask.fromBox(box, slot, stack,
                        plan.destinations(), true));
                    removeReservedSource(planned.get(box), slot, plan.reservedItems());
                }
            }
        }
    }

    private static void buildCompactionTasks(MinecraftClient client,
                                             Map<BoxRef, List<ItemStack>> contents) {
        int targetCount = Math.max(0, Math.min(minimumRequiredBoxes, boxes.size()));
        CompactionCandidate best = null;

        while (targetCount <= boxes.size() && best == null) {
            int sourceCount = boxes.size() - targetCount;
            for (Set<BoxRef> sources : candidateSourceSets(contents, sourceCount)) {
                List<BoxRef> targets = boxes.stream().filter(box -> !sources.contains(box)).toList();
                List<TransferTask> plan = planCompaction(contents, targets, sources);
                if (plan == null) continue;
                PlanCost cost = estimateCompactionSteps(client, contents, plan);
                CompactionCandidate candidate = new CompactionCandidate(plan, targets, cost);
                if (best == null || comparePlans(candidate, best) < 0) best = candidate;
            }
            if (best == null) targetCount++;
        }

        if (best == null) {
            best = new CompactionCandidate(List.of(), boxes, new PlanCost(0, 0, 0, 0));
        }
        plannedUsedBoxes = best.targets().size();
        plannedTransferStacks = best.cost().transferStacks();
        plannedSourceOpens = best.cost().sourceOpens();
        plannedDestinationOpens = best.cost().destinationOpens();
        plannedSteps = best.cost().steps();
        tasks.addAll(best.tasks());
    }

    /**
     * Keeps the selected boxes untouched and only empties the chosen sources.
     * Returns null when those targets cannot safely hold every source stack.
     */
    private static List<TransferTask> planCompaction(
        Map<BoxRef, List<ItemStack>> contents, List<BoxRef> targets,
        Set<BoxRef> sourceSet) {
        if (sourceSet.isEmpty()) return List.of();
        Map<BoxRef, List<ItemStack>> planned = copyContents(contents);
        for (BoxRef target : targets) normalizeStacks(planned.get(target));

        List<TransferTask> result = new ArrayList<>();
        List<BoxRef> sources = sourceSet.stream().sorted((left, right) -> {
            int compare = Integer.compare(nonEmptyStacks(contents.get(right)),
                nonEmptyStacks(contents.get(left)));
            return compare != 0 ? compare
                : Integer.compare(boxes.indexOf(left), boxes.indexOf(right));
        }).toList();
        for (BoxRef source : sources) {
            List<PendingMove> moves = new ArrayList<>();
            List<ItemStack> sourceContents = contents.get(source);
            for (int slot = 0; slot < sourceContents.size(); slot++) {
                ItemStack stack = sourceContents.get(slot);
                if (!stack.isEmpty()) moves.add(new PendingMove(source, slot, stack));
            }

            while (moves.stream().anyMatch(move -> move.remaining > 0)) {
                TargetScore best = null;
                for (BoxRef target : targets) {
                    TargetScore score = scoreTarget(target, planned.get(target), moves);
                    if (score.movedItems() == 0) continue;
                    if (best == null || score.completedStacks() > best.completedStacks()
                        || (score.completedStacks() == best.completedStacks()
                            && score.movedItems() > best.movedItems())
                        || (score.completedStacks() == best.completedStacks()
                            && score.movedItems() == best.movedItems()
                            && boxes.indexOf(score.box()) < boxes.indexOf(best.box()))) {
                        best = score;
                    }
                }
                if (best == null) return null;

                List<ItemStack> targetContents = planned.get(best.box());
                for (PendingMove move : moves) {
                    if (move.remaining <= 0) continue;
                    int moved = reserveCapacity(targetContents, move.stack,
                        move.remaining, false);
                    if (moved > 0) {
                        move.destinations.add(best.box());
                        move.remaining -= moved;
                    }
                }
            }

            moves.sort((left, right) -> {
                int leftTarget = left.destinations.isEmpty() ? Integer.MAX_VALUE
                    : boxes.indexOf(left.destinations.get(0));
                int rightTarget = right.destinations.isEmpty() ? Integer.MAX_VALUE
                    : boxes.indexOf(right.destinations.get(0));
                int compare = Integer.compare(leftTarget, rightTarget);
                return compare != 0 ? compare : Integer.compare(left.sourceSlot, right.sourceSlot);
            });
            for (PendingMove move : moves) {
                result.add(TransferTask.fromBox(move.source, move.sourceSlot, move.stack,
                    move.destinations, false));
            }
        }
        return result;
    }

    private static List<Set<BoxRef>> candidateSourceSets(
        Map<BoxRef, List<ItemStack>> contents, int sourceCount) {
        if (sourceCount <= 0) return List.of(Set.of());
        if (sourceCount >= boxes.size()) return List.of(new HashSet<>(boxes));

        List<Set<BoxRef>> result = new ArrayList<>();
        long combinations = combinationCount(boxes.size(), sourceCount, 1024);
        if (combinations <= 1024) {
            enumerateSourceSets(0, sourceCount, new ArrayList<>(), result);
            return result;
        }

        List<BoxRef> ranked = boxes.stream().sorted((left, right) -> {
            int compare = Integer.compare(nonEmptyStacks(contents.get(left)),
                nonEmptyStacks(contents.get(right)));
            if (compare != 0) return compare;
            compare = Integer.compare(totalItems(contents.get(left)), totalItems(contents.get(right)));
            return compare != 0 ? compare
                : Integer.compare(boxes.indexOf(right), boxes.indexOf(left));
        }).toList();
        Set<BoxRef> base = new HashSet<>(ranked.subList(0, sourceCount));
        addUniqueSourceSet(result, base);

        for (BoxRef removed : List.copyOf(base)) {
            for (BoxRef added : ranked) {
                if (base.contains(added)) continue;
                Set<BoxRef> swapped = new HashSet<>(base);
                swapped.remove(removed);
                swapped.add(added);
                addUniqueSourceSet(result, swapped);
                if (result.size() >= 128) return result;
            }
        }

        Set<BoxRef> tail = new HashSet<>(boxes.subList(boxes.size() - sourceCount, boxes.size()));
        addUniqueSourceSet(result, tail);
        return result;
    }

    private static void enumerateSourceSets(int start, int remaining,
                                            List<BoxRef> selected,
                                            List<Set<BoxRef>> result) {
        if (remaining == 0) {
            result.add(new HashSet<>(selected));
            return;
        }
        for (int index = start; index <= boxes.size() - remaining; index++) {
            selected.add(boxes.get(index));
            enumerateSourceSets(index + 1, remaining - 1, selected, result);
            selected.remove(selected.size() - 1);
        }
    }

    private static void addUniqueSourceSet(List<Set<BoxRef>> result, Set<BoxRef> candidate) {
        if (result.stream().noneMatch(existing -> existing.equals(candidate))) {
            result.add(Set.copyOf(candidate));
        }
    }

    private static long combinationCount(int count, int selected, long limit) {
        int k = Math.min(selected, count - selected);
        long result = 1;
        for (int index = 1; index <= k; index++) {
            result = result * (count - k + index) / index;
            if (result > limit) return limit + 1;
        }
        return result;
    }

    private static int nonEmptyStacks(List<ItemStack> contents) {
        int result = 0;
        for (ItemStack stack : contents) if (!stack.isEmpty()) result++;
        return result;
    }

    private static int totalItems(List<ItemStack> contents) {
        int result = 0;
        for (ItemStack stack : contents) result += stack.getCount();
        return result;
    }

    private static int comparePlans(CompactionCandidate left, CompactionCandidate right) {
        int compare = Integer.compare(left.cost().steps(), right.cost().steps());
        if (compare != 0) return compare;
        compare = Integer.compare(left.cost().transferStacks(), right.cost().transferStacks());
        if (compare != 0) return compare;
        compare = Integer.compare(left.cost().destinationOpens(), right.cost().destinationOpens());
        if (compare != 0) return compare;
        int leftFirst = left.tasks().isEmpty() ? Integer.MAX_VALUE
            : boxes.indexOf(left.tasks().get(0).sourceBox());
        int rightFirst = right.tasks().isEmpty() ? Integer.MAX_VALUE
            : boxes.indexOf(right.tasks().get(0).sourceBox());
        return Integer.compare(rightFirst, leftFirst);
    }

    private static TargetScore scoreTarget(BoxRef target, List<ItemStack> contents,
                                           List<PendingMove> moves) {
        List<ItemStack> simulated = contents.stream().map(ItemStack::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int completed = 0;
        int movedItems = 0;
        for (PendingMove move : moves) {
            if (move.remaining <= 0) continue;
            int moved = reserveCapacity(simulated, move.stack, move.remaining, false);
            movedItems += moved;
            if (moved == move.remaining) completed++;
        }
        return new TargetScore(target, completed, movedItems);
    }

    private static PlanCost estimateCompactionSteps(MinecraftClient client,
                                                    Map<BoxRef, List<ItemStack>> contents,
                                                    List<TransferTask> plan) {
        int sourceOpens = 0;
        int destinationOpens = 0;
        int transferSlots = Math.max(1,
            findEmptyPlayerSlots(client.player.getInventory()).size());
        int index = 0;
        while (index < plan.size()) {
            BoxRef source = plan.get(index).sourceBox();
            int end = index;
            while (end < plan.size() && Objects.equals(plan.get(end).sourceBox(), source)) end++;
            for (int batchStart = index; batchStart < end; batchStart += transferSlots) {
                int batchEnd = Math.min(end, batchStart + transferSlots);
                Set<BoxRef> destinations = new HashSet<>();
                for (int taskIndex = batchStart; taskIndex < batchEnd; taskIndex++) {
                    destinations.addAll(plan.get(taskIndex).destinations());
                }
                sourceOpens++;
                destinationOpens += destinations.size();
            }
            index = end;
        }
        int transferClicks = plan.size() * 2;
        for (TransferTask transfer : plan) transferClicks += transfer.destinations().size();
        Set<BoxRef> openedTargets = new HashSet<>();
        for (TransferTask transfer : plan) openedTargets.addAll(transfer.destinations());
        for (BoxRef target : openedTargets) {
            transferClicks += normalizationClicks(contents.get(target));
        }
        return new PlanCost(plan.size(), sourceOpens, destinationOpens,
            (sourceOpens + destinationOpens) * 2 + transferClicks);
    }

    private static int normalizationClicks(List<ItemStack> original) {
        List<ItemStack> contents = original.stream().map(ItemStack::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int clicks = 0;
        for (int target = 0; target < contents.size(); target++) {
            ItemStack left = contents.get(target);
            if (left.isEmpty() || left.getCount() >= left.getMaxCount()) continue;
            for (int source = target + 1; source < contents.size(); source++) {
                ItemStack right = contents.get(source);
                if (right.isEmpty() || !ItemStack.areItemsAndComponentsEqual(left, right)) continue;
                int room = left.getMaxCount() - left.getCount();
                int moved = Math.min(room, right.getCount());
                clicks += right.getCount() > room ? 3 : 2;
                left.increment(moved);
                right.decrement(moved);
                if (right.isEmpty()) contents.set(source, ItemStack.EMPTY);
                if (left.getCount() >= left.getMaxCount()) break;
            }
        }
        return clicks;
    }

    private static void buildCategoryDepositTasks(MinecraftClient client,
                                                  Map<BoxRef, List<ItemStack>> contents) {
        Set<String> playerCategories = new HashSet<>();
        for (int index = 9; index < PlayerInventory.MAIN_SIZE; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            String category = stack.isEmpty() || QuickShulkerAdapter.isOpenable(stack)
                ? null : ShulkerCategoryRules.category(stack);
            if (category != null) playerCategories.add(category);
        }
        Map<String, List<ItemStack>> incoming = new HashMap<>();
        for (int index = 9; index < PlayerInventory.MAIN_SIZE; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (stack.isEmpty() || QuickShulkerAdapter.isOpenable(stack)) continue;
            String category = ShulkerCategoryRules.category(stack);
            if (category != null) incoming.computeIfAbsent(category, ignored -> new ArrayList<>()).add(stack);
        }
        Map<String, List<BoxRef>> targets = categoryTargets(contents, playerCategories, incoming);
        Map<BoxRef, List<ItemStack>> planned = copyContents(contents);
        for (int index = 9; index < PlayerInventory.MAIN_SIZE; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (stack.isEmpty() || QuickShulkerAdapter.isOpenable(stack)) continue;
            String category = ShulkerCategoryRules.category(stack);
            List<BoxRef> candidates = targets.getOrDefault(category, List.of());
            if (category == null || candidates.isEmpty()) {
                skippedItems += stack.getCount();
                continue;
            }
            PlanResult plan = reserveDestinations(stack, stack.getCount(), candidates,
                planned, false, null);
            if (plan.reservedItems() > 0) {
                tasks.add(TransferTask.fromPlayer(index, stack, plan.destinations()));
            }
            skippedItems += stack.getCount() - plan.reservedItems();
        }
    }

    private static void buildCategoryRebuildTasks(Map<BoxRef, List<ItemStack>> contents) {
        Set<String> categories = new HashSet<>();
        Map<String, Map<StackKey, Integer>> totals = new HashMap<>();
        for (List<ItemStack> boxContents : contents.values()) {
            for (ItemStack stack : boxContents) {
                if (stack.isEmpty()) continue;
                String category = ShulkerCategoryRules.category(stack);
                if (category == null) {
                    skippedItems += stack.getCount();
                    continue;
                }
                categories.add(category);
                totals.computeIfAbsent(category, ignored -> new HashMap<>())
                    .merge(new StackKey(stack), stack.getCount(), Integer::sum);
            }
        }
        Map<String, Integer> requiredSlots = new HashMap<>();
        for (Map.Entry<String, Map<StackKey, Integer>> entry : totals.entrySet()) {
            int slots = 0;
            for (Map.Entry<StackKey, Integer> total : entry.getValue().entrySet()) {
                int max = total.getKey().prototype.getMaxCount();
                slots += (total.getValue() + max - 1) / max;
            }
            requiredSlots.put(entry.getKey(), slots);
        }
        Map<String, List<BoxRef>> targets = assignCategoryBoxes(categories, requiredSlots);
        Map<BoxRef, List<ItemStack>> planned = copyContents(contents);
        for (Map.Entry<BoxRef, List<ItemStack>> entry : contents.entrySet()) {
            BoxRef source = entry.getKey();
            List<ItemStack> sourceContents = entry.getValue();
            for (int slot = 0; slot < sourceContents.size(); slot++) {
                ItemStack stack = sourceContents.get(slot);
                if (stack.isEmpty()) continue;
                String category = ShulkerCategoryRules.category(stack);
                if (category == null) continue;
                List<BoxRef> destinations = targets.getOrDefault(category, List.of());
                if (destinations.isEmpty()) {
                    skippedItems += stack.getCount();
                } else if (!destinations.contains(source)) {
                    PlanResult plan = reserveDestinations(stack, stack.getCount(), destinations,
                        planned, false, source);
                    if (plan.reservedItems() > 0) {
                        tasks.add(TransferTask.fromBox(source, slot, stack,
                            plan.destinations(), false));
                        removeReservedSource(planned.get(source), slot, plan.reservedItems());
                    }
                    skippedItems += stack.getCount() - plan.reservedItems();
                }
            }
        }
    }

    private static Map<BoxRef, List<ItemStack>> copyContents(
        Map<BoxRef, List<ItemStack>> contents) {
        Map<BoxRef, List<ItemStack>> result = new LinkedHashMap<>();
        for (Map.Entry<BoxRef, List<ItemStack>> entry : contents.entrySet()) {
            result.put(entry.getKey(), entry.getValue().stream().map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        }
        return result;
    }

    private static PlanResult reserveDestinations(ItemStack stack, int requested,
                                                   List<BoxRef> candidates,
                                                   Map<BoxRef, List<ItemStack>> planned,
                                                   boolean mergeOnly, BoxRef source) {
        List<BoxRef> destinations = new ArrayList<>();
        int remaining = requested;
        for (BoxRef candidate : candidates) {
            if (remaining <= 0) break;
            if (Objects.equals(candidate, source)) continue;
            List<ItemStack> target = planned.get(candidate);
            if (target == null) continue;
            int reserved = reserveCapacity(target, stack, remaining, mergeOnly);
            if (reserved > 0) {
                destinations.add(candidate);
                remaining -= reserved;
            }
        }
        return new PlanResult(List.copyOf(destinations), requested - remaining);
    }

    private static int reserveCapacity(List<ItemStack> contents, ItemStack incoming,
                                       int requested, boolean mergeOnly) {
        int remaining = requested;
        for (ItemStack stack : contents) {
            if (remaining <= 0) break;
            if (!ItemStack.areItemsAndComponentsEqual(stack, incoming)
                || stack.getCount() >= stack.getMaxCount()) continue;
            int moved = Math.min(remaining, stack.getMaxCount() - stack.getCount());
            stack.increment(moved);
            remaining -= moved;
        }
        if (!mergeOnly) {
            for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
                if (!contents.get(slot).isEmpty()) continue;
                int moved = Math.min(remaining, incoming.getMaxCount());
                contents.set(slot, incoming.copyWithCount(moved));
                remaining -= moved;
            }
        }
        return requested - remaining;
    }

    private static void normalizeStacks(List<ItemStack> contents) {
        for (int target = 0; target < contents.size(); target++) {
            ItemStack left = contents.get(target);
            if (left.isEmpty() || left.getCount() >= left.getMaxCount()) continue;
            for (int source = target + 1; source < contents.size(); source++) {
                ItemStack right = contents.get(source);
                if (right.isEmpty() || !ItemStack.areItemsAndComponentsEqual(left, right)) continue;
                int moved = Math.min(right.getCount(), left.getMaxCount() - left.getCount());
                left.increment(moved);
                right.decrement(moved);
                if (right.isEmpty()) contents.set(source, ItemStack.EMPTY);
                if (left.getCount() >= left.getMaxCount()) break;
            }
        }
    }

    private static void removeReservedSource(List<ItemStack> contents, ItemStack expected,
                                             int reserved) {
        if (contents == null || reserved <= 0) return;
        int remaining = reserved;
        for (int slot = 0; slot < contents.size() && remaining > 0; slot++) {
            ItemStack source = contents.get(slot);
            if (!ItemStack.areItemsAndComponentsEqual(source, expected)) continue;
            int removed = Math.min(remaining, source.getCount());
            source.decrement(removed);
            remaining -= removed;
            if (source.isEmpty()) contents.set(slot, ItemStack.EMPTY);
        }
    }

    private static void removeReservedSource(List<ItemStack> contents, int slot,
                                             int reserved) {
        if (contents == null || slot < 0 || slot >= contents.size() || reserved <= 0) return;
        ItemStack source = contents.get(slot);
        if (source.isEmpty()) return;
        int left = source.getCount() - reserved;
        if (left <= 0) contents.set(slot, ItemStack.EMPTY);
        else source.setCount(left);
    }

    private static Map<String, List<BoxRef>> categoryTargets(
        Map<BoxRef, List<ItemStack>> contents, Set<String> requestedCategories,
        Map<String, List<ItemStack>> incoming) {
        Set<String> categories = new HashSet<>(requestedCategories);
        Map<String, List<BoxRef>> result = new HashMap<>();
        List<BoxRef> empty = new ArrayList<>();
        for (BoxRef box : boxes) {
            String boxLabel = label(box.initialStack());
            if (!boxLabel.isEmpty()) {
                categories.add(boxLabel);
                result.computeIfAbsent(boxLabel, ignored -> new ArrayList<>()).add(box);
                continue;
            }
            Map<String, Integer> counts = new HashMap<>();
            for (ItemStack stack : contents.get(box)) {
                String category = ShulkerCategoryRules.category(stack);
                if (category != null) counts.merge(category, stack.getCount(), Integer::sum);
            }
            boolean actuallyEmpty = contents.get(box).stream().allMatch(ItemStack::isEmpty);
            if (counts.isEmpty() && actuallyEmpty) empty.add(box);
            else {
                if (counts.isEmpty()) continue;
                String dominant = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                categories.add(dominant);
                result.computeIfAbsent(dominant, ignored -> new ArrayList<>()).add(box);
            }
        }
        for (String category : ShulkerCategoryRules.orderedCategories(categories)) {
            if (!result.containsKey(category) && !empty.isEmpty()) {
                result.put(category, new ArrayList<>(List.of(empty.remove(0))));
            }
            List<BoxRef> assigned = result.get(category);
            if (assigned == null) continue;
            int needed = requiredCategoryDepositBoxes(category, assigned, contents,
                incoming.getOrDefault(category, List.of()));
            while (assigned.size() < needed && !empty.isEmpty()) assigned.add(empty.remove(0));
        }
        return result;
    }

    private static int requiredCategoryDepositBoxes(String category, List<BoxRef> assigned,
                                                     Map<BoxRef, List<ItemStack>> contents,
                                                     List<ItemStack> incoming) {
        Map<StackKey, Integer> categoryTotals = new HashMap<>();
        int fixedSlots = 0;
        for (BoxRef box : assigned) {
            for (ItemStack stack : contents.get(box)) {
                if (stack.isEmpty()) continue;
                if (category.equals(ShulkerCategoryRules.category(stack))) {
                    categoryTotals.merge(new StackKey(stack), stack.getCount(), Integer::sum);
                } else {
                    fixedSlots++;
                }
            }
        }
        for (ItemStack stack : incoming) {
            categoryTotals.merge(new StackKey(stack), stack.getCount(), Integer::sum);
        }
        int requiredSlots = fixedSlots;
        for (Map.Entry<StackKey, Integer> entry : categoryTotals.entrySet()) {
            int max = entry.getKey().prototype.getMaxCount();
            requiredSlots += (entry.getValue() + max - 1) / max;
        }
        return Math.max(1, (requiredSlots + BOX_SLOTS - 1) / BOX_SLOTS);
    }

    private static Map<String, List<BoxRef>> assignCategoryBoxes(Set<String> categories,
                                                                 Map<String, Integer> slots) {
        Map<String, List<BoxRef>> result = new HashMap<>();
        List<BoxRef> available = new ArrayList<>(boxes);
        for (String category : ShulkerCategoryRules.orderedCategories(categories)) {
            int needed = Math.max(1, (slots.getOrDefault(category, 0) + BOX_SLOTS - 1) / BOX_SLOTS);
            List<BoxRef> assigned = new ArrayList<>();
            for (int index = 0; index < available.size() && assigned.size() < needed; ) {
                BoxRef candidate = available.get(index);
                if (category.equals(label(candidate.initialStack()))) {
                    assigned.add(candidate);
                    available.remove(index);
                } else index++;
            }
            while (assigned.size() < needed && !available.isEmpty()) assigned.add(available.remove(0));
            result.put(category, assigned);
        }
        return result;
    }

    private static boolean runClicks(MinecraftClient client, ScreenHandler handler) {
        if (clicks.isEmpty()) return false;
        if (waitTicks++ < MIN_ACTION_DELAY) return true;
        ClickStep click = clicks.peek();
        if (handler.getCursorStack().isEmpty() != click.cursorEmptyBefore()) {
            if (++timeoutTicks > ACTION_TIMEOUT) {
                hardStop("服务器未确认鼠标状态，请在当前界面手动放回物品");
            }
            return true;
        }
        client.interactionManager.clickSlot(handler.syncId, click.slot(), click.button(),
            click.action(), client.player);
        clicks.remove();
        waitTicks = 0;
        timeoutTicks = 0;
        return true;
    }

    private static boolean waitUntilClosed(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?>) {
            if (++timeoutTicks > ACTION_TIMEOUT) abort(client, "潜影盒界面关闭超时");
            return false;
        }
        return ++waitTicks >= CLOSE_SETTLE_TICKS;
    }

    private static boolean canCancelNow(MinecraftClient client) {
        ScreenHandler handler = client.player.currentScreenHandler;
        return clicks.isEmpty() && (handler == null || handler.getCursorStack().isEmpty());
    }

    private static void requestCancel() {
        cancelRequested = true;
        TrashCanDetectorClient.tip("正在安全取消潜影盒整理");
    }

    private static void closeAndFinish(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?>) closeHandled(client);
        phase = Phase.FINISH;
    }

    private static void closeHandled(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
    }

    private static ScreenHandler shulkerHandler(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return null;
        ScreenHandler handler = handled.getScreenHandler();
        return isShulkerHandler(handler) ? handler : null;
    }

    private static boolean isShulkerHandler(ScreenHandler handler) {
        if (handler == null || handler.slots.size() != SHULKER_HANDLER_SLOTS) return false;

        for (int slotId = 0; slotId < BOX_SLOTS; slotId++) {
            if (handler.getSlot(slotId).inventory instanceof PlayerInventory) return false;
        }

        boolean[] playerSlots = new boolean[PlayerInventory.MAIN_SIZE];
        for (int slotId = BOX_SLOTS; slotId < SHULKER_HANDLER_SLOTS; slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (!(slot.inventory instanceof PlayerInventory)
                || slot.getIndex() < 0 || slot.getIndex() >= PlayerInventory.MAIN_SIZE) {
                return false;
            }
            playerSlots[slot.getIndex()] = true;
        }
        for (boolean present : playerSlots) if (!present) return false;
        return true;
    }

    private static boolean isOpenShulkerScreen(MinecraftClient client, HandledScreen<?> handled) {
        if (!isShulkerHandler(handled.getScreenHandler()) || client.player == null) return false;
        if (handled.getScreenHandler() instanceof ShulkerBoxScreenHandler) return true;
        String title = handled.getTitle().getString();
        PlayerInventory inventory = client.player.getInventory();
        for (int index = 0; index < PlayerInventory.MAIN_SIZE; index++) {
            ItemStack stack = inventory.getStack(index);
            if (isEligibleBox(stack) && stack.getName().getString().equals(title)) return true;
        }
        ItemStack offhand = inventory.getStack(PlayerInventory.OFF_HAND_SLOT);
        return isEligibleBox(offhand) && offhand.getName().getString().equals(title);
    }

    private static int findMergeTarget(ScreenHandler handler, StackKey key) {
        for (int slot = 0; slot < BOX_SLOTS; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (key.matches(stack) && stack.getCount() < stack.getMaxCount()) return slot;
        }
        return -1;
    }

    private static int findEqualStack(List<ItemStack> stacks, ItemStack wanted, int start) {
        if (wanted.isEmpty()) return -1;
        for (int index = start; index < stacks.size(); index++) {
            if (ItemStack.areEqual(stacks.get(index), wanted)) return index;
        }
        return -1;
    }

    private static int findPlayerHandlerSlot(ScreenHandler handler, int inventoryIndex) {
        for (int slotId = BOX_SLOTS; slotId < handler.slots.size(); slotId++) {
            Slot slot = handler.getSlot(slotId);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == inventoryIndex) return slotId;
        }
        return -1;
    }

    private static List<ItemStack> containerStacks(ScreenHandler handler) {
        List<ItemStack> result = new ArrayList<>(BOX_SLOTS);
        for (int slot = 0; slot < BOX_SLOTS; slot++) result.add(handler.getSlot(slot).getStack().copy());
        return result;
    }

    private static List<ItemStack> readContents(ItemStack box) {
        DefaultedList<ItemStack> result = DefaultedList.ofSize(BOX_SLOTS, ItemStack.EMPTY);
        ContainerComponent component = box.get(DataComponentTypes.CONTAINER);
        if (component != null) component.copyTo(result);
        return result.stream().map(ItemStack::copy).toList();
    }

    private static boolean hasTemporaryItem(MinecraftClient client) {
        if (client.player == null) return false;
        for (int inventoryIndex : reservedTempSlots) {
            if (!client.player.getInventory().getStack(inventoryIndex).isEmpty()) return true;
        }
        return false;
    }

    private static List<Integer> findEmptyPlayerSlots(PlayerInventory inventory) {
        List<Integer> result = new ArrayList<>();
        for (int index = 9; index < PlayerInventory.MAIN_SIZE; index++) {
            if (inventory.getStack(index).isEmpty()) result.add(index);
        }
        for (int index = 0; index < PlayerInventory.HOTBAR_SIZE; index++) {
            if (inventory.getStack(index).isEmpty()) result.add(index);
        }
        return result;
    }

    private static List<BoxRef> findEligibleBoxes(PlayerInventory inventory) {
        List<BoxRef> result = new ArrayList<>();
        for (int index = 0; index < PlayerInventory.MAIN_SIZE; index++) {
            ItemStack stack = inventory.getStack(index);
            if (isEligibleBox(stack)) {
                result.add(boxRef(index, stack));
            }
        }
        return result;
    }

    private static BoxRef findCurrentTarget(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> handled) {
            Slot focused = ((HandledScreenAccessor) handled).trashcandetector$getFocusedSlot();
            if (focused != null && focused.inventory == client.player.getInventory()
                && isEligibleBox(focused.getStack())) {
                return new BoxRef(focused.getIndex(), focused.id,
                    focused.getStack().getName().getString(), focused.getStack().copy());
            }
        }
        PlayerInventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (isEligibleBox(inventory.getStack(selected))) {
            return boxRef(selected, inventory.getStack(selected));
        }
        if (isEligibleBox(inventory.getStack(PlayerInventory.OFF_HAND_SLOT))) {
            return boxRef(PlayerInventory.OFF_HAND_SLOT, inventory.getStack(PlayerInventory.OFF_HAND_SLOT));
        }
        return null;
    }

    private static boolean isEligibleBox(ItemStack stack) {
        return QuickShulkerAdapter.isOpenable(stack)
            && !stack.getName().getString().contains("[锁定]");
    }

    private static BoxRef boxRef(int inventoryIndex, ItemStack stack) {
        return new BoxRef(inventoryIndex, windowSlot(inventoryIndex),
            stack.getName().getString(), stack.copy());
    }

    private static int windowSlot(int inventoryIndex) {
        if (inventoryIndex == PlayerInventory.OFF_HAND_SLOT) return 45;
        return inventoryIndex < PlayerInventory.HOTBAR_SIZE ? 36 + inventoryIndex : inventoryIndex;
    }

    private static String label(ItemStack stack) {
        String name = stack.isEmpty() ? "" : stack.getName().getString();
        int left = name.indexOf('[');
        int right = name.indexOf(']', left + 1);
        return left >= 0 && right > left ? name.substring(left + 1, right).trim() : "";
    }

    private static ShulkerOrganizerMode configuredMode() {
        Object value = TrashCanDetectorConfigs.SHULKER_ORGANIZER_MODE.getOptionListValue();
        return value instanceof ShulkerOrganizerMode organizerMode
            ? organizerMode : ShulkerOrganizerMode.MINIMUM_BOX_COMPACTION;
    }

    private static boolean requiresTemporarySlot(ShulkerOrganizerMode requestedMode) {
        return requestedMode == ShulkerOrganizerMode.CROSS_BOX_MERGE
            || requestedMode == ShulkerOrganizerMode.CATEGORY_REBUILD
            || requestedMode == ShulkerOrganizerMode.MINIMUM_BOX_COMPACTION;
    }

    private static boolean canStart(MinecraftClient client) {
        if (!TrashCanDetectorConfigs.SHULKER_ORGANIZER_ENABLED.getBooleanValue()) {
            TrashCanDetectorClient.feedback("潜影盒整理功能未启用");
            return false;
        }
        if (client.player == null || client.getNetworkHandler() == null) {
            TrashCanDetectorClient.feedback("请先进入游戏服务器");
            return false;
        }
        if (!QuickShulkerAdapter.isAvailable()) {
            TrashCanDetectorClient.feedback(QuickShulkerAdapter.unavailableReason());
            return false;
        }
        if (TrashCanDetectorClient.isBusy() || InfiniteFlightDeviceManager.isActive()) {
            TrashCanDetectorClient.feedback("当前已有其他自动化操作正在运行");
            return false;
        }
        return true;
    }

    private static void computeBoxStatistics(PlayerInventory inventory) {
        usedBoxes = 0;
        emptyBoxes = 0;
        for (BoxRef box : boxes) {
            ItemStack current = inventory.getStack(box.inventoryIndex());
            if (!QuickShulkerAdapter.isOpenable(current)) continue;
            boolean empty = readContents(current).stream().allMatch(ItemStack::isEmpty);
            if (empty) emptyBoxes++; else usedBoxes++;
        }
    }

    private static void resetStatistics() {
        batch.clear();
        reservedTempSlots.clear();
        batchSource = null;
        batchDestination = null;
        batchCursor = 0;
        step = 0;
        waitTicks = 0;
        timeoutTicks = 0;
        beforeCount = 0;
        movedStacks = 0;
        movedItems = 0;
        mergedStacks = 0;
        skippedItems = 0;
        usedBoxes = 0;
        emptyBoxes = 0;
        finishReason = null;
        stopAfterReturn = false;
        minimumRequiredBoxes = 0;
        plannedUsedBoxes = 0;
        plannedTransferStacks = 0;
        plannedSourceOpens = 0;
        plannedDestinationOpens = 0;
        plannedSteps = 0;
    }

    private static void rememberConnection(MinecraftClient client) {
        startNetworkHandler = client.getNetworkHandler();
        startWorld = client.world;
        startDimension = client.world == null ? null : client.world.getRegistryKey();
    }

    private static void abort(MinecraftClient client, String reason) {
        clicks.clear();
        ScreenHandler handler = client.player == null ? null : client.player.currentScreenHandler;
        if (handler != null && !handler.getCursorStack().isEmpty()) {
            hardStop(reason + "；鼠标仍有物品，请在当前界面手动放回");
        } else if (hasTemporaryItem(client)) {
            beginReturn(client, reason, true);
        } else {
            finishReason = "已中止：" + reason;
            closeAndFinish(client);
        }
    }

    private static void hardStop(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        manualRecovery = client.player != null
            && (!client.player.currentScreenHandler.getCursorStack().isEmpty()
                || hasTemporaryItem(client));
        active = false;
        cancelRequested = false;
        phase = Phase.IDLE;
        tasks.clear();
        sortBoxes.clear();
        clicks.clear();
        batch.clear();
        task = null;
        startNetworkHandler = null;
        startWorld = null;
        startDimension = null;
        TrashCanDetectorClient.feedback("潜影盒整理已停止：" + reason);
        TrashCanDetectorClient.tip(manualRecovery
            ? "潜影盒整理已停止，请先清理鼠标或中转槽"
            : "潜影盒整理已停止");
    }

    private static void finish(MinecraftClient client) {
        if (client.player != null && (!client.player.currentScreenHandler.getCursorStack().isEmpty()
            || hasTemporaryItem(client) || !batch.isEmpty())) {
            hardStop("收尾检查发现鼠标或中转槽仍有物品，未标记为完成");
            return;
        }
        if (client.currentScreen instanceof HandledScreen<?>) closeHandled(client);
        if (!boxes.isEmpty()) computeBoxStatistics(client.player.getInventory());
        String reason = finishReason == null ? "整理完成" : finishReason;
        if (mode == ShulkerOrganizerMode.MINIMUM_BOX_COMPACTION
            && minimumRequiredBoxes > 0 && usedBoxes > minimumRequiredBoxes) {
            reason += "（理论最少 " + minimumRequiredBoxes + " 个，当前 " + usedBoxes + " 个）";
        }
        active = false;
        manualRecovery = false;
        cancelRequested = false;
        phase = Phase.IDLE;
        tasks.clear();
        sortBoxes.clear();
        clicks.clear();
        batch.clear();
        reservedTempSlots.clear();
        task = null;
        batchSource = null;
        batchDestination = null;
        startNetworkHandler = null;
        startWorld = null;
        startDimension = null;
        TrashCanDetectorClient.feedback(reason + "；移动 " + movedStacks + " 组 / "
            + movedItems + " 件，合并 " + mergedStacks + " 次，使用 " + usedBoxes
            + " 个盒，空盒 " + emptyBoxes + " 个，未分类 " + skippedItems + " 件");
        TrashCanDetectorClient.tip(reason);
    }
}
