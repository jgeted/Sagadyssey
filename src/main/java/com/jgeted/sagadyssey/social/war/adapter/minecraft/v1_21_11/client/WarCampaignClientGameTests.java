package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.client;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.network.NpcInteractionPacket;
import com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11.WarMapNetwork12111;
import com.jgeted.sagadyssey.social.war.api.WarMapInteraction;
import com.jgeted.sagadyssey.social.war.api.WarMapView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;

/** Test-only client. Setup messages ask it to use ordinary packets, orientation and map sessions. */
@EventBusSubscriber(modid="sagadyssey", value=Dist.CLIENT)
public final class WarCampaignClientGameTests {
    private static final String PREFIX = "[WarCampaign] ";
    private static final ArrayDeque<String> actions = new ArrayDeque<>();
    private static ClickEvent.RunCommand pendingConfirmation;
    private static boolean awaitingConfirmation;
    private static boolean connected;
    private static boolean stopping;
    private static int mapStep;
    private static long mapRevision;
    private static int ticks;
    private static int facingEntity = -1;
    private static int nextMapAttempt, nextMapRequest, mapRetries;

    @SubscribeEvent public static void chat(ClientChatReceivedEvent event) {
        if (!enabled()) return;
        var message = event.getMessage();
        String text = message.getString();
        if (text.startsWith(PREFIX)) actions.add(text.substring(PREFIX.length()));
        if (awaitingConfirmation) takeConfirmation(message);
    }

    private static boolean enabled() { return Boolean.getBoolean("sagadyssey.war.campaign.client"); }

    private static void takeConfirmation(Component message) {
        if (message.getStyle().getClickEvent() instanceof ClickEvent.RunCommand command && command.command().startsWith("/saga war muster confirm "))
            pendingConfirmation = command;
        for (var child : message.getSiblings()) takeConfirmation(child);
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!enabled() || stopping) return;
        var client = Minecraft.getInstance();
        if (Files.isRegularFile(Path.of(System.getProperty("sagadyssey.war.campaign.control"), "abort"))) {
            stopping = true;
            client.stop();
            return;
        }
        if (!connected && client.screen instanceof TitleScreen && client.getOverlay() == null) {
            String address = System.getProperty("sagadyssey.war.campaign.address");
            ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString(address),new ServerData("Campaign acceptance",address,ServerData.Type.OTHER),false,null);
            connected = true;
            return;
        }
        if (client.player == null || client.level == null || client.getConnection() == null || ++ticks % 2 != 0) return;
        try {
            if (pendingConfirmation != null) {
                ClickInvoker.confirm(client, pendingConfirmation);
                pendingConfirmation = null;
                awaitingConfirmation = false;
            }
            String current = actions.peekFirst();
            if (current != null && perform(client, current)) actions.removeFirst();
        } catch (Exception failure) {
            stopping = true;
            System.err.println("CAMPAIGN_CLIENT_FAILURE " + failure);
            failure.printStackTrace();
            client.stop();
        }
    }

    private static boolean perform(Minecraft client, String action) throws Exception {
        if (action.equals("STOP")) { stopping = true; client.stop(); return true; }
        if (action.startsWith("UI ")) return WarSurvivalClientGameTests.perform(client, action.substring(3), ticks);
        if (action.startsWith("CMD ")) { client.getConnection().sendCommand(action.substring(4)); return true; }
        String[] fields = action.split(" ");
        if (fields[0].equals("ORDER")) return order(client, fields);
        int id = Integer.parseInt(fields[1]);
        if (!(client.level.getEntity(id) instanceof NpcBase npc) || client.player.distanceToSqr(npc) > 32) return false;
        if (fields[0].equals("LOOK")) {
            if(!fields[3].matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("invalid observation name");
            return com.jgeted.sagadyssey.npc.client.NpcCampaignAppearanceGameTests.capture(client,npc,fields[2],
                    Path.of(System.getProperty("sagadyssey.war.campaign.control"),"look-"+fields[3]+".json"));
        }
        if (fields[0].equals("RECRUIT")) ClientPacketDistributor.sendToServer(new NpcInteractionPacket(id,"recruit"));
        else if (fields[0].equals("OFFER")) {
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES,npc.getEyePosition());
            client.getConnection().send(new ServerboundMovePlayerPacket.Rot(client.player.getYRot(),client.player.getXRot(),client.player.onGround(),client.player.horizontalCollision));
            if (facingEntity != id) { facingEntity = id; return false; }
            facingEntity = -1;
            awaitingConfirmation = true;
            client.getConnection().sendCommand("saga war muster " + fields[2] + " " + fields[3] + " " + fields[4] + " " + fields[5] + " 30");
        } else throw new IllegalArgumentException("unknown campaign input");
        return true;
    }

    private static boolean order(Minecraft client, String[] fields) throws Exception {
        if (mapStep == 0) {
            if(ticks<nextMapAttempt)return false;
            if (client.screen != null) client.screen.onClose();
            client.getConnection().sendCommand("saga war map");
            mapStep = 1;
            return false;
        }
        if (!(client.screen instanceof WarMapScreen12111 screen)) return false;
        var field = WarMapScreen12111.class.getDeclaredField("view");
        field.setAccessible(true);
        var view = (WarMapView) field.get(screen);
        if(mapStep<=3&&ticks<nextMapRequest)return false;
        if (mapStep >= 2 && mapRevision == view.revision()) return false;
        UUID unit = UUID.fromString(fields[1]);
        var mode = WarMapView.Mode.valueOf(fields[2]);
        if (mapStep <= 2) {
            if (view.units().stream().noneMatch(row -> row.id().equals(unit))) {
                int next = view.page()+1;
                if (next*WarMapView.PAGE_SIZE >= view.totalUnits()) throw new IllegalStateException("unit missing from native map: "+unit);
                send(view,WarMapInteraction.Action.REFRESH,next,unit,mode,0,0,WarMapInteraction.NONE);
                mapStep = 2;
            } else {
                int x=(int)Math.round(view.sheet().pixelX(Integer.parseInt(fields[3]))), z=(int)Math.round(view.sheet().pixelZ(Integer.parseInt(fields[4])));
                send(view,WarMapInteraction.Action.PREVIEW,view.page(),unit,mode,x,z,WarMapInteraction.NONE);
                mapStep = 3;
            }
            mapRevision = view.revision();
            return false;
        }
        if(mapStep==3){
            var preview = view.preview().filter(value -> value.unit().equals(unit) && value.mode()==mode);
            if(preview.isEmpty())return retryMap(screen,view);
            send(view,WarMapInteraction.Action.ISSUE,view.page(),unit,mode,0,0,preview.orElseThrow().ticket());
            mapStep=4;
            mapRevision=view.revision();
            return false;
        }
        if(view.receipt().filter(result->result.outcome()==WarMapView.Outcome.ACCEPTED).isEmpty())return retryMap(screen,view);
        mapStep=mapRetries=0;
        return true;
    }

    private static boolean retryMap(WarMapScreen12111 screen,WarMapView view) throws Exception {
        var message=WarMapScreen12111.class.getDeclaredField("message");
        message.setAccessible(true);
        String why=view.receipt().map(WarMapView.Receipt::reason).orElse(String.valueOf(message.get(screen)));
        System.out.println("CAMPAIGN_MAP_RETRY "+why);
        if(++mapRetries>4)throw new IllegalStateException("native map order not accepted: "+why);
        screen.onClose();
        mapStep=0;
        nextMapAttempt=ticks+22;
        return false;
    }

    private static void send(WarMapView view, WarMapInteraction.Action action, int page, UUID unit,
                             WarMapView.Mode mode, int x, int z, UUID ticket) {
        // The server limiter advances on server ticks, which can lag behind the client under load.
        nextMapRequest=ticks+14;
        var request=new WarMapInteraction.Request(view.session(),view.revision(),action,page,unit,mode,x,z,ticket);
        ClientPacketDistributor.sendToServer(new WarMapNetwork12111.RequestPayload(request));
    }

    private static final class ClickInvoker extends Screen {
        private ClickInvoker() { super(Component.empty()); }
        static void confirm(Minecraft client, ClickEvent.RunCommand command) { defaultHandleGameClickEvent(command,client,client.screen); }
    }
}
