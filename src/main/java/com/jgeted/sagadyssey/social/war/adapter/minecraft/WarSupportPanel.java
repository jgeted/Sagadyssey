package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Offer;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Stage;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

import java.util.*;

import static com.jgeted.sagadyssey.social.war.adapter.minecraft.WarManagementRuntime.*;

final class WarSupportPanel {
    private WarSupportPanel() {}

    static void army(Session s, Army army, Step back) {
        var page = s.page(army.name() + " · 军库与粮饷");
        var depot = WarCampaignRuntime.existing(s.server()).flatMap(r -> r.depot(army.id()));
        page.line(depot.map(d -> "原军库 · " + coordinates(BlockPos.of(d.location().position()))).orElse("尚无已保存的军库。"))
                .line("面谈服役中的军务人员，可指定一趟实物运输。")
                .button("粮饷账目", () -> bills(s, army, back))
                .button("登记实体军库", () -> pickDepot(s, army, back))
                .button("运单", () -> shipments(s, army, Optional.empty(), () -> army(s, army, back), 0))
                .button("刷新", () -> army(s, army, back)).button("返回", back).show();
    }

    private static void bills(Session s, Army army, Step back) throws Exception {
        s.page(army.name() + " · 粮饷")
                .lines(WarStaffRuntime.open(s.server()).supplies(s.player, army.id()))
                .button("刷新", () -> bills(s, army, back)).button("返回", () -> army(s, army, back)).show();
    }

    private static void pickDepot(Session s, Army army, Step back) {
        s.pickBlock("手持这张地图右键准备登记的实体箱子或木桶。潜行朝空中右键可取消。", (at, face) -> {
            var target = s.player.level().getBlockEntity(at);
            if (!(target instanceof Container)) throw new IllegalArgumentException("请选择单个箱子或木桶。");
            s.confirm("登记军库", "用 " + coordinates(at) + " 的当前容器作为 " + army.name() + " 的军库。",
                    "确认登记", () -> {
                        if (s.player.level().getBlockEntity(at) != target) throw new IllegalArgumentException("原容器已经改变。");
                        s.notice(WarCampaignRuntime.open(s.server()).bindDepot(s.player, army.id(), at));
                        army(s, army, back);
                    }, () -> army(s, army, back));
        });
    }

    static void carrier(Session s, Person who, Offer offer, Army army) {
        var npc = who.near(s);
        Step back = () -> WarMusterPanel.person(s, who);
        s.page(npc.getNpcName() + " · 运送物资").line("从本军已登记军库取物，运送给本军原人物或已登记目的箱。")
                .button("送给军士", () -> {
                    who.near(s);
                    s.pickEntity("拿地图右键要收货的在役军士。潜行朝空中右键可取消。", target -> {
                        var receiver = new Person(target.getCharacterId(), target.getUUID());
                        var service = WarMusterPanel.latest(s, receiver)
                                .filter(o -> o.stage() == Stage.ACTIVE && o.request().armyId().equals(army.id()))
                                .orElseThrow(() -> new IllegalArgumentException("请选择本军在役原人物。"));
                        cargo(s, who, offer, army, ItemLocation.personal(service.request().characterId(), service.body()),
                                target.getNpcName(), 0);
                    });
                }).button("送到军库", () -> {
                    who.near(s);
                    s.pickBlock("拿地图右键已登记的目的箱。潜行朝空中右键可取消。", (at, face) ->
                            cargo(s, who, offer, army, WarCampaignRuntime.destination(s.player, at), "箱子 " + coordinates(at), 0));
                }).button("本人的运单", () -> shipments(s, army, Optional.of(offer.id()),
                        () -> carrier(s, who, offer, army), 0))
                .button("返回", back).show();
    }

    private record Stock(String id, String name, int maximum, int count) {}

    private static void cargo(Session s, Person who, Offer offer, Army army, ItemLocation target, String destination, int pageNumber) {
        var runtime = WarCampaignRuntime.existing(s.server()).orElseThrow(() -> new IllegalArgumentException("请先登记本军军库。"));
        var source = runtime.depot(army.id()).filter(d -> d.owner().equals(s.actor))
                .orElseThrow(() -> new IllegalArgumentException("请先登记并保存本军军库。"));
        if (source.location().equals(target)) throw new IllegalArgumentException("请选与取货不同的实体端点。");
        var endpoint = source.location().resolve(s.server(), CharacterRegistryData.get(s.server()))
                .orElseThrow(() -> new IllegalArgumentException("原军库尚未加载。"));
        var container = endpoint.container();
        var stocks = new LinkedHashMap<String, Stock>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            var fp = ItemFingerprint.of(stack);
            if (fp.componentsHash() != 0) continue;
            stocks.compute(fp.itemId(), (id, old) -> new Stock(id, stack.getHoverName().getString(), stack.getMaxStackSize(),
                    stack.getCount() + (old == null ? 0 : old.count())));
        }
        var page = s.page("选择军库实物").line("收货处 · " + destination);
        if (stocks.isEmpty()) page.line("库中没有可按普通堆叠运输的物资。");
        page.choices(List.copyOf(stocks.values()), pageNumber, stock -> stock.name() + " ×" + stock.count(),
                stock -> quantity(s, who, offer, army, target, destination, stock),
                next -> cargo(s, who, offer, army, target, destination, next))
                .button("返回承运人", () -> carrier(s, who, offer, army)).show();
    }

    private static void quantity(Session s, Person who, Offer o, Army army, ItemLocation target, String destination, Stock stock) {
        s.page("批准运输").line(stock.name() + " → " + destination)
                .line("原军务人员须实际取货、运送和交付。")
                .field("每趟数量", Integer.toString(Math.min(8, Math.min(stock.maximum(), stock.count()))), 2)
                .field("趟数", "1", 1).action("确认运单", values -> {
                    int amount = number(values, 0, 1, Math.min(64, stock.maximum()), "每趟数量");
                    int trips = number(values, 1, 1, 8, "趟数");
                    var runtime = WarCampaignRuntime.open(s.server());
                    var id = runtime.ship(s.player, o.id(), target, stock.id(), amount, trips);
                    s.notice("已批准，等待本人实际运送。");
                    shipment(s, army, id, () -> carrier(s, who, o, army));
                }).button("返回库存", () -> cargo(s, who, o, army, target, destination, 0)).show();
    }

    private static void shipments(Session s, Army army, Optional<UUID> carrier, Step back, int pageNumber) {
        var entries = WarCampaignRuntime.existing(s.server()).stream().flatMap(r -> r.entries().stream())
                .filter(WarCampaignEntry.Shipment.class::isInstance).map(WarCampaignEntry.Shipment.class::cast)
                .filter(row -> row.run().army().equals(army.id()) && carrier.map(row.run().service()::equals).orElse(true))
                .sorted(Comparator.comparingLong((WarCampaignEntry.Shipment row) -> row.run().createdAt()).reversed()).toList();
        var page = s.page("运输记录");
        if (entries.isEmpty()) page.line("这里还没有运单。");
        page.choices(entries, pageNumber, row -> WarMusterPanel.personName(s, row.run().person()) + " · " + phase(row),
                        row -> shipment(s, army, row.id(), () -> shipments(s, army, carrier, back, pageNumber)),
                        next -> shipments(s, army, carrier, back, next))
                .button("刷新", () -> shipments(s, army, carrier, back, pageNumber)).button("返回", back).show();
    }

    private static void shipment(Session s, Army army, UUID id, Step back) {
        var runtime = WarCampaignRuntime.existing(s.server()).orElseThrow();
        var row = runtime.entries().stream().filter(WarCampaignEntry.Shipment.class::isInstance).map(WarCampaignEntry.Shipment.class::cast)
                .filter(r -> r.id().equals(id) && r.run().army().equals(army.id())).findFirst().orElseThrow();
        var run = row.run();
        var page = s.page("运输进度").line(WarMusterPanel.personName(s, run.person()) + " · " + phase(row))
                .line(row.item().itemId() + " ×" + run.quantity() + " · 已交付 " + run.delivered() + "/" + run.trips() + " 趟")
                .line(run.reason()).button("刷新", () -> shipment(s, army, id, back)).button("返回", back);
        if (!run.terminal()) page.button("撤回运单", () -> s.confirm("撤回运单", "已领取的实物须返还原库。",
                "确认撤回", () -> {
                    s.notice(runtime.cancel(s.player, id, false));
                    shipment(s, army, id, back);
                }, () -> shipment(s, army, id, back)));
        page.show();
    }

    private static String phase(WarCampaignEntry.Shipment shipment) {
        return switch (shipment.run().phase()) {
            case TO_SOURCE, LOADING -> "取货中"; case TO_TARGET, UNLOADING -> "送货中";
            case RETURNING, RETURN_UNLOAD -> "返库中"; case DONE -> "已交付";
            case CANCELLED -> "已取消"; case REVIEW -> "待核对";
        };
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
}
