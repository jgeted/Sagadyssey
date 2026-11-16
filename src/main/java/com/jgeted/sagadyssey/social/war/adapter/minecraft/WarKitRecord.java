package com.jgeted.sagadyssey.social.war.adapter.minecraft;

import com.jgeted.sagadyssey.social.war.api.WarMilitiaRequest;
import com.jgeted.sagadyssey.worldsim.rights.ItemFingerprint;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import java.util.*;

/** 兵装借用清单和实物请求；不保存物品副本。 */
public record WarKitRecord(WarMilitiaRequest request, UUID body, ItemLocation armory, List<Line> lines,
                           List<PhysicalItemOperation.Intent> issues, List<PhysicalItemOperation.Intent> returns) {
    public record Line(ItemFingerprint item, int quantity) {
        public Line { Objects.requireNonNull(item); if (quantity < 1 || quantity > 64 || item.itemId().length() > 256) throw new IllegalArgumentException("invalid kit line"); }
    }
    public WarKitRecord {
        Objects.requireNonNull(request); Objects.requireNonNull(body); Objects.requireNonNull(armory); lines = List.copyOf(lines); issues = List.copyOf(issues); returns = List.copyOf(returns);
        if (armory.kind() != ItemLocation.Kind.BLOCK && armory.kind() != ItemLocation.Kind.PLAYER || lines.size() > 6 || lines.stream().map(Line::item).distinct().count() != lines.size()
                || lines.isEmpty() && (!issues.isEmpty() || !returns.isEmpty())
                || armory.kind() == ItemLocation.Kind.PLAYER && !armory.character().equals(request.actorId())
                || !returns.isEmpty() && issues.isEmpty() || issues.size() > 32 || returns.size() > 32) throw new IllegalArgumentException("invalid kit plan");
        for (int i = 0; i < issues.size(); i++) validate(request, body, armory, lines, issues.get(i), false, i);
        for (int i = 0; i < returns.size(); i++) validate(request, body, armory, lines, returns.get(i), true, i);
    }
    public Optional<PhysicalItemOperation.Intent> issue() { return issues.isEmpty() ? Optional.empty() : Optional.of(issues.getLast()); }
    public Optional<PhysicalItemOperation.Intent> returned() { return returns.isEmpty() ? Optional.empty() : Optional.of(returns.getLast()); }
    public static UUID attemptId(UUID contract, boolean returning, int sequence) {
        var stage = returning ? com.jgeted.sagadyssey.social.war.domain.WarMobilization.Stage.RETURN_EQUIPMENT : com.jgeted.sagadyssey.social.war.domain.WarMobilization.Stage.EQUIP;
        return UUID.nameUUIDFromBytes((com.jgeted.sagadyssey.social.war.domain.WarMobilization.operationId(contract, stage) + "/attempt/" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void validate(WarMilitiaRequest request, UUID body, ItemLocation armory, List<Line> lines, PhysicalItemOperation.Intent intent, boolean returning, int sequence) {
        var transfer = intent.transfer().orElseThrow(); var equipment = ItemLocation.equipment(request.characterId(), body);
        var expected = lines.stream().map(line -> new TransferLeg(returning ? equipment.id() : armory.id(), returning ? armory.id() : equipment.id(), line.item(), line.quantity())).toList();
        if (!intent.id().equals(attemptId(request.id(), returning, sequence)) || !transfer.legs().equals(expected)
                || transfer.intent() != (returning ? TransferIntent.RETURN : TransferIntent.ISSUE)
                || !transfer.initiatedBy().equals(com.jgeted.sagadyssey.worldsim.rights.SubjectRef.character(request.actorId()))
                || !transfer.sourceAuthorities().equals(returning ? Map.of(equipment.id(), com.jgeted.sagadyssey.worldsim.rights.SubjectRef.character(request.characterId())) : Map.of())) throw new IllegalArgumentException("foreign kit item intent");
    }
    public static boolean follows(WarKitRecord old, WarKitRecord next) {
        return old.request.equals(next.request) && old.body.equals(next.body) && old.armory.equals(next.armory) && old.lines.equals(next.lines)
                && prefix(old.issues, next.issues) && prefix(old.returns, next.returns);
    }
    private static boolean prefix(List<?> old, List<?> next) { return next.size() >= old.size() && next.size() <= old.size() + 1 && next.subList(0, old.size()).equals(old); }
}
