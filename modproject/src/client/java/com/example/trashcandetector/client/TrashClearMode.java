package com.example.trashcandetector.client;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

import java.util.Locale;

enum TrashClearMode implements IConfigOptionListEntry {
    ALL("all", "全部物品"),
    BLACKLIST("blacklist", "排除黑名单"),
    WHITELIST("whitelist", "只丢白名单");

    private final String value;
    private final String displayName;

    TrashClearMode(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @Override
    public String getStringValue() {
        return value;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        TrashClearMode[] values = values();
        int index = ordinal() + (forward ? 1 : -1);
        if (index < 0) {
            index = values.length - 1;
        } else if (index >= values.length) {
            index = 0;
        }
        return values[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (TrashClearMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        return ALL;
    }
}
