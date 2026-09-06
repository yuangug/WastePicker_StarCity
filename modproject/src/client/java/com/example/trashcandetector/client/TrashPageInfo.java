package com.example.trashcandetector.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Layout and metadata helpers for the 90-slot trash GUI shown by the server. */
final class TrashPageInfo {

    static final int TOTAL_SLOTS = 90;
    static final int CONTENT_SLOTS = 36;
    static final int UI_START = 36;
    static final int UI_END = 54;
    static final int INVENTORY_START = 54;
    static final int INVENTORY_END = 90;

    private static final int CONTROL_START = 45;
    /** 第二排第 3 格：截图中的放大镜/“查看数据”信息物品。 */
    private static final int INFO_SLOT = 47;
    /** 第二排最右侧：截图中的绿色“下一页”按钮。 */
    private static final int NEXT_SLOT = 53;
    private static final Pattern TOTAL_PAGES = Pattern.compile(
        "(?:当前物品页数|总页数|物品页数|页数|itempages|totalpages|pages)"
            + "[^0-9０-９]{0,16}([0-9０-９]{1,5})",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CURRENT_PAGE = Pattern.compile(
        "(?:当前页|第)\\D{0,8}(\\d+)\\s*页?"
    );

    private TrashPageInfo() {
    }

    static int findNextPageButton(ScreenHandler handler) {
        ItemStack fixedButton = handler.getSlot(NEXT_SLOT).getStack();
        // The GUI layout is fixed by the server: slot 53 is the right-hand
        // next-page control. Its custom model may not expose a translatable
        // name, so do not require name/lore matching for this fixed slot.
        if (!fixedButton.isEmpty()) {
            return NEXT_SLOT;
        }

        // Resource packs may shift the visual button; keep the fallback inside
        // the second control row so the first row can never be clicked.
        for (int slot = CONTROL_START; slot < UI_END; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && isNextPageStack(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isNextPageStack(ItemStack stack) {
        String text = stackText(stack).toLowerCase();
        return text.contains("下一页") || text.contains("下一頁")
            || text.contains("next page") || text.contains("next");
    }

    static PageInfo findPageInfo(ScreenHandler handler, int fallbackCurrent) {
        // The screenshot's control row is fixed: previous=45, info=47,
        // trash=49, next=53. Reading exactly slot 47 prevents page numbers in
        // navigation-button lore from ever becoming the total page count.
        PageInfo pageInfo = parsePageInfo(handler.getSlot(INFO_SLOT).getStack(), fallbackCurrent);
        if (pageInfo != null) {
            return pageInfo;
        }

        // Some versions insert a spacer or shift the information item. Search
        // all GUI controls as a fallback, but only accept an explicit page
        // count label and never inspect the next-page slot.
        for (int slot = UI_START; slot < UI_END; slot++) {
            if (slot == NEXT_SLOT || slot == INFO_SLOT) {
                continue;
            }
            pageInfo = parsePageInfo(handler.getSlot(slot).getStack(), fallbackCurrent);
            if (pageInfo != null) {
                return pageInfo;
            }
        }
        return null;
    }

    static String signature(ScreenHandler handler) {
        StringBuilder result = new StringBuilder(1024);
        // Only the 36 trash-content slots identify a page. The control row is
        // deliberately excluded: clicking next can change slot 53 locally
        // before the server sends the new page, which is not a page change.
        for (int slot = 0; slot < CONTENT_SLOTS; slot++) {
            result.append(slot).append(':').append(stackSignature(handler.getSlot(slot).getStack())).append(';');
        }
        return result.toString();
    }

    static String stackSignature(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        return Registries.ITEM.getId(stack.getItem()) + "x" + stack.getCount()
            + "|" + stack.getName().getString();
    }

    private static PageInfo parsePageInfo(ItemStack stack, int fallbackCurrent) {
        if (stack.isEmpty()) {
            return null;
        }

        String text = normalizeDigits(stackText(stack)).replaceAll("\\s+", "");
        // Only a label explicitly describing item-page count is accepted.
        // The caller supplies the fixed information slot, so navigation-button
        // names and numbers can never become the trash page count.
        Matcher matcher = TOTAL_PAGES.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        int total = parsePositive(matcher.group(1));
        matcher = CURRENT_PAGE.matcher(text);
        int current = matcher.find() ? parsePositive(matcher.group(1)) : fallbackCurrent;
        return current > 0 && total >= current ? new PageInfo(current, total) : null;
    }

    private static int parsePositive(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalizeDigits(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character >= '０' && character <= '９') {
                result.append((char) ('0' + character - '０'));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String stackText(ItemStack stack) {
        StringBuilder text = new StringBuilder(stack.getName().getString());
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                text.append(' ').append(line.getString());
            }
        }
        return text.toString();
    }

    static final class PageInfo {
        final int current;
        final int total;

        PageInfo(int current, int total) {
            this.current = current;
            this.total = total;
        }
    }
}
