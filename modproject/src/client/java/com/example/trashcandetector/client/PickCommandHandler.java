package com.example.trashcandetector.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.network.ClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;

import java.util.List;
import java.util.Locale;

/**
 * //pick 指令处理器：
 * 1. execute()   —— 由 ChatScreenMixin 拦截回车后在客户端本地执行（不发送到服务器）
 * 2. registerSuggestions() —— 由 ClientPlayNetworkHandlerMixin 在服务器命令树同步后调用，
 *    把 /pick 注册进客户端命令分发器，从而复用原版聊天栏的 Tab 补全 UI
 */
public final class PickCommandHandler {

    public static final String COMMAND = "//pick";
    public static final String TRASH_COMMAND = "//trash";

    /** //pick add 物品 ID 补全：全物品注册表（原版风格，支持短名匹配） */
    private static final SuggestionProvider<ClientCommandSource> ADD_SUGGESTIONS =
        (context, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder);

    /** //pick del 物品 ID 补全：当前搜索列表 */
    private static final SuggestionProvider<ClientCommandSource> DEL_SUGGESTIONS =
        (context, builder) -> CommandSource.suggestMatching(PickList.entries(), builder);

    private PickCommandHandler() {
    }

    /**
     * 判断聊天文本是否是本模组的 //pick 指令（大小写不敏感，注意与 //pickup 之类区分）
     */
    public static boolean isPickCommand(String text) {
        if (text == null) {
            return false;
        }
        if (!text.regionMatches(true, 0, COMMAND, 0, COMMAND.length())) {
            return false;
        }
        return text.length() == COMMAND.length() || text.charAt(COMMAND.length()) == ' ';
    }

    public static boolean isTrashCommand(String text) {
        return isCommand(text, TRASH_COMMAND);
    }

    private static boolean isCommand(String text, String command) {
        if (text == null || !text.regionMatches(true, 0, command, 0, command.length())) return false;
        return text.length() == command.length() || text.charAt(command.length()) == ' ';
    }

    /**
     * 把 //pick 命令树注册到客户端命令分发器（仅供 Tab 补全，原版客户端不会在本地执行分发器中的命令）
     */
    public static void registerSuggestions(CommandDispatcher<ClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<ClientCommandSource> root =
            LiteralArgumentBuilder.<ClientCommandSource>literal("/pick");
        root.executes(ctx -> {
            execute(COMMAND);
            return 1;
        });
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("start")
            .executes(ctx -> {
                execute(COMMAND + " start");
                return 1;
            }));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("auto")
            .executes(ctx -> {
                execute(COMMAND + " auto");
                return 1;
            }));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("list")
            .executes(ctx -> {
                execute(COMMAND + " list");
                return 1;
            }));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("add")
            .then(RequiredArgumentBuilder.<ClientCommandSource, String>argument("item", StringArgumentType.word())
                .suggests(ADD_SUGGESTIONS)
                .executes(ctx -> 1)));
        root.then(LiteralArgumentBuilder.<ClientCommandSource>literal("del")
            .then(RequiredArgumentBuilder.<ClientCommandSource, String>argument("item", StringArgumentType.word())
                .suggests(DEL_SUGGESTIONS)
                .executes(ctx -> 1)));
        dispatcher.register(root);

        LiteralArgumentBuilder<ClientCommandSource> trashRoot =
            LiteralArgumentBuilder.<ClientCommandSource>literal("/trash");
        trashRoot.then(LiteralArgumentBuilder.<ClientCommandSource>literal("clear")
            .executes(ctx -> {
                execute(TRASH_COMMAND + " clear");
                return 1;
            }));
        trashRoot.then(LiteralArgumentBuilder.<ClientCommandSource>literal("status")
            .executes(ctx -> {
                execute(TRASH_COMMAND + " status");
                return 1;
            }));
        dispatcher.register(trashRoot);
    }

    /**
     * 执行 //pick 指令（chatText 已经过 ChatScreen 的空白规整）
     */
    public static void execute(String chatText) {
        boolean trash = isTrashCommand(chatText);
        String command = trash ? TRASH_COMMAND : COMMAND;
        String rest = chatText.substring(command.length()).trim();
        String[] args = rest.isEmpty() ? new String[0] : rest.split(" ");

        if (args.length == 0) {
            usage();
            return;
        }

        if (trash) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "clear" -> {
                    if (args.length != 1) usage(); else TrashCanDetectorClient.requestTrashClear();
                }
                case "status" -> {
                    if (args.length != 1) usage(); else cmdStatus();
                }
                default -> usage();
            }
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length != 1) {
                    usage();
                } else {
                    cmdStart();
                }
            }
            case "auto" -> {
                if (args.length != 1) {
                    usage();
                } else {
                    cmdAuto();
                }
            }
            case "list" -> {
                if (args.length != 1) {
                    usage();
                } else {
                    cmdList();
                }
            }
            case "add" -> {
                if (args.length != 2) {
                    usage();
                } else {
                    cmdAdd(args[1]);
                }
            }
            case "del" -> {
                if (args.length != 2) {
                    usage();
                } else {
                    cmdDel(args[1]);
                }
            }
            default -> usage();
        }
    }

    private static void usage() {
        feedback("用法：");
        feedback("//pick start —— 打开垃圾桶并自动翻页搜索列表中的物品");
        feedback("//pick auto —— 开/关自动模式（检测到垃圾桶刷新后导出并自动搜索）");
        feedback("//pick add <物品ID> —— 添加要搜索的物品（支持 Tab 补全）");
        feedback("//pick list —— 查看当前搜索列表");
        feedback("//pick del <物品ID> —— 删除列表中的物品（支持 Tab 补全）");
        feedback("//trash clear —— 打开垃圾桶并自动丢弃全部物品");
        feedback("//trash status —— 查看自动化开关和运行状态");
    }

    private static void cmdStart() {
        TrashCanDetectorClient.requestPickStart();
    }

    private static void cmdAuto() {
        if (TrashCanDetectorClient.toggleAutoPick()) {
            feedback("自动拾取已开启：每次检测到垃圾桶刷新，导出后会自动翻页搜索（//pick start 不受影响）");
        } else {
            feedback("自动拾取已关闭：检测到垃圾桶刷新时仅自动打开并导出");
        }
    }

    private static void cmdAdd(String id) {
        String normalized = PickList.normalize(id);
        if (PickList.add(id)) {
            feedback("已添加 " + normalized + "，当前列表共 " + PickList.size() + " 项（//pick list 查看）");
        } else {
            feedback(normalized + " 已在列表中，无需重复添加");
        }
    }

    private static void cmdDel(String id) {
        String normalized = PickList.normalize(id);
        if (PickList.remove(id)) {
            feedback("已删除 " + normalized + "，当前列表共 " + PickList.size() + " 项");
        } else {
            feedback("列表中未找到 " + normalized);
        }
    }

    private static void cmdList() {
        List<String> entries = PickList.entries();
        if (entries.isEmpty()) {
            feedback("搜索列表为空，请先用 //pick add <物品ID> 添加物品");
            return;
        }
        feedback("当前搜索列表（共 " + entries.size() + " 项）：");
        // 每 10 项一行，避免刷屏
        for (int i = 0; i < entries.size(); i += 10) {
            int end = Math.min(i + 10, entries.size());
            feedback("  " + String.join("、", entries.subList(i, end)));
        }
    }

    private static void cmdStatus() {
        feedback("自动清空：" + (TrashCanDetectorConfigs.AUTO_CLEAR_TRASH.getBooleanValue() ? "开启" : "关闭"));
        feedback("清空规则：" + TrashClearFilter.modeName());
        feedback("清空名单：" + TrashClearFilter.status());
        feedback("自动购买积分：" + (PointBuyer.isEnabled() ? "开启" : "关闭")
            + "，每次 " + TrashCanDetectorConfigs.pointsPerPurchase() + " 个");
        feedback("运行状态：" + (TrashCleaner.isActive() ? "正在清空垃圾桶"
            : TrashPicker.isActive() ? "正在搜索垃圾桶" : "空闲"));
    }

    private static void feedback(String message) {
        TrashCanDetectorClient.feedback(message);
    }
}
