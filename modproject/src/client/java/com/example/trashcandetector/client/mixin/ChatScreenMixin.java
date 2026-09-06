package com.example.trashcandetector.client.mixin;

import com.example.trashcandetector.client.PickCommandHandler;
import com.example.trashcandetector.client.TrashCanDetectorClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 ChatScreen，拦截本模组的本地指令输入：
 * - Enter 键：本地执行指令（start/add/list/del），消息不发送到服务器
 * - Tab 键：不拦截，走原版 chatInputSuggestor 补全（命令树由
 *   ClientPlayNetworkHandlerMixin 注册）
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    protected TextFieldWidget chatField;

    /**
     * 拦截 Enter：本地执行 //pick 指令（不发送到服务器）
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void trashcandetector$onPickCommand(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (!input.isEnter()) {
            return;
        }
        String text = this.chatField.getText();
        if (!PickCommandHandler.isPickCommand(text) && !PickCommandHandler.isTrashCommand(text)) {
            return;
        }

        // 沿用原版规整（去首尾空格、压缩连续空格）
        ChatScreen self = (ChatScreen) (Object) this;
        String normalized = self.normalize(text);
        if (!PickCommandHandler.isPickCommand(normalized) && !PickCommandHandler.isTrashCommand(normalized)) {
            return;
        }

        try {
            PickCommandHandler.execute(normalized);
        } catch (Exception e) {
            TrashCanDetectorClient.LOGGER.error("执行 //pick 指令失败", e);
        }

        // 模拟原版 Enter 流程：加入聊天历史（上方向键可召回）并关闭界面
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addToMessageHistory(normalized);
        }
        self.close();
        cir.setReturnValue(true);
    }
}
