package com.example.trashcandetector.client;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Combines local clear lists with optional Tweakeroo and Printer restrictions. */
final class TrashClearFilter {

    private static Set<String> localBlacklist = Collections.emptySet();
    private static Set<String> localWhitelist = Collections.emptySet();

    private TrashClearFilter() {
    }

    static synchronized void reload() {
        localBlacklist = normalizeAll(TrashCanDetectorConfigs.CLEAR_BLACKLIST.getStrings());
        localWhitelist = normalizeAll(TrashCanDetectorConfigs.CLEAR_WHITELIST.getStrings());
        ExternalMiningLists.reload();
    }

    static synchronized boolean shouldDiscard(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        String id = Registries.ITEM.getId(stack.getItem()).toString();
        String path = id.substring(id.indexOf(':') + 1);
        TrashClearMode mode = TrashCanDetectorConfigs.CLEAR_MODE.getOptionListValue() instanceof TrashClearMode clearMode
            ? clearMode : TrashClearMode.ALL;

        if (mode == TrashClearMode.BLACKLIST && contains(localBlacklist, id, path)) {
            return false;
        }
        if (mode == TrashClearMode.WHITELIST && !contains(localWhitelist, id, path)) {
            return false;
        }

        ExternalMiningLists.Snapshot external = ExternalMiningLists.getSnapshot();
        if (!allowedBySource(external.tweakeroo, id, path)
            || !allowedBySource(external.printer, id, path)) {
            return false;
        }
        return true;
    }

    static synchronized String status() {
        TrashClearMode mode = TrashCanDetectorConfigs.CLEAR_MODE.getOptionListValue() instanceof TrashClearMode clearMode
            ? clearMode : TrashClearMode.ALL;
        ExternalMiningLists.Snapshot external = ExternalMiningLists.getSnapshot();
        return mode.getDisplayName()
            + "，本地黑名单 " + localBlacklist.size() + " 项，本地白名单 " + localWhitelist.size() + " 项"
            + "，Tweakeroo " + sourceStatus(external.tweakeroo)
            + "，Printer " + sourceStatus(external.printer);
    }

    static synchronized String modeName() {
        return TrashCanDetectorConfigs.CLEAR_MODE.getOptionListValue().getDisplayName();
    }

    private static boolean allowedBySource(ExternalMiningLists.Source source, String id, String path) {
        if (!source.isActive()) {
            return true;
        }
        if (source.type == ExternalMiningLists.ListType.BLACKLIST) {
            return !contains(source.blacklist, id, path);
        }
        return contains(source.whitelist, id, path);
    }

    private static String sourceStatus(ExternalMiningLists.Source source) {
        if (!source.isActive()) {
            return "未启用";
        }
        if (source.type == ExternalMiningLists.ListType.BLACKLIST) {
            return "黑名单 " + source.blacklist.size() + " 项";
        }
        return "白名单 " + source.whitelist.size() + " 项";
    }

    private static boolean contains(Set<String> entries, String id, String path) {
        return entries.contains(id) || entries.contains(path);
    }

    private static Set<String> normalizeAll(Iterable<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
