package com.jgeted.sagadyssey.npc.brain.command;

import com.jgeted.sagadyssey.core.MinecraftApi;
import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcCommand;
import com.jgeted.sagadyssey.social.service.AuthorityScope;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Immutable player request, not ownership of an NPC or its private possessions. */
public record NpcCommandIntent(UUID id, UUID character, UUID issuer, UUID player,
                               List<UUID> evidence, NpcCommand command, NpcCommand previous,
                               String dimension, BlockPos anchor, int radius, long started,
                               long expires, Status status, int leg, long updated, String detail) {
    public enum Status { QUEUED, ACTIVE, CANCELLED, EXPIRED }
    public NpcCommandIntent {
        Objects.requireNonNull(id); Objects.requireNonNull(character); Objects.requireNonNull(issuer);
        Objects.requireNonNull(player); Objects.requireNonNull(command); Objects.requireNonNull(previous);
        Objects.requireNonNull(status); Objects.requireNonNull(anchor);
        evidence = List.copyOf(evidence); anchor = anchor.immutable();
        if (evidence.isEmpty() || evidence.size() > 16 || new HashSet<>(evidence).size()!=evidence.size()
                || scope(command)==null || character.equals(issuer) || radius<1 || radius>48
                || started<0 || expires<=started || expires-started>24000
                || updated<started || leg<0 || leg>100000 || Identifier.tryParse(dimension)==null)
            throw new IllegalArgumentException("invalid command intent");
        detail=Objects.requireNonNullElse(detail, "");
        if(detail.length()>512) throw new IllegalArgumentException("command detail too long");
    }
    public boolean closed() { return status==Status.CANCELLED || status==Status.EXPIRED; }
    public static AuthorityScope scope(NpcCommand c) {
        return switch(c) { case FOLLOW -> AuthorityScope.FOLLOW; case STAY -> AuthorityScope.HOLD_POSITION;
            case WORK -> AuthorityScope.WORK; case PATROL -> AuthorityScope.PATROL; default -> null; };
    }
    public NpcCommandIntent update(Status next, int nextLeg, long now, String reason) {
        if(closed() || nextLeg<leg || now<updated || status==Status.ACTIVE && next==Status.QUEUED)
            throw new IllegalStateException("invalid command transition");
        return new NpcCommandIntent(id,character,issuer,player,evidence,command,previous,dimension,
                anchor,radius,started,expires,next,nextLeg,now,reason);
    }
    public boolean sameIntent(NpcCommandIntent other) {
        return id.equals(other.id)&&character.equals(other.character)&&issuer.equals(other.issuer)
                &&player.equals(other.player)&&evidence.equals(other.evidence)&&command==other.command
                &&previous==other.previous&&dimension.equals(other.dimension)&&anchor.equals(other.anchor)
                &&radius==other.radius&&started==other.started&&expires==other.expires;
    }
    public CompoundTag save() {
        var t=new CompoundTag(); t.store("Id", UUIDUtil.CODEC,id);t.store("Character", UUIDUtil.CODEC,character);t.store("Issuer", UUIDUtil.CODEC,issuer);
        t.store("Player", UUIDUtil.CODEC,player);var e=new ListTag();for(var proof:evidence){var p=new CompoundTag();p.store("Id", UUIDUtil.CODEC,proof);e.add(p);}t.put("Evidence",e);
        t.putString("Command",command.name());t.putString("Previous",previous.name());t.putString("Dimension",dimension);
        t.putLong("Anchor",anchor.asLong());t.putInt("Radius",radius);t.putLong("Started",started);
        t.putLong("Expires",expires);t.putString("Status",status.name());t.putInt("Leg",leg);
        t.putLong("Updated",updated);t.putString("Detail",detail);return t;
    }
    public static NpcCommandIntent load(CompoundTag t) {
        for(var key:List.of("Id","Character","Issuer","Player")) if(!t.read(key, UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("missing "+key);
        for(var key:List.of("Command","Previous","Dimension","Status","Detail")) require(t,key,Tag.TAG_STRING);
        for(var key:List.of("Anchor","Started","Expires","Updated")) require(t,key,Tag.TAG_LONG);
        for(var key:List.of("Radius","Leg")) require(t,key,Tag.TAG_INT);
        if(!(t.get("Evidence") instanceof ListTag e)||e.isEmpty()||e.size()>16||MinecraftApi.elementType(e)!=Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("invalid authority evidence");
        var proofs=new ArrayList<UUID>();for(var value:e){var p=(CompoundTag)value;if(!p.read("Id", UUIDUtil.CODEC).isPresent())throw new IllegalArgumentException("missing proof");proofs.add(p.read("Id", UUIDUtil.CODEC).orElseThrow());}
        return new NpcCommandIntent(t.read("Id", UUIDUtil.CODEC).orElseThrow(),t.read("Character", UUIDUtil.CODEC).orElseThrow(),t.read("Issuer", UUIDUtil.CODEC).orElseThrow(),t.read("Player", UUIDUtil.CODEC).orElseThrow(),proofs,
                NpcCommand.valueOf(t.getStringOr("Command", "")),NpcCommand.valueOf(t.getStringOr("Previous", "")),t.getStringOr("Dimension", ""),
                BlockPos.of(t.getLongOr("Anchor", 0L)),t.getIntOr("Radius", 0),t.getLongOr("Started", 0L),t.getLongOr("Expires", 0L),
                Status.valueOf(t.getStringOr("Status", "")),t.getIntOr("Leg", 0),t.getLongOr("Updated", 0L),t.getStringOr("Detail", ""));
    }
    private static void require(CompoundTag t,String k,int type){if(!MinecraftApi.containsTag(t, k,type))throw new IllegalArgumentException("missing "+k);}
}
