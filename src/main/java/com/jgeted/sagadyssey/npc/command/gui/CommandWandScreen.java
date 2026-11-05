package com.jgeted.sagadyssey.npc.command.gui;

import com.jgeted.sagadyssey.npc.command.CommandRank;
import com.jgeted.sagadyssey.npc.command.SquadStrategy;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupActionPacket;
import com.jgeted.sagadyssey.npc.command.network.CommandGroupSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** 指挥杖的集团管理面板。全部按钮只发送意图，修改由服务端裁决。 */
public final class CommandWandScreen extends Screen {
    private static final int PANEL_W = 330;
    private static final int PANEL_H = 264;

    private final CommandGroupSyncPayload data;
    private int left;
    private int top;

    public CommandWandScreen(CommandGroupSyncPayload data) {
        super(Component.literal("集团指挥"));
        this.data = data;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int x = left + 12;
        int y = top + 43;

        addButton("切换小队", x, y, 94, "cycle_squad");
        addButton("选中整队", x + 100, y, 94, "select_squad");
        addButton("清空选择", x + 200, y, 94, "clear_selection");

        y += 27;
        addButton("新建并拆分", x, y, 94, "create_squad");
        addButton("加入当前队", x + 100, y, 94, "assign_selected");
        Button merge = addButton("并回主力队", x + 200, y, 94, "merge_main");
        merge.active = !"main".equals(data.activeSquadId());

        y += 27;
        addButton("降低等级", x, y, 94, "rank_down");
        addButton("提升等级", x + 100, y, 94, "rank_up");
        addButton("切换战术", x + 200, y, 94, "cycle_strategy");

        y += 39;
        addButton("跟随", x, y, 70, "command_follow");
        addButton("待命", x + 75, y, 70, "command_stay");
        addButton("开始工作", x + 150, y, 70, "command_work");
        addButton("设为工作区", x + 225, y, 70, "set_work_zone");

        y += 27;
        addButton("清除工作区", x, y, 94, "clear_work_zone");
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(x + 200, y, 94, 20).build());
    }

    private Button addButton(String label, int x, int y, int width, String action) {
        Button button = Button.builder(Component.literal(label), ignored ->
                        PacketDistributor.sendToServer(new CommandGroupActionPacket(action)))
                .bounds(x, y, width, 20).build();
        addRenderableWidget(button);
        return button;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xEE101724);

        CommandGroupSyncPayload.SquadView active = data.squads().stream()
                .filter(s -> s.id().equals(data.activeSquadId())).findFirst()
                .orElse(new CommandGroupSyncPayload.SquadView("main", "主力队", 0x42A5F5,
                        SquadStrategy.BALANCED.name(), 0));
        int accent = 0xFF000000 | active.color();
        drawBorder(graphics, accent);

        Component title = Component.literal("集团指挥 · " + active.name())
                .withStyle(style -> style.withBold(true));
        graphics.drawString(font, title, left + 12, top + 10, accent);
        int selected = (int) data.members().stream().filter(CommandGroupSyncPayload.MemberView::selected).count();
        graphics.drawString(font, "小队成员 " + active.memberCount() + "  ·  当前选中 " + selected,
                left + 12, top + 26, 0xFFD5DCE8);

        SquadStrategy strategy = SquadStrategy.byName(active.strategy());
        int infoY = top + 126;
        graphics.drawString(font, "战术：" + strategy.displayName() + " — " + strategy.description(),
                left + 12, infoY, 0xFFFFD166);

        String zoneText;
        if (data.corner1() == null) zoneText = "区域：未标记（右键地面两次框选）";
        else if (data.corner2() == null) zoneText = "区域：已标记第一角，等待第二角";
        else zoneText = "区域：" + data.corner1().toShortString() + " → " + data.corner2().toShortString();
        graphics.drawString(font, zoneText, left + 12, top + 193, 0xFF9EC5FF);

        graphics.drawString(font, "选中成员", left + 12, top + 218, 0xFFB8C2D1);
        List<CommandGroupSyncPayload.MemberView> selectedMembers = data.members().stream()
                .filter(CommandGroupSyncPayload.MemberView::selected).limit(4).toList();
        int memberX = left + 75;
        if (selectedMembers.isEmpty()) {
            graphics.drawString(font, "无", memberX, top + 218, 0xFF777F8C);
        } else {
            String names = selectedMembers.stream()
                    .map(m -> m.name() + "[" + CommandRank.byName(m.rank()).displayName() + "]")
                    .reduce((a, b) -> a + "、" + b).orElse("");
            if (selected > 4) names += " 等" + selected + "人";
            if (font.width(names) > PANEL_W - 90) {
                names = font.plainSubstrByWidth(names, PANEL_W - 102) + "…";
            }
            graphics.drawString(font, names, memberX, top + 218, 0xFFFFFFFF);
        }
        graphics.drawString(font, "右键 NPC 切换选择 · 蹲下右键地面清空 · 右键空气打开本面板",
                left + 12, top + 242, 0xFF7F8B9B);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawBorder(GuiGraphics graphics, int color) {
        graphics.fill(left - 1, top - 1, left + PANEL_W + 1, top, color);
        graphics.fill(left - 1, top + PANEL_H, left + PANEL_W + 1, top + PANEL_H + 1, color);
        graphics.fill(left - 1, top, left, top + PANEL_H, color);
        graphics.fill(left + PANEL_W, top, left + PANEL_W + 1, top + PANEL_H, color);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean isPauseScreen() { return false; }
}
