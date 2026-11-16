package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.social.war.adapter.minecraft.WarNativeBindings.StaffOffices;

import com.jgeted.sagadyssey.social.institution.*;
import com.jgeted.sagadyssey.social.war.domain.WarStaffEntry.Terms;
import com.jgeted.sagadyssey.social.war.domain.WarMusterEntry.Army;
import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import com.jgeted.sagadyssey.worldsim.transfer.ItemLocation;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Shared InstitutionOffice is the sole holder/term/permission authority. No asset ownership changes. */
public final class WarStaffOffices12111 implements StaffOffices {
    private final MinecraftServer server;private final Path file;private final InstitutionData data;
    private Map<UUID,InstitutionOffice> saved=Map.of();private CompletableFuture<?> pending;private boolean requested;private long nextAt;private String problem="";
    public WarStaffOffices12111(MinecraftServer server,Set<UUID> knownAppointments)throws IOException{
        this.server=server;file=server.getWorldPath(LevelResource.ROOT).resolve("data/sagadyssey_institutions.dat");
        if(!knownAppointments.isEmpty()){read();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("shared office evidence missing");}
        data=InstitutionData.get(server);
        for(var office:data.offices()){
            var institution=data.institution(office.institutionId());
            if(institution!=null&&institution.kind()==InstitutionKind.MILITIA&&institution.charter().startsWith("sagadyssey:war/staff/v1/")
                    &&office.authorities().contains(OfficeAuthority.REVIEW_MILITARY_REPORTS)&&!knownAppointments.contains(office.id()))throw new IOException("shared military office without original staffing evidence");
        }
    }
    public String problem(){return problem;}
    public InstitutionOffice expected(Terms t){return new InstitutionOffice(t.id(),t.army(),t.role().title(),SubjectRef.character(t.holder()),Set.of(OfficeAuthority.REVIEW_MILITARY_REPORTS),SubjectRef.character(t.owner()),t.startsAt(),t.endsAt(),false);}
    private Optional<InstitutionOffice> current(UUID id){return Optional.ofNullable(data.office(id));}
    public boolean exists(UUID id){return data.office(id)!=null;}
    public boolean matches(Terms t,boolean revoked){var e=expected(t);return current(t.id()).filter((revoked?e.revoke():e)::equals).isPresent();}
    public boolean durable(Terms t,boolean revoked){var e=expected(t);return (revoked?e.revoke():e).equals(saved.get(t.id()));}
    public void establish(Army army,UUID account){
        var expected=new InstitutionRecord(army.id(),army.name(),InstitutionKind.MILITIA,null,ItemLocation.player(army.owner(),account).id(),army.createdAt(),true,"sagadyssey:war/staff/v1/"+army.owner());
        var old=data.institution(army.id());
        if(old==null){if(!data.addInstitution(expected))throw new IllegalStateException("military institution publication conflict");request();}
        else if(!old.equals(expected))throw new IllegalStateException("military institution identity conflict");
    }
    public void appoint(Terms t){if(!data.addOffice(expected(t)))throw new IllegalStateException("original office identity occupied");request();}
    public void revoke(Terms t){if(!matches(t,false)&&!matches(t,true))throw new IllegalStateException("shared office terms changed");if(matches(t,false))data.revokeOffice(t.id());request();}
    public boolean active(Terms t,long now){var institution=data.institution(t.army());return problem.isEmpty()&&institution!=null&&institution.active()&&matches(t,false)&&durable(t,false)&&expected(t).activeAt(now);}
    public void request(){requested=true;}
    public void tick(long now){
        if(pending!=null&&pending.isDone()){try{pending.join();read();}catch(IOException|RuntimeException e){problem="STAFF_SHARED_CHECKPOINT_REQUIRES_REVIEW:"+e.getClass().getSimpleName();saved=Map.of();}pending=null;}
        if(pending==null&&requested&&now>=nextAt&&problem.isEmpty()){requested=false;nextAt=now+20;pending=server.overworld().getDataStorage().scheduleSave();}
    }
    private void read()throws IOException{
        BasicFileAttributes attr;
        try{attr=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException missing){throw new IOException("shared institution snapshot missing",missing);}
        if(!attr.isRegularFile()||attr.size()>16L*1024*1024)throw new IOException("invalid institution snapshot");
        var root=NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));
        if(!(root.get("data") instanceof CompoundTag d)||!(d.get("Offices") instanceof ListTag rows)||rows.size()>16384)throw new IOException("invalid office snapshot root");
        var result=new HashMap<UUID,InstitutionOffice>();
        for(var raw:rows){
            if(!(raw instanceof CompoundTag row)||!(row.get("Authorities") instanceof ListTag powers)||powers.isEmpty()||powers.size()>128)throw new IOException("invalid shared office row");
            var authorities=EnumSet.noneOf(OfficeAuthority.class);for(var p:powers){if(!(p instanceof CompoundTag value)||!authorities.add(OfficeAuthority.valueOf(value.getString("Value").orElseThrow())))throw new IOException("invalid office authority");}
            var office=new InstitutionOffice(row.read("Id",UUIDUtil.CODEC).orElseThrow(),row.read("Institution",UUIDUtil.CODEC).orElseThrow(),row.getString("Title").orElseThrow(),SubjectRef.load(row.getCompoundOrEmpty("Holder")),authorities,SubjectRef.load(row.getCompoundOrEmpty("AppointedBy")),row.getLong("StartsAt").orElseThrow(),row.getLong("EndsAt").orElseThrow(),row.getBoolean("Revoked").orElseThrow());
            if(result.putIfAbsent(office.id(),office)!=null)throw new IOException("duplicate shared office identity");
        }saved=Map.copyOf(result);
    }
}
