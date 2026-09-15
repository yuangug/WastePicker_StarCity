package com.example.trashcandetector.client;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

import java.util.List;

/** Adapter between the mod's services and MaLiLib configuration/keybind APIs. */
public final class TrashCanDetectorMalilib implements IInitializationHandler {

    private static final KeybindProvider KEYBINDS = new KeybindProvider();

    @Override
    public void registerModHandlers() {
        ConfigManager.getInstance().registerConfigHandler(
            TrashCanDetectorClient.MOD_ID, TrashCanDetectorConfigs.INSTANCE
        );
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(
            TrashCanDetectorClient.MOD_ID,
            "TrashCan Detector",
            TrashCanDetectorConfigGui::new
        ));

        TrashCanDetectorConfigs.CLEAR_TRASH_HOTKEY.getKeybind().setCallback(new ClearCallback());
        TrashCanDetectorConfigs.START_PICK_HOTKEY.getKeybind().setCallback(new StartPickCallback());
        TrashCanDetectorConfigs.OPEN_CONFIG_HOTKEY.getKeybind().setCallback(new ConfigCallback());
        TrashCanDetectorConfigs.ENABLE_FLIGHT_DEVICE_HOTKEY.getKeybind()
            .setCallback(new EnableFlightDeviceCallback());
        InputEventHandler.getKeybindManager().registerKeybindProvider(KEYBINDS);
    }

    private static final class KeybindProvider implements IKeybindProvider {
        @Override
        public void addKeysToMap(IKeybindManager manager) {
            manager.addKeybindToMap(TrashCanDetectorConfigs.CLEAR_TRASH_HOTKEY.getKeybind());
            manager.addKeybindToMap(TrashCanDetectorConfigs.START_PICK_HOTKEY.getKeybind());
            manager.addKeybindToMap(TrashCanDetectorConfigs.OPEN_CONFIG_HOTKEY.getKeybind());
            manager.addKeybindToMap(TrashCanDetectorConfigs.ENABLE_FLIGHT_DEVICE_HOTKEY.getKeybind());
        }

        @Override
        public void addHotkeys(IKeybindManager manager) {
            List<? extends IHotkey> hotkeys = ImmutableList.of(
                TrashCanDetectorConfigs.CLEAR_TRASH_HOTKEY,
                TrashCanDetectorConfigs.START_PICK_HOTKEY,
                TrashCanDetectorConfigs.OPEN_CONFIG_HOTKEY,
                TrashCanDetectorConfigs.ENABLE_FLIGHT_DEVICE_HOTKEY
            );
            manager.addHotkeysForCategory(
                "TrashCan Detector", "trashcandetector.hotkeys", hotkeys
            );
        }
    }

    private static final class ClearCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            TrashCanDetectorClient.requestTrashClear();
            return true;
        }
    }

    private static final class ConfigCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            GuiBase.openGui(new TrashCanDetectorConfigGui());
            return true;
        }
    }

    private static final class StartPickCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            TrashCanDetectorClient.requestPickStart();
            return true;
        }
    }

    private static final class EnableFlightDeviceCallback implements IHotkeyCallback {
        @Override
        public boolean onKeyAction(KeyAction action, IKeybind key) {
            InfiniteFlightDeviceManager.requestEnsure();
            return true;
        }
    }
}
