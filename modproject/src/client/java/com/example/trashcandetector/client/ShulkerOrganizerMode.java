package com.example.trashcandetector.client;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;

import java.util.Locale;

enum ShulkerOrganizerMode implements IConfigOptionListEntry {
    SINGLE_BOX_SORT("single_box_sort", "逐盒排序"),
    CROSS_BOX_MERGE("cross_box_merge", "跨盒合并"),
    CATEGORY_DEPOSIT("category_deposit", "分类存入"),
    CATEGORY_REBUILD("category_rebuild", "分类重建"),
    MINIMUM_BOX_COMPACTION("minimum_box_compaction", "最少盒数压缩");

    private final String value;
    private final String displayName;

    ShulkerOrganizerMode(String value, String displayName) {
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
        ShulkerOrganizerMode[] modes = values();
        int index = ordinal() + (forward ? 1 : -1);
        if (index < 0) index = modes.length - 1;
        if (index >= modes.length) index = 0;
        return modes[index];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (ShulkerOrganizerMode mode : values()) {
            if (mode.value.equals(normalized)) return mode;
        }
        return SINGLE_BOX_SORT;
    }
}
