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
    public static final ConfigBoolean AUTO_SEND_ZZZ_WHILE_SLEEPING = new ConfigBoolean(
        "autoSendZzzWhileSleeping", false, "每次进入睡眠状态时自动发送一次聊天消息 zzz。"
    ).translatedName("睡觉时自动发送 zzz");
    public static final ConfigBoolean SHULKER_ORGANIZER_ENABLED = new ConfigBoolean(
        "shulkerOrganizerEnabled", false, "允许使用 QuickShulker 自动整理潜影盒。"
    ).translatedName("启用潜影盒整理");
    public static final ConfigOptionList SHULKER_ORGANIZER_MODE = new ConfigOptionList(
        "shulkerOrganizerMode", ShulkerOrganizerMode.SINGLE_BOX_SORT,
        "整理全部潜影盒快捷键使用的模式。"
    ).translatedName("潜影盒整理模式");
    public static final ConfigStringList SHULKER_CATEGORY_RULES = new ConfigStringList(
        "shulkerCategoryRules", ImmutableList.of(),
        "格式：类别|item/tag/name|匹配值，按从上到下的顺序优先匹配。"
    ).translatedName("潜影盒自定义分类规则");
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
    public static final ConfigHotkey ORGANIZE_CURRENT_SHULKER_HOTKEY = new ConfigHotkey(
        "organizeCurrentShulkerHotkey", "", "整理当前或鼠标悬停的潜影盒。"
    ).translatedName("整理当前潜影盒快捷键");
    public static final ConfigHotkey ORGANIZE_ALL_SHULKERS_HOTKEY = new ConfigHotkey(
        "organizeAllShulkersHotkey", "", "按配置模式整理背包内全部潜影盒。"
    ).translatedName("整理全部潜影盒快捷键");

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
        AUTO_CLEAR_TRASH,
        POINT_BUYER_ENABLED,
        AUTO_ENABLE_FLIGHT_DEVICE,
        AUTO_SEND_ZZZ_WHILE_SLEEPING,
        SHULKER_ORGANIZER_ENABLED,
        SHULKER_ORGANIZER_MODE,
        SHULKER_CATEGORY_RULES,
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
        ENABLE_FLIGHT_DEVICE_HOTKEY,
        ORGANIZE_CURRENT_SHULKER_HOTKEY,
        ORGANIZE_ALL_SHULKERS_HOTKEY
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
        ShulkerCategoryRules.reload();
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
        ShulkerCategoryRules.reload();
        save();
    }

    public static int pointsPerPurchase() {
        return Math.max(1, Math.min(100000, POINTS_PER_PURCHASE.getIntegerValue()));
    }

    public static void saveNow() {
        INSTANCE.save();
    }

}
