package com.jgeted.sagadyssey.social.war.application;

import java.util.*;

/** Volatile execution evidence, never authority or payable time. Recovery must earn it again. */
public final class WarDutyClock {
    public record Job(UUID task,UUID service,UUID person,UUID body) {
        public Job { Objects.requireNonNull(task);Objects.requireNonNull(service);Objects.requireNonNull(person);Objects.requireNonNull(body); }
    }
    private static final class Progress {
        final Job job;long last=-1;int ticks;
        Progress(Job job){this.job=job;}
    }
    private final int required,limit;
    private final Map<UUID,Progress> tasks=new HashMap<>();
    private final Map<UUID,Job> services=new HashMap<>(),people=new HashMap<>(),bodies=new HashMap<>();
    public WarDutyClock(int required,int limit){if(required<1||limit<1)throw new IllegalArgumentException("positive duty bounds required");this.required=required;this.limit=limit;}
    public boolean reserve(Job job){
        Objects.requireNonNull(job);var old=tasks.get(job.task());if(old!=null)return old.job.equals(job);
        if(tasks.size()>=limit||services.containsKey(job.service())||people.containsKey(job.person())||bodies.containsKey(job.body()))return false;
        tasks.put(job.task(),new Progress(job));services.put(job.service(),job);people.put(job.person(),job);bodies.put(job.body(),job);return true;
    }
    public Optional<Job> service(UUID service){return Optional.ofNullable(services.get(service));}
    public boolean personReserved(UUID person){return people.containsKey(person);}
    public int observe(Job job,long now){
        var p=tasks.get(job.task());if(p==null||!p.job.equals(job)||now<0)return 0;
        if(now==p.last)return p.ticks; // Repeated native callbacks in one tick earn no additional work.
        if(now<p.last){p.last=-1;p.ticks=0;return 0;}
        p.ticks=p.last>=0&&now-p.last==1?Math.min(required,p.ticks+1):1;p.last=now;return p.ticks;
    }
    public boolean ready(UUID task,long now){var p=tasks.get(task);return p!=null&&now>=0&&p.last>=0&&p.last<=now&&now-p.last<=1&&p.ticks>=required;}
    public int progress(UUID task,long now){var p=tasks.get(task);return p!=null&&p.last>=0&&p.last<=now&&now-p.last<=1?p.ticks:0;}
    public void release(UUID task){var p=tasks.remove(task);if(p==null)return;services.remove(p.job.service(),p.job);people.remove(p.job.person(),p.job);bodies.remove(p.job.body(),p.job);}
    public void clear(){tasks.clear();services.clear();people.clear();bodies.clear();}
}
