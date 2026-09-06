package com.example.trashcandetector.client;

import fi.dy.masa.malilib.gui.GuiConfigsBase;

import java.util.List;

/** Standard MaLiLib configuration screen for this mod. */
public final class TrashCanDetectorConfigGui extends GuiConfigsBase {

    public TrashCanDetectorConfigGui() {
        this(null);
    }

    public TrashCanDetectorConfigGui(net.minecraft.client.gui.screen.Screen parent) {
        super(10, 50, TrashCanDetectorClient.MOD_ID, parent, "垃圾桶探测器配置");
        this.setConfigWidth(220);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(TrashCanDetectorConfigs.GUI_OPTIONS);
    }
}
