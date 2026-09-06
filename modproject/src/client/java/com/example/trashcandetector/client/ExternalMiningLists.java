package com.example.trashcandetector.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Reads optional mining restrictions without linking against optional mods. */
final class ExternalMiningLists {

    private static final Logger LOGGER = TrashCanDetectorClient.LOGGER;
    private static final String TWEAKEROO_FILE = "tweakeroo.json";
    private static final String PRINTER_FILE = "litematica-printer.json";

    private static Snapshot snapshot = Snapshot.EMPTY;

    private ExternalMiningLists() {
    }

    static synchronized void reload() {
        MinecraftClient client = MinecraftClient.getInstance();
        snapshot = new Snapshot(
            TrashCanDetectorConfigs.SYNC_TWEAKEROO_MINING_LIST.getBooleanValue()
                ? readTweakeroo(client) : Source.EMPTY,
            TrashCanDetectorConfigs.SYNC_PRINTER_MINING_LIST.getBooleanValue()
                ? readPrinter(client) : Source.EMPTY
        );
    }

    static synchronized Snapshot getSnapshot() {
        return snapshot;
    }

    private static Source readTweakeroo(MinecraftClient client) {
        Path path = findConfig(client, TWEAKEROO_FILE);
        JsonObject lists = readObject(path, "Lists");
        if (lists == null) {
            return Source.EMPTY;
        }
        return readSource(
            lists,
            "blockTypeBreakRestrictionListType",
            "blockTypeBreakRestrictionBlackList",
            "blockTypeBreakRestrictionWhiteList"
        );
    }

    private static Source readPrinter(MinecraftClient client) {
        Path path = findConfig(client, PRINTER_FILE);
        JsonObject root = readObject(path, "litematica-printer");
        if (root == null) {
            return Source.EMPTY;
        }
        return readSource(root, "excavateLimit", "excavateBlacklist", "excavateWhitelist");
    }

    private static Source readSource(
        JsonObject object,
        String typeKey,
        String blacklistKey,
        String whitelistKey
    ) {
        String type = getString(object, typeKey).toLowerCase(Locale.ROOT);
        if (type.equals("blacklist")) {
            return new Source(readIds(object.get(blacklistKey)), Collections.emptySet(), ListType.BLACKLIST);
        }
        if (type.equals("whitelist")) {
            return new Source(Collections.emptySet(), readIds(object.get(whitelistKey)), ListType.WHITELIST);
        }
        return Source.EMPTY;
    }

    private static JsonObject readObject(Path path, String childKey) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject root = element.getAsJsonObject();
            JsonElement child = root.get(childKey);
            return child != null && child.isJsonObject() ? child.getAsJsonObject() : root;
        } catch (Exception e) {
            LOGGER.warn("读取外部挖掘名单失败 {}: {}", path, e.toString());
            return null;
        }
    }

    private static Set<String> readIds(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                String id = TrashClearFilter.normalize(entry.getAsString());
                if (!id.isEmpty()) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static Path findConfig(MinecraftClient client, String fileName) {
        if (client == null) {
            return null;
        }
        Path current = client.runDirectory.toPath().toAbsolutePath().normalize();
        String version = client.getGameVersion();
        for (Path directory = current; directory != null; directory = directory.getParent()) {
            Path direct = directory.resolve("config").resolve(fileName);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path versionConfig = directory.resolve("versions").resolve(version).resolve("config").resolve(fileName);
            if (Files.isRegularFile(versionConfig)) {
                return versionConfig;
            }
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path appDataConfig = Path.of(appData, ".minecraft", "versions", version, "config", fileName);
            if (Files.isRegularFile(appDataConfig)) {
                return appDataConfig;
            }
        }
        return null;
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(Source.EMPTY, Source.EMPTY);

        final Source tweakeroo;
        final Source printer;

        Snapshot(Source tweakeroo, Source printer) {
            this.tweakeroo = tweakeroo;
            this.printer = printer;
        }
    }

    static final class Source {
        static final Source EMPTY = new Source(Collections.emptySet(), Collections.emptySet(), ListType.NONE);

        final Set<String> blacklist;
        final Set<String> whitelist;
        final ListType type;

        Source(Set<String> blacklist, Set<String> whitelist, ListType type) {
            this.blacklist = blacklist;
            this.whitelist = whitelist;
            this.type = type;
        }

        boolean isActive() {
            return type != ListType.NONE;
        }
    }

    enum ListType {
        NONE,
        BLACKLIST,
        WHITELIST
    }
}
