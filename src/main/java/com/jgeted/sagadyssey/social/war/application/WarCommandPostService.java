package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.domain.WarCommandPost;
import com.jgeted.sagadyssey.social.war.spi.WarCommandPostPorts.*;
import java.util.*;

/** Publish only acknowledged references; a pending replacement makes the old post unavailable too. */
public final class WarCommandPostService implements AutoCloseable {
    private final Journal journal;
    private final Map<UUID,WarCommandPost> desired=new HashMap<>(),committed=new HashMap<>();
    private final LinkedHashSet<UUID> pending=new LinkedHashSet<>();
    private final Map<UUID,String> failed=new HashMap<>();
    public WarCommandPostService(Journal journal){
        this.journal=Objects.requireNonNull(journal);
        for(var v:journal.recover()){if(desired.putIfAbsent(v.unit(),v)!=null)throw new IllegalArgumentException("duplicate command post");committed.put(v.unit(),v);}
    }
    public Optional<WarCommandPost> desired(UUID unit){return Optional.ofNullable(desired.get(unit));}
    public Collection<WarCommandPost> records(){return List.copyOf(desired.values());}
    public Reply view(UUID unit){
        if(failed.containsKey(unit))return new Reply(Status.UNKNOWN,Optional.empty(),failed.get(unit));
        return pending.contains(unit)?new Reply(Status.PENDING,Optional.empty(),"POST_NOT_YET_DURABLE"):new Reply(committed.containsKey(unit)?Status.APPLIED:Status.UNAVAILABLE,Optional.ofNullable(committed.get(unit)),"");
    }
    public Reply bind(WarCommandPost value){
        var old=desired.get(value.unit());
        if(failed.containsKey(value.unit()))return view(value.unit());
        if(old!=null && !old.equals(value) && (pending.contains(value.unit()) || !WarCommandPost.follows(old,value)))return new Reply(Status.UNAVAILABLE,Optional.empty(),"POST_CHANGE_IN_PROGRESS_OR_REVISION_CONFLICT");
        if(old==null && value.revision()!=1)return new Reply(Status.UNKNOWN,Optional.empty(),"POST_INITIAL_REVISION_REQUIRED");
        var result=journal.persist(value);
        if(result.status()==Status.UNAVAILABLE)return result;
        desired.put(value.unit(),value);accept(value,result);return view(value.unit());
    }
    private void accept(WarCommandPost post,Reply result){
        if(result.status()==Status.APPLIED && result.durable().filter(post::equals).isPresent()){committed.put(post.unit(),post);pending.remove(post.unit());}
        else if(result.status()==Status.PENDING)pending.add(post.unit());
        else {failed.put(post.unit(),result.reason().isEmpty()?"POST_WRITE_REQUIRES_REVIEW":result.reason());pending.remove(post.unit());}
    }
    public void tick(){for(int n=Math.min(4,pending.size());n>0;n--){var iterator=pending.iterator();var id=iterator.next();iterator.remove();accept(desired.get(id),journal.persist(desired.get(id)));}}
    public void close(){journal.close();}
}
