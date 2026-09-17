package com.example.trashcandetector.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ShulkerCategoryRules {
    static final String BLOCKS = "方块";
    static final String TOOLS = "工具武器";
    static final String EQUIPMENT = "装备";
    static final String FOOD = "食物药水";
    static final String REDSTONE = "红石";
    static final String MATERIALS = "材料";
    static final String MISC = "杂项";
    static final List<String> BUILTIN_ORDER = List.of(
        BLOCKS, TOOLS, EQUIPMENT, FOOD, REDSTONE, MATERIALS, MISC
    );

    private enum RuleType { ITEM, TAG, NAME }

    private record Rule(String category, RuleType type, String value) {
    }

    private static final Set<String> REDSTONE_IDS = Set.of(
        "minecraft:redstone", "minecraft:redstone_torch", "minecraft:redstone_block",
        "minecraft:repeater", "minecraft:comparator", "minecraft:piston",
        "minecraft:sticky_piston", "minecraft:observer", "minecraft:hopper",
        "minecraft:dispenser", "minecraft:dropper", "minecraft:target",
        "minecraft:lever", "minecraft:daylight_detector", "minecraft:sculk_sensor",
        "minecraft:calibrated_sculk_sensor", "minecraft:tripwire_hook",
        "minecraft:redstone_lamp", "minecraft:hopper_minecart"
    );
    private static final List<Rule> RULES = new ArrayList<>();
    private static final List<String> CUSTOM_ORDER = new ArrayList<>();

    private ShulkerCategoryRules() {
    }

    static void reload() {
        RULES.clear();
        CUSTOM_ORDER.clear();
        Set<String> seenCategories = new HashSet<>();
        for (String line : TrashCanDetectorConfigs.SHULKER_CATEGORY_RULES.getStrings()) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) continue;
            String category = parts[0].trim();
            String typeText = parts[1].trim().toUpperCase(Locale.ROOT);
            String value = parts[2].trim();
            if (category.isEmpty() || value.isEmpty()) continue;
            try {
                RULES.add(new Rule(category, RuleType.valueOf(typeText), value));
                if (!BUILTIN_ORDER.contains(category) && seenCategories.add(category)) {
                    CUSTOM_ORDER.add(category);
                }
            } catch (IllegalArgumentException ignored) {
                TrashCanDetectorClient.LOGGER.warn("忽略无效潜影盒分类规则: {}", line);
            }
        }
    }

    static String category(ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (Rule rule : RULES) {
            if (matches(rule, stack)) return rule.category;
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (!Identifier.DEFAULT_NAMESPACE.equals(itemId.getNamespace())) return null;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (REDSTONE_IDS.contains(id)) return REDSTONE;
        if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)
            || stack.isIn(ItemTags.HOES) || stack.isIn(ItemTags.PICKAXES)
            || stack.isIn(ItemTags.SHOVELS) || stack.isIn(ItemTags.SPEARS)
            || stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)
            || stack.isOf(Items.TRIDENT) || stack.isOf(Items.MACE)
            || stack.isOf(Items.SHEARS) || stack.isOf(Items.FISHING_ROD)
            || stack.isOf(Items.FLINT_AND_STEEL) || stack.isOf(Items.BRUSH)) {
            return TOOLS;
        }
        if (stack.contains(DataComponentTypes.EQUIPPABLE)) return EQUIPMENT;
        if (stack.contains(DataComponentTypes.FOOD)
            || stack.contains(DataComponentTypes.POTION_CONTENTS)) return FOOD;
        String path = itemId.getPath();
        if (isMaterialPath(path)) return MATERIALS;
        if (stack.getItem() instanceof BlockItem) return BLOCKS;
        return MISC;
    }

    static int categoryOrder(ItemStack stack) {
        String category = category(stack);
        int builtIn = BUILTIN_ORDER.indexOf(category);
        if (builtIn >= 0) return builtIn;
        int custom = CUSTOM_ORDER.indexOf(category);
        return custom >= 0 ? BUILTIN_ORDER.size() + custom : Integer.MAX_VALUE;
    }

    static Comparator<ItemStack> stackComparator() {
        return Comparator.comparingInt(ShulkerCategoryRules::categoryOrder)
            .thenComparing(stack -> Registries.ITEM.getId(stack.getItem()).toString())
            .thenComparing(stack -> stack.getName().getString())
            .thenComparing(stack -> stack.getComponents().toString())
            .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());
    }

    static List<String> orderedCategories(Set<String> categories) {
        List<String> result = new ArrayList<>();
        for (String builtin : BUILTIN_ORDER) if (categories.contains(builtin)) result.add(builtin);
        for (String custom : CUSTOM_ORDER) if (categories.contains(custom)) result.add(custom);
        categories.stream().filter(value -> !result.contains(value)).sorted().forEach(result::add);
        return result;
    }

    private static boolean matches(Rule rule, ItemStack stack) {
        return switch (rule.type) {
            case ITEM -> Registries.ITEM.getId(stack.getItem()).toString().equalsIgnoreCase(rule.value);
            case NAME -> stack.getName().getString().toLowerCase(Locale.ROOT)
                .contains(rule.value.toLowerCase(Locale.ROOT));
            case TAG -> {
                String value = rule.value.startsWith("#") ? rule.value.substring(1) : rule.value;
                Identifier id = Identifier.tryParse(value);
                yield id != null && stack.isIn(TagKey.of(RegistryKeys.ITEM, id));
            }
        };
    }

    private static boolean isMaterialPath(String path) {
        return path.endsWith("_ingot") || path.endsWith("_nugget")
            || path.endsWith("_gem") || path.endsWith("_dust")
            || path.startsWith("raw_") || path.endsWith("_scrap")
            || path.endsWith("_shard") || path.endsWith("_crystal")
            || path.endsWith("_hide") || path.endsWith("_leather")
            || path.endsWith("_string") || path.endsWith("_feather")
            || path.endsWith("_bone") || path.endsWith("_rod")
            || path.endsWith("_clay_ball") || path.endsWith("_brick")
            || path.endsWith("_dye");
    }
}
