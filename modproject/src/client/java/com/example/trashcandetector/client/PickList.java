package com.example.trashcandetector.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 待搜索物品 ID 列表（//pick add/del/list 管理）。
 * 主配置由 MaLiLib 持久化；首次使用时兼容导入旧 picklist.json。
 */
public final class PickList {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrashCanDetectorClient.MOD_ID);

    private static final LinkedHashSet<String> ENTRIES = new LinkedHashSet<>();
    private static boolean loaded;

    private PickList() {
    }

    public static synchronized List<String> entries() {
        ensureLoaded();
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return ENTRIES.isEmpty();
    }

    public static synchronized int size() {
        ensureLoaded();
        return ENTRIES.size();
    }

    /**
     * 添加物品 ID（大小写不敏感去重）
     *
     * @return true 表示新增成功
     */
    public static synchronized boolean add(String rawId) {
        ensureLoaded();
        String id = normalize(rawId);
        if (id.isEmpty()) {
            return false;
        }
        for (String entry : ENTRIES) {
            if (entry.equalsIgnoreCase(id)) {
                return false;
            }
        }
        ENTRIES.add(id);
        saveToConfig();
        return true;
    }

    /**
     * 删除物品 ID（大小写不敏感）
     *
     * @return true 表示删除成功
     */
    public static synchronized boolean remove(String rawId) {
        ensureLoaded();
        String id = normalize(rawId);
        if (id.isEmpty()) {
            return false;
        }
        boolean removed = ENTRIES.removeIf(entry -> entry.equalsIgnoreCase(id));
        if (removed) {
            saveToConfig();
        }
        return removed;
    }

    /**
     * 判断物品堆是否命中搜索列表。
     * 支持完整 ID（minecraft:ender_pearl）或短 ID（ender_pearl）两种匹配方式。
     */
    public static synchronized boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ensureLoaded();
        if (ENTRIES.isEmpty()) {
            return false;
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        String path = id.substring(id.indexOf(':') + 1);
        for (String entry : ENTRIES) {
            if (entry.equalsIgnoreCase(id) || entry.equalsIgnoreCase(path)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (String configured : TrashCanDetectorConfigs.PICK_ITEM_IDS.getStrings()) {
            String entry = normalize(configured);
            if (!entry.isEmpty()) {
                ENTRIES.add(entry);
            }
        }

        // Import the old standalone list when the new MaLiLib list has no values.
        if (ENTRIES.isEmpty()) {
            importLegacyList();
        }
        LOGGER.info("已加载搜索列表，共 {} 项", ENTRIES.size());
    }

    /** Refresh the in-memory matcher after the MaLiLib list is edited. */
    public static synchronized void reloadFromConfig() {
        ensureLoaded();
        ENTRIES.clear();
        for (String configured : TrashCanDetectorConfigs.PICK_ITEM_IDS.getStrings()) {
            String entry = normalize(configured);
            if (!entry.isEmpty()) {
                ENTRIES.add(entry);
            }
        }
    }

    private static void importLegacyList() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path path = client.runDirectory.toPath()
            .resolve("trashcan-detector")
            .resolve("picklist.json");
        try {
            if (!Files.exists(path)) {
                return;
            }
            JsonObject obj = JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            if (obj.has("items")) {
                for (JsonElement element : obj.getAsJsonArray("items")) {
                    String entry = normalize(element.getAsString());
                    if (!entry.isEmpty()) {
                        ENTRIES.add(entry);
                    }
                }
            }
            if (!ENTRIES.isEmpty()) {
                saveToConfig();
            }
        } catch (Exception e) {
            LOGGER.warn("导入旧搜索列表失败: {}", e.toString());
        }
    }

    private static void saveToConfig() {
        TrashCanDetectorConfigs.PICK_ITEM_IDS.setStrings(new ArrayList<>(ENTRIES));
        TrashCanDetectorConfigs.saveNow();
    }
}
