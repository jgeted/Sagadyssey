package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/** Drives native item use and real screen widgets inside the protected campaign client. */
final class WarSurvivalClientGameTests {
    private static int next;
    private WarSurvivalClientGameTests() {}

    static boolean perform(Minecraft client, String action, int ticks) throws Exception {
        if (ticks < next) return false;
        var fields = action.split(" ", 2);
        String argument = fields.length > 1 ? fields[1] : "";
        boolean complete = switch (fields[0]) {
            case "MAP" -> openMap(client);
            case "TALK" -> npc(client, Integer.parseInt(argument), true);
            case "CIVIL" -> npc(client, Integer.parseInt(argument), false);
            case "BUTTON" -> button(client, argument);
            case "FORM" -> form(client, argument);
            case "BLOCK" -> block(client, argument);
            case "PIXEL" -> pixel(client, argument);
            case "HOTBAR" -> { client.player.getInventory().setSelectedSlot(Integer.parseInt(argument)); yield true; }
            case "USE" -> { client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); yield true; }
            case "MARKING" -> worldMarking(client, argument);
            case "CLOSE" -> { if (client.screen != null) client.screen.onClose(); yield true; }
            default -> throw new IllegalArgumentException("unknown survival fixture action " + action);
        };
        if (complete) {
            next = ticks + 8;
            System.out.println("WAR_SURVIVAL_CLIENT " + action);
        }
        return complete;
    }

    private static boolean openMap(Minecraft mc) {
        if (mc.screen != null) return false;
        sneak(mc, true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        sneak(mc, false);
        return true;
    }

    private static boolean npc(Minecraft mc, int entity, boolean military) {
        if (mc.screen != null || !(mc.level.getEntity(entity) instanceof NpcBase npc)
                || !mc.player.isWithinEntityInteractionRange(npc, 0.0)) return false;
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, npc.getEyePosition());
        sneak(mc, military);
        var hit = new EntityHitResult(npc, npc.getEyePosition());
        var result = mc.gameMode.interactAt(mc.player, npc, hit, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) mc.gameMode.interact(mc.player, npc, InteractionHand.MAIN_HAND);
        sneak(mc, false);
        return true;
    }

    private static boolean button(Minecraft mc, String label) {
        if (mc.screen == null) return false;
        var alternatives = List.of(label.split("\\|"));
        var target = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && b.active && alternatives.stream().anyMatch(s -> s.startsWith("~")
                        ? b.getMessage().getString().contains(s.substring(1)) : b.getMessage().getString().startsWith(s)))
                .findFirst();
        if (target.isEmpty()) return false;
        var b = target.get();
        return click(mc, b.getX() + b.getWidth() / 2D, b.getY() + b.getHeight() / 2D);
    }

    private static boolean form(Minecraft mc, String values) {
        if (!(mc.screen instanceof WarManagementScreen12111 panel)) return false;
        var inputs = panel.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
        String[] text = values.split("\\|", -1);
        if (inputs.size() != text.length) return false;
        for (int i = 0; i < text.length; i++) inputs.get(i).setValue(text[i]);
        return true;
    }

    private static boolean block(Minecraft mc, String coordinates) {
        if (mc.screen != null) return false;
        int[] xyz = Arrays.stream(coordinates.split(" ")).mapToInt(Integer::parseInt).toArray();
        var at = new BlockPos(xyz[0], xyz[1], xyz[2]);
        var view = new Vec3(at.getX() + .5, at.getY() + .99, at.getZ() + .5);
        var hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), view, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, mc.player));
        if (!(hit instanceof BlockHitResult) || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(at)) return false;
        sneak(mc, false);
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, view);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }

    private static boolean pixel(Minecraft mc, String coordinates) throws ReflectiveOperationException {
        if (!(mc.screen instanceof WarManagementScreen12111 panel) || panel.view().map().isEmpty()) return false;
        int[] pos = Arrays.stream(coordinates.split(" ")).mapToInt(Integer::parseInt).toArray();
        int size = field(panel, "mapSize");
        return click(mc, field(panel, "mapLeft") + (pos[0] + .5) * size / 128,
                field(panel, "mapTop") + (pos[1] + .5) * size / 128);
    }

    private static boolean worldMarking(Minecraft client, String witness) throws Exception {
        var field = WarManagementClient12111.class.getDeclaredField("marking");
        field.setAccessible(true);
        if (client.screen != null || field.get(null) == null) return false;
        if (!witness.matches("[a-z-]{1,24}")) throw new IllegalArgumentException("invalid world witness");
        Files.writeString(Path.of(System.getProperty("sagadyssey.war.campaign.control"), "ui-" + witness), "world");
        return true;
    }

    private static void sneak(Minecraft client, boolean shifted) {
        var keys = shifted ? new Input(false, false, false, false, false, true, false) : Input.EMPTY;
        client.player.input.keyPresses = keys;
        client.getConnection().send(new ServerboundPlayerInputPacket(keys));
    }

    private static int field(Object state, String name) throws ReflectiveOperationException {
        var field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(state);
    }

    private static boolean click(Minecraft mc, double x, double y) {
        var screen = mc.screen;
        var event = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        boolean handled = screen.mouseClicked(event, false);
        screen.mouseReleased(event);
        return handled;
    }
}
