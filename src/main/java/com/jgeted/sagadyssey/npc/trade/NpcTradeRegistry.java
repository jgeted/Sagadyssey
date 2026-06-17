package com.jgeted.sagadyssey.npc.trade;

import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * 每个职业每级的交易池 + 商人跨职业池 + 抽取逻辑。
 */
public class NpcTradeRegistry {

    /** 每个职业每级抽几项（下标 = level-1） */
    public static final Map<NpcProfession, int[]> PICK_COUNTS = Map.ofEntries(
            Map.entry(NpcProfession.BLACKSMITH, new int[]{2, 2, 2, 2}),
            Map.entry(NpcProfession.FARMER,    new int[]{2, 2, 2, 1}),
            Map.entry(NpcProfession.WORKER,    new int[]{3, 2, 2, 1}),
            Map.entry(NpcProfession.WARRIOR,   new int[]{2, 2, 2, 1}),
            Map.entry(NpcProfession.ARCHER,    new int[]{2, 2, 2, 1}),
            Map.entry(NpcProfession.HEAVY,     new int[]{1, 2, 2, 1}),
            Map.entry(NpcProfession.MEDIC,     new int[]{2, 2, 2, 1}),
            Map.entry(NpcProfession.BARD,      new int[]{1, 2, 1, 1}),
            Map.entry(NpcProfession.TRADER,    new int[]{3, 3, 3, 2}),
            Map.entry(NpcProfession.NONE,      new int[]{0, 0, 0, 0})
    );

    /** 每个职业每级的池（List 的下标 = level-1） */
    private static final Map<NpcProfession, List<List<NpcTradeOffer>>> POOLS = new HashMap<>();

    static {
        // ==================== 铁匠 (6 Lv1 + 6 Lv2) ====================
        List<List<NpcTradeOffer>> bs = new ArrayList<>();
        bs.add(List.of(  // Lv1 — 材料 + 功能性方块
                offer(e(), 2, 3, iron(), 1, 1, 1),
                offer(e(), 1, 1, lantern(), 1, 1, 1),
                offer(e(), 6, 8, coal(), 1, 1, 1),
                offer(e(), 1, 1, furnace(), 1, 1, 1),
                buyOffer(iron(), 4, 6, e(), 1, 1),
                buyOffer(coal(), 12, 14, e(), 1, 1)
        ));
        bs.add(List.of(  // Lv2 — 铁工具 + 盾牌
                offer(e(), 2, 2, ironPick(), 1, 1, 2),
                offer(e(), 2, 2, ironAxe(), 1, 1, 2),
                offer(e(), 2, 2, ironShovel(), 1, 1, 2),
                offer(e(), 2, 2, ironHoe(), 1, 1, 2),
                offer(e(), 2, 2, ironSword(), 1, 1, 2),
                offer(e(), 2, 2, shield(), 1, 1, 2)
        ));
        bs.add(List.of());  // Lv3 待定
        bs.add(List.of());  // Lv4 待定
        POOLS.put(NpcProfession.BLACKSMITH, bs);

        // ==================== 农民 (10 Lv1) ====================
        List<List<NpcTradeOffer>> fm = new ArrayList<>();
        fm.add(List.of(  // Lv1 — 作物批量买卖
                offer(e(), 11, 15, wheat(), 1, 1, 1),
                offer(e(), 6, 7, wheatSeeds(), 1, 1, 1),
                offer(e(), 5, 6, potato(), 1, 1, 1),
                offer(e(), 7, 8, carrot(), 1, 1, 1),
                offer(e(), 6, 7, beetSeeds(), 1, 1, 1),
                offer(e(), 7, 8, beetroot(), 1, 1, 1),
                buyOffer(wheat(), 18, 22, e(), 1, 1),
                buyOffer(carrot(), 11, 12, e(), 1, 1),
                buyOffer(beetroot(), 13, 15, e(), 1, 1),
                buyOffer(potato(), 12, 14, e(), 1, 1)
        ));
        fm.add(List.of());  // Lv2
        fm.add(List.of());  // Lv3
        fm.add(List.of());  // Lv4
        POOLS.put(NpcProfession.FARMER, fm);

        // ==================== 工人 (13 Lv1) ====================
        List<List<NpcTradeOffer>> wk = new ArrayList<>();
        wk.add(List.of(  // Lv1 — 建材 + 基础工具
                offer(e(), 1, 1, cobblestone(), 7, 9, 1),
                offer(e(), 1, 1, stone(), 5, 7, 1),
                offer(e(), 1, 1, dirt(), 9, 12, 1),
                offer(e(), 1, 1, sand(), 6, 8, 1),
                offer(e(), 1, 1, gravel(), 5, 7, 1),
                offer(e(), 1, 1, torch(), 5, 7, 1),
                offer(e(), 1, 1, ladder(), 4, 6, 1),
                offer(e(), 1, 1, stonePick(), 1, 2, 1),
                offer(e(), 1, 1, stoneAxe(), 1, 2, 1),
                offer(e(), 1, 1, stoneShovel(), 1, 2, 1),
                buyOffer(coal(), 14, 18, e(), 1, 1),
                buyOffer(cobblestone(), 18, 22, e(), 1, 1),
                buyOffer(iron(), 3, 4, e(), 1, 1)
        ));
        wk.add(List.of());  // Lv2
        wk.add(List.of());  // Lv3
        wk.add(List.of());  // Lv4
        POOLS.put(NpcProfession.WORKER, wk);

        // ==================== 战士 (9 Lv1) ====================
        List<List<NpcTradeOffer>> wr = new ArrayList<>();
        wr.add(List.of(  // Lv1 — 武器 + 基础护甲
                offer(e(), 1, 1, stoneSword(), 1, 1, 1),
                offer(e(), 2, 2, ironSword(), 1, 1, 1),
                offer(e(), 2, 2, shield(), 1, 1, 1),
                offer(e(), 2, 2, bow(), 1, 1, 1),
                offer(e(), 1, 1, arrow(), 12, 16, 1),
                offer(e(), 2, 2, leatherChest(), 1, 1, 1),
                offer(e(), 2, 2, leatherLegs(), 1, 1, 1),
                buyOffer(rottenFlesh(), 14, 18, e(), 1, 1),
                buyOffer(bone(), 10, 14, e(), 1, 1)
        ));
        wr.add(List.of());  // Lv2
        wr.add(List.of());  // Lv3
        wr.add(List.of());  // Lv4
        POOLS.put(NpcProfession.WARRIOR, wr);

        // ==================== 弓手 (5 Lv1) ====================
        List<List<NpcTradeOffer>> ar = new ArrayList<>();
        ar.add(List.of(  // Lv1 — 远程武器 + 弹药
                offer(e(), 2, 2, bow(), 1, 1, 1),
                offer(e(), 1, 1, arrow(), 14, 18, 1),
                offer(e(), 3, 3, crossbow(), 1, 1, 1),
                buyOffer(string(), 4, 6, e(), 1, 1),
                buyOffer(feather(), 8, 12, e(), 1, 1)
        ));
        ar.add(List.of());  // Lv2
        ar.add(List.of());  // Lv3
        ar.add(List.of());  // Lv4
        POOLS.put(NpcProfession.ARCHER, ar);

        // ==================== 重甲兵 (8 Lv1) ====================
        List<List<NpcTradeOffer>> hv = new ArrayList<>();
        hv.add(List.of(  // Lv1 — 重甲套
                offer(e(), 4, 5, ironChest(), 1, 1, 1),
                offer(e(), 3, 4, ironLegs(), 1, 1, 1),
                offer(e(), 2, 3, ironBoots(), 1, 1, 1),
                offer(e(), 2, 3, ironHelmet(), 1, 1, 1),
                offer(e(), 2, 2, shield(), 1, 1, 1),
                offer(e(), 2, 2, ironSword(), 1, 1, 1),
                buyOffer(iron(), 5, 7, e(), 1, 1),
                buyOffer(ironBlock(), 1, 1, e(), 2, 3, 1)
        ));
        hv.add(List.of());  // Lv2
        hv.add(List.of());  // Lv3
        hv.add(List.of());  // Lv4
        POOLS.put(NpcProfession.HEAVY, hv);

        // ==================== 医师 (3 Lv1) ====================
        List<List<NpcTradeOffer>> md = new ArrayList<>();
        md.add(List.of(  // Lv1 — 医疗用品
                offer(e(), 2, 2, goldenApple(), 1, 1, 1),
                offer(e(), 3, 3, potion(), 1, 1, 1),
                offer(e(), 1, 1, paper(), 2, 4, 1)
        ));
        md.add(List.of());  // Lv2
        md.add(List.of());  // Lv3
        md.add(List.of());  // Lv4
        POOLS.put(NpcProfession.MEDIC, md);

        // ==================== 诗人 (4 Lv1) ====================
        List<List<NpcTradeOffer>> bd = new ArrayList<>();
        bd.add(List.of(  // Lv1 — 书卷类
                offer(e(), 1, 1, book(), 1, 1, 1),
                offer(e(), 3, 3, writableBook(), 1, 1, 1),
                offer(e(), 2, 2, nameTag(), 1, 1, 1),
                buyOffer(goldIngot(), 1, 1, e(), 3, 4, 1)
        ));
        bd.add(List.of());  // Lv2
        bd.add(List.of());  // Lv3
        bd.add(List.of());  // Lv4
        POOLS.put(NpcProfession.BARD, bd);

        // 无职业——空池
        POOLS.put(NpcProfession.NONE,
                List.of(List.of(), List.of(), List.of(), List.of()));
        // 商人不走 POOLS（走跨职业池）
    }

    // ==================== 公开 API ====================

    /** 从指定职业指定等级的池中随机抽 count 项（不重复），并 resolve */
    public static List<NpcTradeOffer> pickRandom(NpcProfession prof, int level, long seed, int count) {
        List<NpcTradeOffer> pool = getPool(prof, level);
        if (pool.isEmpty() || count <= 0) return List.of();
        List<NpcTradeOffer> copy = new ArrayList<>(pool);
        Random rand = new Random(seed + level * 31L);
        Collections.shuffle(copy, rand);
        List<NpcTradeOffer> picked = copy.subList(0, Math.min(count, copy.size()));
        // resolve 每项（固定随机数量）
        return picked.stream().map(t -> t.resolve(rand)).toList();
    }

    /** 获取某职业某级的池子（不随机，模板状态） */
    public static List<NpcTradeOffer> getPool(NpcProfession prof, int level) {
        List<List<NpcTradeOffer>> levels = POOLS.getOrDefault(prof, List.of());
        int idx = Math.min(level - 1, levels.size() - 1);
        if (idx < 0 || levels.isEmpty()) return List.of();
        return levels.get(idx);
    }

    // ==================== 商人特殊逻辑 ====================

    /** 商人跨职业池（排除单件不可打折项） */
    public static List<NpcTradeOffer> getMerchantPool(int level) {
        List<NpcTradeOffer> all = new ArrayList<>();
        for (NpcProfession prof : POOLS.keySet()) {
            if (prof == NpcProfession.NONE || prof == NpcProfession.TRADER) continue;
            for (NpcTradeOffer t : getPool(prof, level)) {
                if (level == 1 && isSingleDiscountable(t)) continue;
                all.add(t);
            }
        }
        return all;
    }

    /** 单件定价 1→1，无法打折，商人不要 */
    private static boolean isSingleDiscountable(NpcTradeOffer t) {
        return t.costMin() == 1 && t.costMax() == 1
                && t.resultMin() == 1 && t.resultMax() == 1;
    }

    /** 商人加价：提高 cost，降低 result */
    public static NpcTradeOffer applyMerchantMarkup(NpcTradeOffer original, double markupRate) {
        int newCostMin = Math.max(1, (int) Math.round(original.costMin() * (1.0 + markupRate)));
        int newCostMax = Math.max(1, (int) Math.round(original.costMax() * (1.0 + markupRate)));
        int newResultMin = Math.max(1, (int) Math.round(original.resultMin() * (1.0 - markupRate)));
        int newResultMax = Math.max(1, (int) Math.round(original.resultMax() * (1.0 - markupRate)));
        return new NpcTradeOffer(original.costItem(), newCostMin, newCostMax,
                original.resultItem(), newResultMin, newResultMax, original.minNpcLevel());
    }

    /** 商人货币兑换（固定，不计入 activeTrades） */
    public static NpcTradeOffer getCurrencyExchange(int slot) {
        return switch (slot) {
            case 0 -> new NpcTradeOffer(e(), 4, 4, goldIngot(), 1, 1, 1);
            case 1 -> new NpcTradeOffer(goldIngot(), 1, 1, e(), 3, 3, 1);
            default -> throw new IllegalArgumentException("slot must be 0 or 1");
        };
    }

    // ==================== 工厂方法 ====================

    private static NpcTradeOffer offer(ItemStack costItem, int costMin, int costMax,
                                        ItemStack resultItem, int resultMin, int resultMax,
                                        int minLevel) {
        return new NpcTradeOffer(costItem, costMin, costMax, resultItem, resultMin, resultMax, minLevel);
    }

    private static NpcTradeOffer buyOffer(ItemStack costItem, int costMin, int costMax,
                                           ItemStack resultItem, int resultMin, int resultMax) {
        return new NpcTradeOffer(costItem, costMin, costMax, resultItem, resultMin, resultMax, 1);
    }
    private static NpcTradeOffer buyOffer(ItemStack costItem, int costMin, int costMax,
                                           ItemStack resultItem, int resultMin, int resultMax,
                                           int minLevel) {
        return new NpcTradeOffer(costItem, costMin, costMax, resultItem, resultMin, resultMax, minLevel);
    }

    // ==================== 物品快捷工厂 ====================
    private static ItemStack e() { return new ItemStack(Items.EMERALD); }
    private static ItemStack iron() { return new ItemStack(Items.IRON_INGOT); }
    private static ItemStack goldIngot() { return new ItemStack(Items.GOLD_INGOT); }
    private static ItemStack lantern() { return new ItemStack(Items.LANTERN); }
    private static ItemStack coal() { return new ItemStack(Items.COAL); }
    private static ItemStack furnace() { return new ItemStack(Items.FURNACE); }
    private static ItemStack ironPick() { return new ItemStack(Items.IRON_PICKAXE); }
    private static ItemStack ironAxe() { return new ItemStack(Items.IRON_AXE); }
    private static ItemStack ironShovel() { return new ItemStack(Items.IRON_SHOVEL); }
    private static ItemStack ironHoe() { return new ItemStack(Items.IRON_HOE); }
    private static ItemStack ironSword() { return new ItemStack(Items.IRON_SWORD); }
    private static ItemStack shield() { return new ItemStack(Items.SHIELD); }
    private static ItemStack wheat() { return new ItemStack(Items.WHEAT); }
    private static ItemStack wheatSeeds() { return new ItemStack(Items.WHEAT_SEEDS); }
    private static ItemStack potato() { return new ItemStack(Items.POTATO); }
    private static ItemStack carrot() { return new ItemStack(Items.CARROT); }
    private static ItemStack beetSeeds() { return new ItemStack(Items.BEETROOT_SEEDS); }
    private static ItemStack beetroot() { return new ItemStack(Items.BEETROOT); }
    private static ItemStack cobblestone() { return new ItemStack(Items.COBBLESTONE); }
    private static ItemStack stone() { return new ItemStack(Items.STONE); }
    private static ItemStack dirt() { return new ItemStack(Items.DIRT); }
    private static ItemStack sand() { return new ItemStack(Items.SAND); }
    private static ItemStack gravel() { return new ItemStack(Items.GRAVEL); }
    private static ItemStack torch() { return new ItemStack(Items.TORCH); }
    private static ItemStack ladder() { return new ItemStack(Items.LADDER); }
    private static ItemStack stonePick() { return new ItemStack(Items.STONE_PICKAXE); }
    private static ItemStack stoneAxe() { return new ItemStack(Items.STONE_AXE); }
    private static ItemStack stoneShovel() { return new ItemStack(Items.STONE_SHOVEL); }
    private static ItemStack stoneSword() { return new ItemStack(Items.STONE_SWORD); }
    private static ItemStack bow() { return new ItemStack(Items.BOW); }
    private static ItemStack arrow() { return new ItemStack(Items.ARROW); }
    private static ItemStack crossbow() { return new ItemStack(Items.CROSSBOW); }
    private static ItemStack leatherChest() { return new ItemStack(Items.LEATHER_CHESTPLATE); }
    private static ItemStack leatherLegs() { return new ItemStack(Items.LEATHER_LEGGINGS); }
    private static ItemStack ironChest() { return new ItemStack(Items.IRON_CHESTPLATE); }
    private static ItemStack ironLegs() { return new ItemStack(Items.IRON_LEGGINGS); }
    private static ItemStack ironBoots() { return new ItemStack(Items.IRON_BOOTS); }
    private static ItemStack ironHelmet() { return new ItemStack(Items.IRON_HELMET); }
    private static ItemStack ironBlock() { return new ItemStack(Items.IRON_BLOCK); }
    private static ItemStack rottenFlesh() { return new ItemStack(Items.ROTTEN_FLESH); }
    private static ItemStack bone() { return new ItemStack(Items.BONE); }
    private static ItemStack string() { return new ItemStack(Items.STRING); }
    private static ItemStack feather() { return new ItemStack(Items.FEATHER); }
    private static ItemStack goldenApple() { return new ItemStack(Items.GOLDEN_APPLE); }
    private static ItemStack potion() { return new ItemStack(Items.POTION); }
    private static ItemStack paper() { return new ItemStack(Items.PAPER); }
    private static ItemStack book() { return new ItemStack(Items.BOOK); }
    private static ItemStack writableBook() { return new ItemStack(Items.WRITABLE_BOOK); }
    private static ItemStack nameTag() { return new ItemStack(Items.NAME_TAG); }
}
