package com.jgeted.sagadyssey.social.religion.life;

import com.jgeted.sagadyssey.social.institution.OfficeAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

/** Fixed invitation, explicit consent and durable labor. No elapsed-offline work. */
public record ChurchActivity(UUID id,UUID temple,Kind kind,BlockPos venue,UUID subject,UUID source,
        List<UUID> invited,Set<UUID> consent,int minimum,long scheduled,long deadline,int duration,
        UUID host,Stage stage,int work,int attempts,long retryAt,long updated,String detail) {
    public enum Kind {
        RITE(OfficeAuthority.PERFORM_PUBLIC_RITE),LESSON(OfficeAuthority.TEACH_PUBLICLY),
        COPY(OfficeAuthority.MANAGE_LIBRARY),VISIT(OfficeAuthority.COMMUNITY_CARE),
        MARRIAGE(OfficeAuthority.CERTIFY_VITAL_RECORD),FUNERAL(OfficeAuthority.CERTIFY_VITAL_RECORD),
        MAINTENANCE(OfficeAuthority.MAINTAIN_FACILITY),WATCH(OfficeAuthority.WATCH_PREMISES);
        public final OfficeAuthority power;
        Kind(OfficeAuthority power){this.power=power;}
        public boolean manuscript(){return this==COPY||this==MARRIAGE||this==FUNERAL;}
    }
    public enum Stage {PLANNED,ASSEMBLING,WORKING,SETTLING,COMPLETED,CANCELLED,REVIEW;
        public boolean closed(){return this==COMPLETED||this==CANCELLED||this==REVIEW;}}
    public ChurchActivity {
        Objects.requireNonNull(id);Objects.requireNonNull(temple);Objects.requireNonNull(kind);
        Objects.requireNonNull(venue);Objects.requireNonNull(stage);Objects.requireNonNull(detail);
        invited=List.copyOf(invited);consent=Set.copyOf(consent);venue=venue.immutable();
        if(invited.size()>16||new HashSet<>(invited).size()!=invited.size()||!invited.containsAll(consent)
                ||minimum<0||minimum>invited.size()||scheduled<0||deadline<=scheduled||duration<20||duration>24000
                ||work<0||work>duration||attempts<0||retryAt<0||updated<0||detail.length()>512
                ||(stage!=Stage.PLANNED&&stage!=Stage.CANCELLED&&host==null)
                ||((stage==Stage.SETTLING||stage==Stage.COMPLETED)&&work!=duration)
                ||(kind==Kind.COPY||kind==Kind.LESSON)&&source==null
                ||(kind==Kind.VISIT||kind==Kind.FUNERAL)&&subject==null
                ||kind==Kind.MARRIAGE&&(invited.size()!=2||minimum!=2)
                ||kind==Kind.VISIT&&(!invited.contains(subject)||minimum!=1))
            throw new IllegalArgumentException("invalid church activity");
    }
    public boolean sameIntent(ChurchActivity a){return id.equals(a.id)&&temple.equals(a.temple)&&kind==a.kind
            &&venue.equals(a.venue)&&Objects.equals(subject,a.subject)&&Objects.equals(source,a.source)
            &&invited.equals(a.invited)&&minimum==a.minimum&&scheduled==a.scheduled&&deadline==a.deadline&&duration==a.duration;}
    public ChurchActivity accept(UUID person){if(stage.closed()||stage==Stage.SETTLING)throw new IllegalStateException("activity already settled");
        if(!invited.contains(person))throw new IllegalStateException("no invitation");var set=new HashSet<>(consent);set.add(person);return copy(host,stage,work,attempts,retryAt,updated,detail,set);}
    public ChurchActivity decline(UUID person){if(stage.closed()||stage==Stage.SETTLING)throw new IllegalStateException("activity already settled");if(!invited.contains(person))throw new IllegalStateException("not an invited person");var set=new HashSet<>(consent);set.remove(person);return copy(host,stage,work,attempts,retryAt,updated,"本人明确撤回同意",set);}
    public ChurchActivity claim(UUID actor,long now){if(host!=null&&!host.equals(actor))throw new IllegalStateException("different host");return copy(actor,Stage.ASSEMBLING,work,attempts,now,now,"已领取具体职责，等待真实到场",consent);}
    public ChurchActivity assign(UUID actor){if(stage!=Stage.PLANNED||host!=null&&!host.equals(actor))throw new IllegalStateException("host assignment must precede activity");return copy(actor,stage,work,attempts,retryAt,updated,detail,consent);}
    public ChurchActivity progress(long now){return copy(host,Stage.WORKING,Math.min(duration,work+1),attempts,now,now,"到场、职权和设施核验通过，正在作业",consent);}
    public ChurchActivity state(Stage next,long now,String reason){return copy(host,next,work,attempts,retryAt,now,reason,consent);}
    public ChurchActivity defer(long now,String reason){return copy(host,Stage.ASSEMBLING,work,attempts+1,now+100,now,reason,consent);}
    private ChurchActivity copy(UUID actor,Stage next,int done,int tries,long retry,long now,String why,Set<UUID> accepted){return new ChurchActivity(id,temple,kind,venue,subject,source,invited,accepted,minimum,scheduled,deadline,duration,actor,next,done,tries,retry,now,why);}
    public String token(){return "church-life:"+id;}
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putUUID("Temple",temple);t.putString("Kind",kind.name());t.putLong("Venue",venue.asLong());
        if(subject!=null)t.putUUID("Subject",subject);if(source!=null)t.putUUID("Source",source);if(host!=null)t.putUUID("Host",host);
        t.put("Invited",people(invited));t.put("Consent",people(consent));t.putInt("Minimum",minimum);t.putLong("Scheduled",scheduled);t.putLong("Deadline",deadline);t.putInt("Duration",duration);
        t.putString("Stage",stage.name());t.putInt("Work",work);t.putInt("Attempts",attempts);t.putLong("Retry",retryAt);t.putLong("Updated",updated);t.putString("Detail",detail);return t;}
    static ListTag people(Collection<UUID> ids){var l=new ListTag();for(var id:ids){var t=new CompoundTag();t.putUUID("Person",id);l.add(t);}return l;}
    static List<UUID> people(CompoundTag t,String key){var ids=new ArrayList<UUID>();for(var e:ChurchLifeData.list(t,key,16)){ids.add(ChurchLifeData.uuid((CompoundTag)e,"Person"));}if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("duplicate person");return ids;}
    public static ChurchActivity load(CompoundTag t){for(var n:List.of("Kind","Stage","Detail"))ChurchLifeData.require(t,n,Tag.TAG_STRING);
        for(var n:List.of("Venue","Scheduled","Deadline","Retry","Updated"))ChurchLifeData.require(t,n,Tag.TAG_LONG);
        for(var n:List.of("Minimum","Duration","Work","Attempts"))ChurchLifeData.require(t,n,Tag.TAG_INT);
        return new ChurchActivity(ChurchLifeData.uuid(t,"Id"),ChurchLifeData.uuid(t,"Temple"),Kind.valueOf(t.getString("Kind")),BlockPos.of(t.getLong("Venue")),optional(t,"Subject"),optional(t,"Source"),people(t,"Invited"),Set.copyOf(people(t,"Consent")),t.getInt("Minimum"),t.getLong("Scheduled"),t.getLong("Deadline"),t.getInt("Duration"),optional(t,"Host"),Stage.valueOf(t.getString("Stage")),t.getInt("Work"),t.getInt("Attempts"),t.getLong("Retry"),t.getLong("Updated"),t.getString("Detail"));}
    static UUID optional(CompoundTag t,String key){return t.contains(key)?ChurchLifeData.uuid(t,key):null;}
}
