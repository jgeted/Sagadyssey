package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.core.persistence.DurableSnapshotStore;
import com.jgeted.sagadyssey.social.combat.CombatEncounter;
import com.jgeted.sagadyssey.social.combat.EncounterEvent;
import com.jgeted.sagadyssey.social.combat.EncounterEventType;
import com.jgeted.sagadyssey.social.war.adapter.persistence.WarLeadershipCodec;
import com.jgeted.sagadyssey.social.war.domain.WarLeadershipRecord;
import com.jgeted.sagadyssey.social.war.domain.WarLeadershipRecord.Specialty;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import com.jgeted.sagadyssey.social.war.feature.command.InitialWarDefinitions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.*;

/** Keeps optional leadership experience separate from the authoritative command, battle and NPC ledgers. */
final class WarLeadershipRuntime implements AutoCloseable {
    static final String FILE_NAME = "sagadyssey_war_leadership.sgw";
    record Assignment(UUID appointment, UUID officer, Specialty specialty, int points) {}
    private record Contact(UUID officer, Specialty specialty, UUID evidence) {}

    private final MinecraftServer server;
    private final DurableSnapshotStore<UUID, WarLeadershipRecord> store;
    private final Map<UUID, EnumMap<Specialty, Integer>> experience = new HashMap<>();
    private final Set<Contact> contacts = new HashSet<>();
    private final LinkedHashMap<UUID, WarLeadershipRecord> pending = new LinkedHashMap<>();
    private String problem = "";

    WarLeadershipRuntime(MinecraftServer server) {
        this.server = server;
        DurableSnapshotStore<UUID, WarLeadershipRecord> opened = null;
        try {
            opened = new DurableSnapshotStore<>(server.getWorldPath(LevelResource.ROOT).resolve("data/" + FILE_NAME),
                    new WarLeadershipCodec(), WarLeadershipRecord::order, WarLeadershipRecord::equals, "sagadyssey-war-leadership");
            for (var record : opened.recover()) {
                var contact = contact(record);
                if (contact.isPresent() && !contacts.add(contact.orElseThrow())) throw new IOException("duplicate credited contact");
                credit(record);
            }
        } catch (IOException | RuntimeException invalid) {
            if (opened != null) opened.close();
            opened = null;
            problem = "LEADERSHIP_REQUIRES_REVIEW:" + invalid.getClass().getSimpleName();
            experience.clear();
            contacts.clear();
        }
        store = opened;
    }

    Optional<Assignment> assignment(WarTransmission order) {
        if (store == null) return Optional.empty();
        var kind = order.command().intent().kind();
        Specialty specialty;
        if (kind.equals(InitialWarDefinitions.MOVE)) specialty = Specialty.MANEUVER;
        else if (kind.equals(InitialWarDefinitions.CHARGE)) specialty = Specialty.ASSAULT;
        else if (kind.equals(InitialWarDefinitions.HOLD)) specialty = Specialty.HOLD;
        else if (kind.equals(InitialWarDefinitions.RETREAT)) specialty = Specialty.WITHDRAW;
        else return Optional.empty();
        long received = order.received().orElseThrow().deliveredAtTick();
        return WarStaffRuntime.existing(server).flatMap(staff -> staff.fieldCaptain(order.command().armyId(), order.command().unitId()))
                .filter(a -> a.terms().startsAt() <= received && a.terms().owner().equals(order.command().actorId()))
                .filter(a -> order.members().stream().anyMatch(m -> m.character().equals(a.terms().holder()) && m.contract().equals(a.terms().service())))
                .map(a -> new Assignment(a.id(), a.terms().holder(), specialty, points(a.terms().holder(), specialty)));
    }

    int points(UUID officer, Specialty specialty) {
        var known = experience.get(officer);
        return known == null ? 0 : known.getOrDefault(specialty, 0);
    }

    void observe(WarTransmission order, Assignment assignment, long now, int arrived, boolean safeAtRally,
                 Collection<CombatEncounter> encounters) {
        int strength = order.members().size();
        UUID id = order.command().operationId();
        if (strength < 3 || assignment.points() >= WarLeadershipRecord.MAX_POINTS || store.desired(id).isPresent()) return;
        long received = order.received().orElseThrow().deliveredAtTick();
        if (assignment.specialty() == Specialty.MANEUVER && !meaningfulMove(order)) return;
        if (assignment.specialty() == Specialty.WITHDRAW && !safeAtRally) return;
        var evidence = assignment.specialty() == Specialty.MANEUVER ? Optional.<UUID>empty() : contactFor(order, assignment, encounters, now, received);
        if (!WarLeadershipRecord.qualifies(assignment.specialty(), now - received, strength, arrived, evidence.isPresent())) return;
        var value = new WarLeadershipRecord(id, order.command().armyId(), order.command().unitId(), assignment.appointment(),
                assignment.officer(), assignment.specialty(), received, now, strength, arrived, evidence);
        var persisted = store.persist(value);
        if (persisted.status() == DurableSnapshotStore.Status.PENDING || persisted.status() == DurableSnapshotStore.Status.APPLIED) {
            pending.put(id, value);
            contact(value).ifPresent(contacts::add);
        } else problem = "LEADERSHIP_REQUIRES_REVIEW:" + persisted.reason();
    }

    private Optional<UUID> contactFor(WarTransmission order, Assignment assignment, Collection<CombatEncounter> encounters, long now, long received) {
        var roster = new HashSet<UUID>();
        for (var member : order.members()) roster.add(member.character());
        long earliest = assignment.specialty() == Specialty.WITHDRAW ? Math.max(0, received - 400) : received;
        return encounters.stream().flatMap(e -> e.events().stream().filter(event -> {
                    if (!realContact(event, now, earliest) || assignment.specialty() == Specialty.WITHDRAW && event.occurredAt() > received) return false;
                    var first = e.sideOf(event.subjectCharacterId());
                    var second = e.sideOf(event.otherCharacterId());
                    if (first == null || second == null || first.id().equals(second.id())) return false;
                    return assignment.specialty() == Specialty.ASSAULT ? roster.contains(event.otherCharacterId()) : roster.contains(event.subjectCharacterId());
                }))
                .filter(event -> !contacts.contains(new Contact(assignment.officer(), assignment.specialty(), event.id())))
                .min(Comparator.comparingLong(EncounterEvent::occurredAt).thenComparing(EncounterEvent::id)).map(EncounterEvent::id);
    }

    private static boolean realContact(EncounterEvent event, long now, long earliest) {
        return event.type() == EncounterEventType.WOUNDED && event.sourceRecordId() != null && event.otherCharacterId() != null
                && event.occurredAt() >= earliest && event.occurredAt() <= now;
    }

    private static boolean meaningfulMove(WarTransmission order) {
        var before = order.route().reportedPosition();
        var after = order.command().intent().target();
        return Math.hypot((double) before.x() - after.x(), (double) before.z() - after.z()) >= 8;
    }

    void tick() {
        for (int i = 0; i < 4 && !pending.isEmpty(); i++) {
            var first = pending.firstEntry();
            var record = first.getValue();
            var saved = store.persist(record);
            if (saved.status() == DurableSnapshotStore.Status.PENDING) return;
            if (saved.durable().filter(record::equals).isEmpty()) {
                problem = "LEADERSHIP_REQUIRES_REVIEW:" + saved.reason();
                return;
            }
            pending.remove(first.getKey());
            credit(record);
        }
    }

    String summary(UUID person) {
        if (!problem.isEmpty()) return problem;
        var scores = new StringJoiner("，", "战场履历 ", "（各项满8点，按实际完成的原军令记录）");
        for (var specialty : Specialty.values()) scores.add(specialty.title() + " " + points(person, specialty));
        return scores.toString();
    }

    private static Optional<Contact> contact(WarLeadershipRecord value) {
        return value.contact().map(id -> new Contact(value.officer(), value.specialty(), id));
    }

    private void credit(WarLeadershipRecord value) {
        var scores = experience.computeIfAbsent(value.officer(), ignored -> new EnumMap<>(Specialty.class));
        scores.merge(value.specialty(), 1, (left, right) -> Math.min(WarLeadershipRecord.MAX_POINTS, left + right));
    }

    @Override public void close() { if (store != null) store.close(); }
}
