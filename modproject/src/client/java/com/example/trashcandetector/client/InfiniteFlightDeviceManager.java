package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;

import java.util.Arrays;
import java.util.Locale;

/**
 * Safely enables Slimefun's Infinite Flight Device without replacing any
 * existing item in a hand slot.
 */
final class InfiniteFlightDeviceManager {

    private static final String TARGET_ID = "minecraft:nether_star";
    private static final String TARGET_NAME = "无尽飞行器";
    private static final int PLAYER_HANDLER_SLOTS = 46;
    private static final int OFFHAND_HANDLER_SLOT = 45;
    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final int PREPARE_DELAY_TICKS = 2;
    private static final int TRANSFER_TIMEOUT_TICKS = 40;
    private static final int CHAT_TIMEOUT_TICKS = 40;
    private static final int RETURN_TIMEOUT_TICKS = 40;
    private static final int DROP_VERIFY_TICKS = 20;
    private static final int RETRY_INTERVAL_TICKS = 4;
    private static final int RETURN_RETRY_INTERVAL_TICKS = 2;
    private static final int MAX_TOGGLE_ATTEMPTS = 2;

    private enum Phase {
        IDLE,
        WAIT_PREPARE,
        WAIT_PREPARE_DROP,
        WAIT_TRANSFER,
        USE,
        WAIT_USE_RESULT,
        RETURN,
        WAIT_RETURN,
        WAIT_RETURN_DROP
    }

    private static final class Target {
        final int inventoryIndex;
        final Hand hand;
        final ItemStack stack;

        Target(int inventoryIndex, Hand hand, ItemStack stack) {
            this.inventoryIndex = inventoryIndex;
            this.hand = hand;
            this.stack = stack.copy();
        }
    }

    private static Phase phase = Phase.IDLE;
    private static boolean requestQueued;
    private static boolean manualRequest;
    private static boolean restartAfterTransition;
    private static boolean lastAutoEnabled;
    private static Boolean enabledState;
    private static ClientPlayNetworkHandler lastNetworkHandler;
    private static ClientWorld lastWorld;
    private static RegistryKey<World> lastDimension;

    private static int phaseTicks;
    private static int attempts;
    private static int originalSelectedSlot;
    private static int workingSelectedSlot = -1;
    private static boolean selectionChanged;
    private static Hand activeHand;
    private static int sourceInventoryIndex = -1;
    private static int temporaryInventoryIndex = -1;
    private static int temporarySwapButton = -1;
    private static String expectedDeviceSignature;
    private static ItemStack[] baselineInventory;
    private static int pendingDropSlot = -1;
    private static int pendingDropTargetCount = -1;
    private static String pendingDropIdentity;
    private static boolean sourceObservedEmpty;
    private static String failureReason;
    private static boolean operationSucceeded;

    private InfiniteFlightDeviceManager() {
    }

    static void tick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null || client.world == null) {
            resetConnectionState();
            return;
        }

        boolean autoEnabled = TrashCanDetectorConfigs.AUTO_ENABLE_FLIGHT_DEVICE.getBooleanValue();
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        RegistryKey<World> dimension = client.world.getRegistryKey();

        if (networkHandler != lastNetworkHandler) {
            lastNetworkHandler = networkHandler;
            lastWorld = client.world;
            lastDimension = dimension;
            enabledState = null;
            if (phase != Phase.IDLE) {
                restoreSelection(client.player.getInventory());
                resetOperation();
            }
            requestQueued = autoEnabled;
            manualRequest = false;
        } else if (lastWorld != client.world || lastDimension != dimension) {
            lastWorld = client.world;
            lastDimension = dimension;
            enabledState = null;
            if (phase != Phase.IDLE) {
                restartAfterTransition = autoEnabled;
                failureReason = null;
                phase = Phase.RETURN;
                phaseTicks = 0;
            } else {
                requestQueued |= autoEnabled;
            }
        }

        if (autoEnabled && !lastAutoEnabled && phase == Phase.IDLE) {
            requestQueued = true;
            manualRequest = false;
        } else if (!autoEnabled && !manualRequest && phase == Phase.IDLE) {
            requestQueued = false;
        }
        lastAutoEnabled = autoEnabled;

        if (phase == Phase.IDLE) {
            if (requestQueued) {
                phase = Phase.WAIT_PREPARE;
                phaseTicks = 0;
            }
            return;
        }

        if (!isSafeToTouchPlayerInventory(client)) {
            return;
        }

        switch (phase) {
            case WAIT_PREPARE -> tickPrepare(client);
            case WAIT_PREPARE_DROP -> tickPrepareDrop(client);
            case WAIT_TRANSFER -> tickTransfer(client);
            case USE -> useDevice(client);
            case WAIT_USE_RESULT -> tickUseResult();
            case RETURN -> tickReturn(client);
            case WAIT_RETURN -> tickReturnWait(client);
            case WAIT_RETURN_DROP -> tickReturnDrop(client);
            case IDLE -> {
            }
        }
    }

    static void requestEnsure() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (phase != Phase.IDLE) {
            TrashCanDetectorClient.feedback("无尽飞行器正在处理中，请稍候");
            return;
        }
        if (Boolean.TRUE.equals(enabledState)) {
            TrashCanDetectorClient.feedback("无尽飞行器已处于开启状态");
            return;
        }
        requestQueued = true;
        manualRequest = true;
        phase = Phase.WAIT_PREPARE;
        phaseTicks = 0;
        TrashCanDetectorClient.feedback("正在查找无尽飞行器并尝试开启...");
        if (client.player == null || client.getNetworkHandler() == null) {
            TrashCanDetectorClient.feedback("请先进入游戏服务器");
        }
    }

    static void handleChatMessage(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (!isFlightMessage(text)) {
            return;
        }

        boolean enabled = containsAny(text,
            "已启用", "已开启", "启用", "开启", "打开", "enabled", " on");
        boolean disabled = containsAny(text,
            "未启用", "未开启", "没有开启", "已关闭", "关闭", "禁用", "disabled", " off");

        if (disabled) {
            enabledState = false;
            if (phase == Phase.WAIT_USE_RESULT) {
                if (attempts < MAX_TOGGLE_ATTEMPTS) {
                    TrashCanDetectorClient.feedback(
                "聊天栏显示无尽飞行器未开启，正在再次右键尝试"
                    );
                    TrashCanDetectorClient.tip("无尽飞行器未开启，正在重试");
                    phase = Phase.USE;
                    phaseTicks = 2;
                } else {
                    failAndReturn("聊天栏显示无尽飞行器仍未开启");
                }
            }
        } else if (enabled) {
            enabledState = true;
            if (phase == Phase.WAIT_USE_RESULT) {
                operationSucceeded = true;
                phase = Phase.RETURN;
                phaseTicks = 0;
                TrashCanDetectorClient.tip("无尽飞行器已开启");
                TrashCanDetectorClient.feedback(
                    "聊天栏已确认无尽飞行器开启，正在放回背包"
                );
            }
        } else if (phase == Phase.WAIT_USE_RESULT
            && containsAny(text, "没有权限", "无法", "失败", "不足", "permission", "failed")) {
            failAndReturn("服务器拒绝开启无尽飞行器");
        }
    }

    static String status() {
        if (phase != Phase.IDLE) {
            return "正在处理";
        }
        if (Boolean.TRUE.equals(enabledState)) {
            return "已开启";
        }
        if (Boolean.FALSE.equals(enabledState)) {
            return "未开启";
        }
        return "未知";
    }

    private static void tickPrepare(MinecraftClient client) {
        if (++phaseTicks < PREPARE_DELAY_TICKS) {
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        // Keep this snapshot for the whole operation. In particular, don't
        // replace it after dropping a newly picked item to make a hand slot.
        if (baselineInventory == null) {
            baselineInventory = snapshotInventory(inventory);
            originalSelectedSlot = inventory.getSelectedSlot();
            workingSelectedSlot = -1;
            selectionChanged = false;
            sourceInventoryIndex = -1;
            temporaryInventoryIndex = -1;
            temporarySwapButton = -1;
            activeHand = null;
            sourceObservedEmpty = false;
            expectedDeviceSignature = null;
            attempts = 0;
            operationSucceeded = false;
            failureReason = null;
        }

        Target target = findTarget(inventory);
        if (target == null) {
            finish(client, "未找到匹配的无尽飞行器（minecraft:nether_star + 无尽飞行器）");
            return;
        }

        expectedDeviceSignature = TrashPageInfo.stackSignature(target.stack);
        sourceInventoryIndex = target.inventoryIndex;
        activeHand = target.hand;

        int selectedSlot = inventory.getSelectedSlot();
        if (target.hand == Hand.OFF_HAND || target.inventoryIndex < 9
            && target.inventoryIndex == selectedSlot) {
            phase = Phase.USE;
            phaseTicks = 0;
            if (target.hand == Hand.MAIN_HAND && target.inventoryIndex != selectedSlot) {
                selectWorkingSlot(inventory, target.inventoryIndex);
            }
            return;
        }

        if (target.hand == Hand.MAIN_HAND && target.inventoryIndex < 9) {
            selectWorkingSlot(inventory, target.inventoryIndex);
            phase = Phase.USE;
            phaseTicks = 0;
            return;
        }

        int emptyHotbar = findEmptyHotbarSlot(inventory);
        if (emptyHotbar >= 0) {
            temporaryInventoryIndex = emptyHotbar;
            temporarySwapButton = emptyHotbar;
        } else if (inventory.getStack(PlayerInventory.OFF_HAND_SLOT).isEmpty()) {
            temporaryInventoryIndex = PlayerInventory.OFF_HAND_SLOT;
            temporarySwapButton = OFFHAND_SWAP_BUTTON;
            activeHand = Hand.OFF_HAND;
        } else {
            int newItemSlot = findDefinitelyNewHandSlot(inventory);
            if (newItemSlot < 0) {
                finish(client, "没有空的手部槽位，且没有可确认的新拾取物，未替换任何物品");
                return;
            }
            if (!startDrop(client, newItemSlot, false)) {
                finish(client, "新拾取物无法安全识别，未替换任何物品");
            }
            return;
        }

        swapSourceWithTemporary(client);
        phase = Phase.WAIT_TRANSFER;
        phaseTicks = 0;
    }

    private static void tickPrepareDrop(MinecraftClient client) {
        phaseTicks++;
        ItemStack stack = client.player.getInventory().getStack(pendingDropSlot);
        if (isDropComplete(stack)) {
            clearPendingDrop();
            phase = Phase.WAIT_PREPARE;
            phaseTicks = 0;
            return;
        }
        if (!pendingDropIdentity.equals(itemIdentity(stack))) {
            finish(client, "新拾取物在丢出前发生变化，未替换任何物品");
            return;
        }
        if (phaseTicks % RETRY_INTERVAL_TICKS == 0) {
            clickInventorySlot(client, inventoryScreenSlot(pendingDropSlot), 0,
                SlotActionType.THROW);
        }
        if (phaseTicks >= DROP_VERIFY_TICKS) {
            finish(client, "新拾取物未能安全丢出，未替换任何物品");
        }
    }

    private static void tickTransfer(MinecraftClient client) {
        phaseTicks++;
        PlayerInventory inventory = client.player.getInventory();
        ItemStack source = inventory.getStack(sourceInventoryIndex);
        ItemStack temporary = getTemporaryStack(inventory);

        if (source.isEmpty() && matchesExpected(temporary)) {
            sourceObservedEmpty = true;
            if (temporaryInventoryIndex < 9) {
                selectWorkingSlot(inventory, temporaryInventoryIndex);
            }
            phase = Phase.USE;
            phaseTicks = 0;
            return;
        }
        if (matchesExpected(source) && temporary.isEmpty()
            && phaseTicks % RETRY_INTERVAL_TICKS == 0) {
            swapSourceWithTemporary(client);
        }
        if (phaseTicks >= TRANSFER_TIMEOUT_TICKS) {
            failAndReturn("无尽飞行器未能安全移到手部，未替换任何物品");
        }
    }

    private static void useDevice(MinecraftClient client) {
        PlayerInventory inventory = client.player.getInventory();
        if (activeHand == Hand.MAIN_HAND && !matchesExpected(client.player.getMainHandStack())) {
            if (++phaseTicks >= TRANSFER_TIMEOUT_TICKS) {
                failAndReturn("手部物品同步异常，未执行右键");
            }
            return;
        }
        if (activeHand == Hand.OFF_HAND && !matchesExpected(client.player.getOffHandStack())) {
            if (++phaseTicks >= TRANSFER_TIMEOUT_TICKS) {
                failAndReturn("副手物品同步异常，未执行右键");
            }
            return;
        }
        if (activeHand == Hand.MAIN_HAND && temporaryInventoryIndex < 0
            && sourceInventoryIndex >= 0 && sourceInventoryIndex < 9) {
            selectWorkingSlot(inventory, sourceInventoryIndex);
        }

        client.interactionManager.interactItem(client.player, activeHand);
        attempts++;
        phase = Phase.WAIT_USE_RESULT;
        phaseTicks = 0;
    }

    private static void tickUseResult() {
        if (++phaseTicks >= CHAT_TIMEOUT_TICKS) {
            failAndReturn("未能从聊天栏确认无尽飞行器状态，已放弃重复右键");
        }
    }

    private static void tickReturn(MinecraftClient client) {
        phaseTicks++;
        PlayerInventory inventory = client.player.getInventory();

        if (!operationSucceeded && failureReason == null) {
            failureReason = "未能确认无尽飞行器已开启";
        }

        if (temporaryInventoryIndex < 0) {
            restoreSelection(inventory);
            finish(client, operationSucceeded ? "无尽飞行器已开启" : failureReason);
            return;
        }

        ItemStack temporary = getTemporaryStack(inventory);
        ItemStack source = inventory.getStack(sourceInventoryIndex);

        // A fast client-side prediction can show the device already back in
        // its source slot before this phase is entered. Check this first so an
        // empty temporary slot is treated as success, not as a lost device.
        if (!isDevice(temporary) && findReturnedDeviceSlot(inventory) >= 0) {
            restoreSelection(inventory);
            finish(client, operationSucceeded ? "无尽飞行器已开启并放回背包" : failureReason);
            return;
        }

        if (source.isEmpty() && isDevice(temporary)) {
            swapSourceWithTemporary(client);
            phase = Phase.WAIT_RETURN;
            return;
        }

        // Wait briefly for the server's inventory packet if both slots are
        // temporarily empty or the temporary slot has not updated yet.
        if (source.isEmpty() && temporary.isEmpty()) {
            if (phaseTicks >= RETURN_TIMEOUT_TICKS) {
                finish(client, "无尽飞行器归还同步超时，未替换任何物品");
            }
            return;
        }

        if (isDevice(source) && isDevice(temporary)) {
            if (phaseTicks >= RETURN_TIMEOUT_TICKS) {
                finish(client, "检测到归还槽位存在重复物品，未执行替换");
            }
            return;
        }

        if (isDevice(temporary)
            && sourceObservedEmpty
            && isDefinitelyNewItem(sourceInventoryIndex, source, true)) {
            if (!startDrop(client, sourceInventoryIndex, true)) {
                finish(client, "原槽位中的新物品无法安全识别，未替换任何物品");
            }
            return;
        }

        if (phaseTicks >= RETURN_TIMEOUT_TICKS) {
            finish(client, failureReason == null
                ? "原槽位存在取出前已有物品，未替换任何物品"
                : failureReason + "；原槽位不是新拾取物，未替换任何物品");
        }
    }

    private static void tickReturnWait(MinecraftClient client) {
        phaseTicks++;
        PlayerInventory inventory = client.player.getInventory();
        ItemStack source = inventory.getStack(sourceInventoryIndex);
        ItemStack temporary = getTemporaryStack(inventory);
        if (!isDevice(temporary) && findReturnedDeviceSlot(inventory) >= 0) {
            restoreSelection(inventory);
            finish(client, operationSucceeded ? "无尽飞行器已开启并放回背包" : failureReason);
            return;
        }
        if (source.isEmpty() && isDevice(temporary)
            && phaseTicks % RETURN_RETRY_INTERVAL_TICKS == 0) {
            swapSourceWithTemporary(client);
        }
        if (phaseTicks >= RETURN_TIMEOUT_TICKS) {
            finish(client, "无尽飞行器未能放回原背包槽位，未替换任何物品");
        }
    }

    private static void tickReturnDrop(MinecraftClient client) {
        phaseTicks++;
        ItemStack source = client.player.getInventory().getStack(pendingDropSlot);
        if (isDropComplete(source)) {
            clearPendingDrop();
            phase = Phase.RETURN;
            phaseTicks = 0;
            return;
        }
        if (!pendingDropIdentity.equals(itemIdentity(source))) {
            finish(client, "原槽位中的新物品在丢出前发生变化，未替换任何物品");
            return;
        }
        if (phaseTicks % RETRY_INTERVAL_TICKS == 0) {
            clickInventorySlot(client, inventoryScreenSlot(pendingDropSlot), 0,
                SlotActionType.THROW);
        }
        if (phaseTicks >= DROP_VERIFY_TICKS) {
            finish(client, "新拾取物未能安全丢出，未替换任何物品");
        }
    }

    private static void swapSourceWithTemporary(MinecraftClient client) {
        clickInventorySlot(
            client,
            inventoryScreenSlot(sourceInventoryIndex),
            temporarySwapButton,
            SlotActionType.SWAP
        );
    }

    private static void clickInventorySlot(MinecraftClient client, int slot, int button,
                                           SlotActionType action) {
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler.slots.size() < PLAYER_HANDLER_SLOTS) {
            return;
        }
        client.interactionManager.clickSlot(handler.syncId, slot, button, action, client.player);
    }

    private static boolean isSafeToTouchPlayerInventory(MinecraftClient client) {
        if (client.currentScreen != null || client.interactionManager == null
            || client.player.currentScreenHandler == null
            || client.player.currentScreenHandler.slots.size() != PLAYER_HANDLER_SLOTS) {
            return false;
        }
        // Never issue a SWAP/THROW while a previous interaction left an item
        // on the cursor; doing so could move or replace an unrelated item.
        return client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private static Target findTarget(PlayerInventory inventory) {
        int selectedSlot = inventory.getSelectedSlot();
        if (isDevice(inventory.getStack(selectedSlot))) {
            return new Target(selectedSlot, Hand.MAIN_HAND,
                inventory.getStack(selectedSlot));
        }
        if (isDevice(inventory.getStack(PlayerInventory.OFF_HAND_SLOT))) {
            return new Target(-1, Hand.OFF_HAND,
                inventory.getStack(PlayerInventory.OFF_HAND_SLOT));
        }
        for (int index = 0; index < PlayerInventory.MAIN_SIZE; index++) {
            if (isDevice(inventory.getStack(index))) {
                return new Target(index, Hand.MAIN_HAND, inventory.getStack(index));
            }
        }
        return null;
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int index = 0; index < PlayerInventory.HOTBAR_SIZE; index++) {
            if (inventory.getStack(index).isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private static ItemStack[] snapshotInventory(PlayerInventory inventory) {
        ItemStack[] result = new ItemStack[PlayerInventory.OFF_HAND_SLOT + 1];
        Arrays.fill(result, ItemStack.EMPTY);
        for (int index = 0; index < PlayerInventory.MAIN_SIZE; index++) {
            result[index] = inventory.getStack(index).copy();
        }
        result[PlayerInventory.OFF_HAND_SLOT] =
            inventory.getStack(PlayerInventory.OFF_HAND_SLOT).copy();
        return result;
    }

    private static int findDefinitelyNewHandSlot(PlayerInventory inventory) {
        for (int index = 0; index < PlayerInventory.HOTBAR_SIZE; index++) {
            ItemStack stack = inventory.getStack(index);
            if (!stack.isEmpty() && isDefinitelyNewItem(index, stack, false)) {
                return index;
            }
        }
        ItemStack offhand = inventory.getStack(PlayerInventory.OFF_HAND_SLOT);
        if (!offhand.isEmpty()
            && isDefinitelyNewItem(PlayerInventory.OFF_HAND_SLOT, offhand, false)) {
            return PlayerInventory.OFF_HAND_SLOT;
        }
        return -1;
    }

    private static boolean isDefinitelyNewItem(int inventoryIndex, ItemStack stack,
                                               boolean allowDifferentBaseline) {
        return newItemCount(inventoryIndex, stack, allowDifferentBaseline) > 0;
    }

    private static int newItemCount(int inventoryIndex, ItemStack stack,
                                    boolean allowDifferentBaseline) {
        if (stack.isEmpty() || baselineInventory == null
            || inventoryIndex < 0 || inventoryIndex >= baselineInventory.length) {
            return 0;
        }
        ItemStack baseline = baselineInventory[inventoryIndex];
        if (baseline == null || baseline.isEmpty()) {
            return stack.getCount();
        }
        if (ItemStack.areItemsAndComponentsEqual(stack, baseline)) {
            return Math.max(0, stack.getCount() - baseline.getCount());
        }
        return allowDifferentBaseline ? stack.getCount() : 0;
    }

    private static boolean startDrop(MinecraftClient client, int inventoryIndex,
                                     boolean allowDifferentBaseline) {
        ItemStack stack = client.player.getInventory().getStack(inventoryIndex);
        int newCount = newItemCount(inventoryIndex, stack, allowDifferentBaseline);
        if (newCount <= 0) {
            return false;
        }

        ItemStack baseline = baselineInventory[inventoryIndex];
        pendingDropSlot = inventoryIndex;
        pendingDropTargetCount = baseline == null || baseline.isEmpty()
            || !ItemStack.areItemsAndComponentsEqual(stack, baseline) ? 0 : baseline.getCount();
        pendingDropIdentity = itemIdentity(stack);
        clickInventorySlot(client, inventoryScreenSlot(inventoryIndex), 0,
            SlotActionType.THROW);
        phase = pendingDropSlot == sourceInventoryIndex
            ? Phase.WAIT_RETURN_DROP : Phase.WAIT_PREPARE_DROP;
        phaseTicks = 0;
        return true;
    }

    private static boolean isDropComplete(ItemStack stack) {
        return stack.isEmpty()
            || (pendingDropTargetCount >= 0
                && pendingDropIdentity != null
                && pendingDropIdentity.equals(itemIdentity(stack))
                && stack.getCount() <= pendingDropTargetCount);
    }

    private static String itemIdentity(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        return Registries.ITEM.getId(stack.getItem()) + "|"
            + stack.getName().getString() + "|"
            + stack.getComponents().toString();
    }

    private static void clearPendingDrop() {
        pendingDropSlot = -1;
        pendingDropTargetCount = -1;
        pendingDropIdentity = null;
    }

    private static int findReturnedDeviceSlot(PlayerInventory inventory) {
        for (int index = 0; index <= PlayerInventory.OFF_HAND_SLOT; index++) {
            if (index == temporaryInventoryIndex || !isDevice(inventory.getStack(index))) {
                continue;
            }
            if (index == sourceInventoryIndex) {
                return index;
            }
            // A different slot is accepted only when it was empty before the
            // operation. This prevents an already-owned duplicate device from
            // being mistaken for the one currently being returned.
            if (baselineInventory != null && index < baselineInventory.length
                && baselineInventory[index].isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private static ItemStack getTemporaryStack(PlayerInventory inventory) {
        if (temporaryInventoryIndex == PlayerInventory.OFF_HAND_SLOT) {
            return inventory.getStack(PlayerInventory.OFF_HAND_SLOT);
        }
        return temporaryInventoryIndex >= 0
            ? inventory.getStack(temporaryInventoryIndex) : ItemStack.EMPTY;
    }

    private static boolean matchesExpected(ItemStack stack) {
        // The server may update stack components or count after the right
        // click. The requested identity is the item ID plus display name;
        // UUID and other changing components are intentionally ignored.
        return isDevice(stack);
    }

    private static boolean isDevice(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return TARGET_ID.equals(Registries.ITEM.getId(stack.getItem()).toString())
            && TARGET_NAME.equals(stack.getName().getString());
    }

    private static int inventoryScreenSlot(int inventoryIndex) {
        if (inventoryIndex == PlayerInventory.OFF_HAND_SLOT) {
            return OFFHAND_HANDLER_SLOT;
        }
        return inventoryIndex < PlayerInventory.HOTBAR_SIZE ? 36 + inventoryIndex : inventoryIndex;
    }

    private static void selectWorkingSlot(PlayerInventory inventory, int slot) {
        if (slot < 0 || slot >= PlayerInventory.HOTBAR_SIZE) {
            return;
        }
        if (inventory.getSelectedSlot() != slot) {
            workingSelectedSlot = slot;
            selectionChanged = true;
            inventory.setSelectedSlot(slot);
        }
    }

    private static void restoreSelection(PlayerInventory inventory) {
        if (selectionChanged && inventory.getSelectedSlot() == workingSelectedSlot) {
            inventory.setSelectedSlot(originalSelectedSlot);
        }
        selectionChanged = false;
        workingSelectedSlot = -1;
    }

    private static boolean isFlightMessage(String text) {
        return text.contains("无尽飞行器")
            || text.contains("无尽飞行")
            || text.contains("无限飞行器")
            || text.contains("无限飞行")
            || text.contains("infinite flight")
            || text.contains("flight device");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static void failAndReturn(String reason) {
        failureReason = reason;
        operationSucceeded = false;
        phase = Phase.RETURN;
        phaseTicks = 0;
    }

    private static void finish(MinecraftClient client, String message) {
        restoreSelection(client.player.getInventory());
        boolean restart = restartAfterTransition
            && TrashCanDetectorConfigs.AUTO_ENABLE_FLIGHT_DEVICE.getBooleanValue();
        resetOperation();
        if (restart) {
            restartAfterTransition = false;
            requestQueued = true;
            manualRequest = false;
            return;
        }
        restartAfterTransition = false;
        requestQueued = false;
        manualRequest = false;
        if (message != null && !message.isBlank()) {
            TrashCanDetectorClient.tip(message);
            TrashCanDetectorClient.feedback(message);
        }
    }

    private static void resetOperation() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        attempts = 0;
        activeHand = null;
        sourceInventoryIndex = -1;
        temporaryInventoryIndex = -1;
        temporarySwapButton = -1;
        expectedDeviceSignature = null;
        baselineInventory = null;
        pendingDropSlot = -1;
        pendingDropTargetCount = -1;
        pendingDropIdentity = null;
        sourceObservedEmpty = false;
        failureReason = null;
        operationSucceeded = false;
    }

    private static void resetConnectionState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            restoreSelection(client.player.getInventory());
        }
        lastNetworkHandler = null;
        lastWorld = null;
        lastDimension = null;
        enabledState = null;
        requestQueued = false;
        manualRequest = false;
        restartAfterTransition = false;
        resetOperation();
    }
}
