package com.example.trashcandetector.client;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import java.nio.file.Path;

/** MaLiLib-backed settings shared by the automation services and config screen. */
public final class TrashCanDetectorConfigs implements IConfigHandler {

    public static final ConfigBoolean AUTO_CLEAR_TRASH = new ConfigBoolean(
        "autoClearTrash", false, "检测到垃圾桶刷新后自动打开并清空。"
    ).translatedName("自动清空垃圾桶");
    public static final ConfigBoolean POINT_BUYER_ENABLED = new ConfigBoolean(
        "pointBuyerEnabled", false, "每 60 秒后台执行一次 /botmanager buy。"
    ).translatedName("自动购买积分");
    public static final ConfigBoolean AUTO_ENABLE_FLIGHT_DEVICE = new ConfigBoolean(
        "autoEnableFlightDevice", false, "进入游戏或切换维度后自动开启无尽飞行器。"
    ).translatedName("自动开启无尽飞行器");
    public static final ConfigInteger POINTS_PER_PURCHASE = new ConfigInteger(
        "pointsPerPurchase", 1, 1, 100000, "每次发送 botmanager buy 时购买的积分数量。"
    ).translatedName("每次购买积分数量");
    public static final ConfigBoolean AUTO_PICK_ON_REFRESH = new ConfigBoolean(
        "autoPickOnRefresh", false, "检测到垃圾桶刷新后自动搜索指定物品。"
    ).translatedName("刷新后自动搜索指定物品");
    public static final ConfigStringList PICK_ITEM_IDS = new ConfigStringList(
        "pickItemIds", ImmutableList.of(), "从垃圾桶中搜索的物品 ID，每行一个。"
    ).translatedName("指定物品 ID");
    public static final ConfigOptionList CLEAR_MODE = new ConfigOptionList(
        "clearMode", TrashClearMode.ALL, "选择自动清空垃圾桶时的物品过滤模式。"
    ).translatedName("清空物品模式");
    public static final ConfigBoolean DIRECT_TRASH_DISCARD = new ConfigBoolean(
        "directTrashDiscard", true, "直接从垃圾桶槽位丢弃物品；关闭后先转移到背包再丢弃。"
    ).translatedName("直接丢弃垃圾桶物品");
    public static final ConfigStringList CLEAR_BLACKLIST = new ConfigStringList(
        "clearBlacklist", ImmutableList.of(), "清空时排除的物品 ID，每行一个。"
    ).translatedName("清空黑名单");
    public static final ConfigStringList CLEAR_WHITELIST = new ConfigStringList(
        "clearWhitelist", ImmutableList.of(), "清空时仅允许丢出的物品 ID，每行一个。"
    ).translatedName("清空白名单");
    public static final ConfigBoolean SYNC_TWEAKEROO_MINING_LIST = new ConfigBoolean(
        "syncTweakerooMiningList", false, "同步 Tweakeroo 的方块挖掘限制名单。"
    ).translatedName("同步 Tweakeroo 挖掘名单");
    public static final ConfigBoolean SYNC_PRINTER_MINING_LIST = new ConfigBoolean(
        "syncPrinterMiningList", false, "同步 Litematica Printer Hana 的挖掘名单。"
    ).translatedName("同步投影打印机挖掘名单");
    public static final ConfigHotkey CLEAR_TRASH_HOTKEY = new ConfigHotkey(
        "clearTrashHotkey", "", "立即打开并清空垃圾桶。"
    ).translatedName("立即清空垃圾桶快捷键");
    public static final ConfigHotkey START_PICK_HOTKEY = new ConfigHotkey(
        "startPickHotkey", "", "打开垃圾桶并搜索指定物品。"
    ).translatedName("开始搜索指定物品快捷键");
    public static final ConfigHotkey OPEN_CONFIG_HOTKEY = new ConfigHotkey(
        "openConfigHotkey", "", "打开 TrashCan Detector 配置界面。"
    ).translatedName("打开配置界面快捷键");
    public static final ConfigHotkey ENABLE_FLIGHT_DEVICE_HOTKEY = new ConfigHotkey(
        "enableFlightDeviceHotkey", "", "立即尝试开启无尽飞行器。"
    ).translatedName("开启无尽飞行器快捷键");

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
        AUTO_CLEAR_TRASH,
        POINT_BUYER_ENABLED,
        AUTO_ENABLE_FLIGHT_DEVICE,
        POINTS_PER_PURCHASE,
        AUTO_PICK_ON_REFRESH,
        PICK_ITEM_IDS,
        CLEAR_MODE,
        DIRECT_TRASH_DISCARD,
        CLEAR_BLACKLIST,
        CLEAR_WHITELIST,
        SYNC_TWEAKEROO_MINING_LIST,
        SYNC_PRINTER_MINING_LIST
    );
    public static final ImmutableList<ConfigHotkey> HOTKEYS = ImmutableList.of(
        CLEAR_TRASH_HOTKEY,
        START_PICK_HOTKEY,
        OPEN_CONFIG_HOTKEY,
        ENABLE_FLIGHT_DEVICE_HOTKEY
    );
    public static final ImmutableList<IConfigBase> GUI_OPTIONS = ImmutableList.<IConfigBase>builder()
        .addAll(OPTIONS)
        .addAll(HOTKEYS)
        .build();

    private static final String FILE_NAME = "trashcandetector.json";

    public static final TrashCanDetectorConfigs INSTANCE = new TrashCanDetectorConfigs();

    private TrashCanDetectorConfigs() {
    }

    public static Path file() {
        return FileUtils.getConfigDirectoryAsPath().resolve(FILE_NAME);
    }

    @Override
    public void load() {
        JsonElement element = JsonUtils.parseJsonFileAsPath(file());
        if (element != null && element.isJsonObject()) {
            JsonObject root = element.getAsJsonObject();
            ConfigUtils.readConfigBase(root, "Settings", OPTIONS);
            ConfigUtils.readHotkeys(root, "Hotkeys", HOTKEYS);
        }
        PickList.reloadFromConfig();
        TrashClearFilter.reload();
    }

    @Override
    public void save() {
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Settings", OPTIONS);
        ConfigUtils.writeHotkeys(root, "Hotkeys", HOTKEYS);
        FileUtils.createDirectoriesIfMissing(file().getParent());
        JsonUtils.writeJsonToFileAsPath(root, file());
    }

    @Override
    public void onConfigsChanged() {
        PickList.reloadFromConfig();
        TrashClearFilter.reload();
        save();
    }

    public static int pointsPerPurchase() {
        return Math.max(1, Math.min(100000, POINTS_PER_PURCHASE.getIntegerValue()));
    }

    public static void saveNow() {
        INSTANCE.save();
    }

}
