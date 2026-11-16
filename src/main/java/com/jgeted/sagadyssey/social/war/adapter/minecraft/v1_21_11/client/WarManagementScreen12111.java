package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.*;
import java.util.function.Consumer;

import static com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.CLOSE;
import static com.jgeted.sagadyssey.social.war.api.WarManagementInteraction.MAP_POINT;

/** Used for the military book and close-range military conversations. */
public final class WarManagementScreen12111 extends Screen {
    private View view;
    private final Consumer<Request> send;
    private final MapRenderState mapState = new MapRenderState();
    private final List<EditBox> inputs = new ArrayList<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private List<FormattedCharSequence> details = List.of();
    private int left, top, wide, tall, detailsTop, detailsBottom, mapLeft, mapTop, mapSize, offset, controlPage, waiting;
    private boolean tiny;

    public WarManagementScreen12111(View view, Consumer<Request> send) {
        super(Component.literal(view.title()));
        this.view = view;
        this.send = Objects.requireNonNull(send);
    }

    public UUID session() { return view.session(); }
    public View view() { return view; }

    void accept(View update) {
        if (update.revision() < view.revision()) return;
        boolean sameForm = update.title().equals(view.title()) && update.fields().equals(view.fields())
                && update.controls().equals(view.controls()) && update.map().equals(view.map());
        var values = sameForm ? values() : List.<String>of();
        view = update;
        waiting = 0;
        if (!sameForm) { offset = 0; controlPage = 0; }
        rebuildWidgets();
        for (int i = 0; i < values.size() && i < inputs.size(); i++) inputs.get(i).setValue(values.get(i));
    }

    @Override protected void init() {
        inputs.clear();
        actionButtons.clear();
        wide = Math.min(520, width - 12);
        tall = Math.min(350, height - 12);
        left = (width - wide) / 2;
        top = (height - tall) / 2;
        tiny = wide < 238 || tall < 150;
        button("关闭", left + wide - 55, top + 7, 45, this::onClose);
        if (tiny) return;

        int columns = wide >= 364 ? 3 : 2;
        int fieldRows = (view.fields().size() + 1) / 2;
        int visible = Math.max(columns, (tall - 110 - fieldRows * 30) / 22 * columns);
        boolean controlsPage = view.controls().size() > visible;
        int start = controlPage * visible;
        if (start >= view.controls().size()) { start = 0; controlPage = 0; }
        int end = Math.min(start + visible, view.controls().size());
        int rows = (end - start + columns - 1) / columns;
        int controlsTop = top + tall - 10 - rows * 22 - (controlsPage ? 22 : 0);
        int fieldTop = controlsTop - fieldRows * 30 - 3;
        int fieldWidth = (wide - 28) / 2;
        for (int i = 0; i < view.fields().size(); i++) {
            var field = view.fields().get(i);
            int x = left + 11 + (i % 2) * (fieldWidth + 5), y = fieldTop + (i / 2) * 30 + 10;
            var input = new EditBox(font, x, y, fieldWidth, 17, Component.literal(field.label()));
            input.setMaxLength(field.length());
            input.setValue(field.initial());
            inputs.add(addRenderableWidget(input));
        }
        int buttonWidth = (wide - 16) / columns - 5;
        for (int i = start; i < end; i++) {
            int local = i - start, control = i;
            int x = left + 10 + (local % columns) * (buttonWidth + 5), y = controlsTop + (local / columns) * 22;
            var action = button(view.controls().get(i), x, y, buttonWidth, () -> request(control, 0, 0));
            action.active = waiting == 0;
            actionButtons.add(action);
        }
        if (controlsPage) {
            final int first = start, last = end;
            var previous = button("上一组操作", left + 10, top + tall - 27, 84, () -> changeControls(-1));
            var following = button("下一组操作", left + wide - 94, top + tall - 27, 84, () -> changeControls(1));
            previous.active = first > 0;
            following.active = last < view.controls().size();
        }
        detailsTop = top + 35;
        detailsBottom = fieldTop - 3;
        if (view.map().isPresent()) {
            mapSize = Math.min(256, Math.min(wide - 20, detailsBottom - detailsTop - 35));
            mapTop = detailsTop + 32;
            mapLeft = left + (wide - mapSize) / 2;
            detailsBottom = mapTop - 2;
        } else mapSize = 0;
        var rowsOfText = new ArrayList<FormattedCharSequence>();
        if (!view.message().isBlank()) rowsOfText.addAll(font.split(Component.literal(view.message()), wide - 22));
        view.lines().forEach(line -> rowsOfText.addAll(font.split(Component.literal(line), wide - 22)));
        details = List.copyOf(rowsOfText);
        offset = Math.min(offset, scrollLimit());
    }

    private Button button(String label, int x, int y, int w, Runnable click) {
        var caption = Component.literal(label);
        var b = Button.builder(caption, ignored -> click.run()).bounds(x, y, w, 18).build();
        if (font.width(caption) > w - 6) b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(caption));
        return addRenderableWidget(b);
    }

    private void changeControls(int direction) {
        var saved = values();
        controlPage += direction;
        rebuildWidgets();
        for (int i = 0; i < saved.size(); i++) inputs.get(i).setValue(saved.get(i));
    }

    private List<String> values() { return inputs.stream().map(EditBox::getValue).toList(); }

    private void request(int control, int x, int z) {
        if (waiting != 0) return;
        waiting = 1;
        actionButtons.forEach(button -> button.active = false);
        send.accept(new Request(session(), view.revision(), control, values(), x, z));
    }

    @Override public void tick() {
        if (waiting == 0 || ++waiting < 75) return;
        waiting = 0;
        actionButtons.forEach(button -> button.active = true);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!tiny && mapSize > 0 && event.button() == 0 && event.x() >= mapLeft && event.x() < mapLeft + mapSize
                && event.y() >= mapTop && event.y() < mapTop + mapSize) {
            int x = (int) ((event.x() - mapLeft) * 128 / mapSize);
            int z = (int) ((event.y() - mapTop) * 128 / mapSize);
            request(MAP_POINT, x, z);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= left && x < left + wide && y >= detailsTop && y < detailsBottom) {
            offset = Math.clamp(offset - (int) Math.signum(scrollY) * 2, 0, scrollLimit());
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private int scrollLimit() { return Math.max(0, details.size() - Math.max(1, (detailsBottom - detailsTop) / 12)); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xB0111518);
        g.fill(left, top, left + wide, top + tall, 0xF51B242B);
        g.fill(left, top, left + wide, top + 2, 0xFFCCAC68);
        g.drawString(font, font.plainSubstrByWidth(view.title(), Math.max(20, wide - 68)), left + 11, top + 12, 0xFFE6D6AF, false);
        if (tiny) {
            g.drawString(font, "请降低界面缩放后重开军务。", left + 11, top + 38, 0xFFE6D6AF, false);
        } else {
            int lines = Math.min(details.size(), offset + Math.max(1, (detailsBottom - detailsTop) / 12));
            for (int i = offset; i < lines; i++)
                g.drawString(font, details.get(i), left + 11, detailsTop + (i - offset) * 12, 0xFFBCCBC7, false);
            for (int i = 0; i < inputs.size(); i++) {
                var input = inputs.get(i);
                g.drawString(font, view.fields().get(i).label(), input.getX(), input.getY() - 10, 0xFFE6D6AF, false);
            }
            if (mapSize > 0) renderMap(g);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderMap(GuiGraphics g) {
        var sheet = view.map().orElseThrow();
        g.fill(mapLeft - 1, mapTop - 1, mapLeft + mapSize + 1, mapTop + mapSize + 1, 0xFFCCAC68);
        g.fill(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize, 0xFFB7AC89);
        var id = new MapId(sheet.id());
        var data = minecraft.level == null ? null : MapItem.getSavedData(id, minecraft.level);
        if (data == null) return;
        minecraft.getMapRenderer().extractRenderState(id, data, mapState);
        g.pose().pushMatrix();
        g.pose().translate(mapLeft, mapTop);
        g.pose().scale(mapSize / 128F);
        g.submitMapRenderState(mapState);
        g.pose().popMatrix();
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        WarManagementClient12111.closed(session());
        send.accept(new Request(session(), view.revision(), CLOSE, List.of(), 0, 0));
        super.onClose();
    }
}
