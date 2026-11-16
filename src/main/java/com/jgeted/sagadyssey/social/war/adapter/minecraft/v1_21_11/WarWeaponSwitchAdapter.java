package com.jgeted.sagadyssey.social.war.adapter.minecraft.v1_21_11;

import com.jgeted.sagadyssey.core.MinecraftApi;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.npc.entity.NpcBase;
import com.jgeted.sagadyssey.npc.inventory.NpcEquipmentInventory;
import com.jgeted.sagadyssey.social.character.CharacterRegistryData;
import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.*;
import com.jgeted.sagadyssey.social.work.*;
import com.jgeted.sagadyssey.worldsim.rights.*;
import com.jgeted.sagadyssey.worldsim.transfer.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import java.io.IOException;
import java.util.*;

/** 原身体上的最后一次换装游标；真正物理效果仍只有共享实物服务一个提交者。 */
public final class WarWeaponSwitchAdapter {
    public static final String KEY = "SagadysseyWarWeaponSwitchV1";
    private static final int HAND = NpcEquipmentInventory.SLOTS.indexOf(EquipmentSlot.MAINHAND);
    private static final PhysicalItemOperationCodec CODEC = new PhysicalItemOperationCodec();
    private final Map<NpcBase, Memo> cache = new WeakHashMap<>();
    private record Title(SubjectRef owner, SubjectRef custodian, Set<String> encumbrances, Set<String> reservations, String provenance) {
        static Title at(RightsStateData rights, UUID container, int slot, ItemStack item) {
            var context = rights.context(container); if (context == null) throw new IllegalStateException("original rights context missing");
            var claims = rights.claimsAt(container, slot, ItemFingerprint.of(item));
            if (claims.size() > 1) throw new IllegalStateException("ambiguous asset title");
            if (claims.isEmpty()) return new Title(context.defaultBeneficialOwner(), context.custodian(), Set.of(), Set.of(), "container_default");
            var c = claims.getFirst(); if (c.quantity() != item.getCount()) throw new IllegalStateException("asset quantity mismatch");
            // Shared relocation collapses unencumbered container-owned claims to defaults.
            // Loan/issue provenance and every reservation remain exact.
            if (c.beneficialOwner().equals(context.defaultBeneficialOwner()) && c.custodian().equals(context.custodian())
                    && c.encumbrances().isEmpty() && c.reservations().isEmpty())
                return new Title(c.beneficialOwner(), c.custodian(), Set.of(), Set.of(), "container_default");
            return new Title(c.beneficialOwner(), c.custodian(), c.encumbrances(), c.reservations(), c.provenance());
        }
        CompoundTag save() {
            var t = new CompoundTag(); t.put("Owner", owner.save()); t.put("Custodian", custodian.save()); t.put("Encumbrances", strings(encumbrances)); t.put("Reservations", strings(reservations)); t.putString("Provenance", provenance); return t;
        }
        static Title load(CompoundTag t) {
            fields(t, Set.of("Owner", "Custodian", "Encumbrances", "Reservations", "Provenance"));
            if (!(t.get("Owner") instanceof CompoundTag o) || !(t.get("Custodian") instanceof CompoundTag c) || !MinecraftApi.containsTag(t, "Provenance", Tag.TAG_STRING)) throw new IllegalArgumentException("invalid title");
            fields(o, Set.of("Type", "Id")); fields(c, Set.of("Type", "Id"));
            return new Title(SubjectRef.load(o), SubjectRef.load(c), strings(t, "Encumbrances"), strings(t, "Reservations"), t.getStringOr("Provenance", ""));
        }
    }
    private record Cursor(UUID contract, UUID body, UUID character, WarAttackKind primary, ItemFingerprint item, Title title,
                          int slot, WarAttackKind desired, PhysicalItemOperation.Intent intent, boolean done) {
        CompoundTag save() {
            var t = new CompoundTag(); t.store("Contract", UUIDUtil.CODEC, contract); t.store("Body", UUIDUtil.CODEC, body); t.store("Character", UUIDUtil.CODEC, character); t.putString("Primary", primary.name()); t.put("Item", item.save()); t.put("Title", title.save());
            t.putInt("Slot", slot); t.putString("Desired", desired.name()); t.putBoolean("Done", done);
            try { t.putByteArray("Intent", CODEC.write(List.of(new PhysicalItemOperation(intent, PhysicalItemOperation.Phase.INTENT, Optional.empty())))); }
            catch (IOException e) { throw new IllegalArgumentException(e); } return t;
        }
        static Cursor load(CompoundTag t) {
            fields(t, Set.of("Contract", "Body", "Character", "Primary", "Item", "Title", "Slot", "Desired", "Done", "Intent"));
            for (String key : List.of("Contract", "Body", "Character")) if (!t.read(key, UUIDUtil.CODEC).isPresent()) throw new IllegalArgumentException("invalid switch identity");
            if (!MinecraftApi.containsTag(t, "Slot", Tag.TAG_INT) || t.getIntOr("Slot", 0) < 0 || t.getIntOr("Slot", 0) > 4095 || !MinecraftApi.containsTag(t, "Done", Tag.TAG_BYTE) || t.getByteOr("Done", (byte)0) < 0 || t.getByteOr("Done", (byte)0) > 1
                    || !MinecraftApi.containsTag(t, "Intent", Tag.TAG_BYTE_ARRAY) || t.getByteArray("Intent").orElse(new byte[0]).length > 16_384 || !(t.get("Item") instanceof CompoundTag item) || !(t.get("Title") instanceof CompoundTag title)
                    || !MinecraftApi.containsTag(t, "Primary", Tag.TAG_STRING) || !MinecraftApi.containsTag(t, "Desired", Tag.TAG_STRING)) throw new IllegalArgumentException("invalid switch cursor");
            fields(item, Set.of("Item", "Components"));
            try {
                var records = List.copyOf(CODEC.read(t.getByteArray("Intent").orElse(new byte[0]))); if (records.size() != 1 || records.getFirst().phase() != PhysicalItemOperation.Phase.INTENT) throw new IllegalArgumentException("invalid switch effect");
                return new Cursor(t.read("Contract", UUIDUtil.CODEC).orElseThrow(), t.read("Body", UUIDUtil.CODEC).orElseThrow(), t.read("Character", UUIDUtil.CODEC).orElseThrow(), WarAttackKind.valueOf(t.getStringOr("Primary", "")), ItemFingerprint.load(item), Title.load(title), t.getIntOr("Slot", 0),
                        WarAttackKind.valueOf(t.getStringOr("Desired", "")), records.getFirst().intent(), t.getBooleanOr("Done", false));
            } catch (IOException e) { throw new IllegalArgumentException(e); }
        }
    }
    private static final class Memo {
        final CompoundTag snapshot; final Cursor cursor;
        boolean verified, denied;
        Memo(CompoundTag tag, Cursor value) { snapshot = tag.copy(); cursor = value; }
    }
    private Memo read(NpcBase npc) {
        var raw = npc.getPersistentData().get(KEY); if (raw == null) return null;
        if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("invalid switch data");
        var memo = cache.get(npc); if (memo != null && memo.snapshot.equals(tag)) return memo;
        var cursor = Cursor.load(tag); validate(npc, cursor); memo = new Memo(tag, cursor); cache.put(npc, memo); return memo;
    }
    private Memo store(NpcBase npc, Cursor cursor) { var tag = cursor.save(); npc.getPersistentData().put(KEY, tag); var memo = new Memo(tag, cursor); cache.put(npc, memo); return memo; }
    private static void validate(NpcBase npc, Cursor c) {
        original(npc);
        if (!c.body.equals(npc.getUUID()) || !c.character.equals(npc.getCharacterId()) || !supportedPrimary(c.primary)
                || c.desired != c.primary && c.desired != WarAttackKind.SWORD_STRIKE || c.slot >= npc.getPersonalInventory().getContainerSize()) throw new IllegalArgumentException("foreign switch cursor");
        var r = c.intent.transfer().orElseThrow(); UUID bag = ContainerIds.npcPersonal(c.character), gear = ContainerIds.named("npc_equipment:" + c.character);
        if (r.intent() != TransferIntent.RELOCATE || !r.initiatedBy().equals(SubjectRef.character(c.character)) || !r.sourceAuthorities().isEmpty()
                || !r.authority().equals("war-switch:" + c.contract) || r.legs().size() < 1 || r.legs().size() > 2) throw new IllegalArgumentException("foreign switch transfer");
        var incoming = r.legs().getLast();
        if (!incoming.sourceContainerId().equals(bag) || !incoming.destinationContainerId().equals(gear) || incoming.sourceSlot() != c.slot || incoming.destinationSlot() != HAND || incoming.quantity() != 1
                || c.desired == c.primary && !incoming.fingerprint().equals(c.item)) throw new IllegalArgumentException("invalid incoming weapon");
        if (r.legs().size() == 2) {
            var outgoing = r.legs().getFirst();
            if (!outgoing.sourceContainerId().equals(gear) || !outgoing.destinationContainerId().equals(bag) || outgoing.sourceSlot() != HAND || outgoing.destinationSlot() != c.slot || outgoing.quantity() != 1
                    || c.desired != c.primary && !outgoing.fingerprint().equals(c.item)) throw new IllegalArgumentException("invalid outgoing weapon");
        }
    }
    private boolean usable(NpcBase npc, Memo memo, UUID contract, WarAttackKind primary) {
        if (memo == null) return false;
        if (memo.cursor.contract.equals(contract)) {
            if (memo.cursor.primary != primary) throw new IllegalArgumentException("changed primary role"); return true;
        }
        if (!(memo.verified && memo.cursor.done || memo.denied) || WorkCommitmentRuntime.existing(npc.getServer()).flatMap(w -> w.find(memo.cursor.contract)).filter(w -> w.phase() == WorkCommitment.Phase.RELEASED).isEmpty())
            throw new IllegalArgumentException("prior military service not released");
        return false;
    }
    public Arms arms(NpcBase npc, UUID contract, WarAttackKind primary) {
        var held = WarMinecraft12111.inspect(npc.getMainHandItem());
        try {
            var memo = read(npc); if (memo != null && !memo.verified && !memo.denied) return new Arms(held, held.ranged(), false, ChangeStatus.PENDING, "WEAPON_TRANSFER_CONFIRMATION_REQUIRED");
            if (!usable(npc, memo, contract, primary)) {
                if (supportedPrimary(primary) && !matchesPrimary(held, primary)
                        && initialSlot(npc, primary) >= 0)
                    return new Arms(held, Optional.empty(), false, ChangeStatus.PENDING, "SELF_OWNED_PRIMARY_EQUIP_REQUIRED");
                return new Arms(held, held.ranged().filter(p -> p.kind() == primary), false, ChangeStatus.READY, "");
            }
            var c = memo.cursor;
            if (c.item.matches(npc.getMainHandItem()) && c.title.equals(Title.at(RightsStateData.get(npc.getServer()), WitnessedItemEndpoint.equipment(npc).id(), HAND, npc.getMainHandItem())))
                return new Arms(held, held.ranged(), false, ChangeStatus.READY, "");
            var stowed = npc.getPersonalInventory().getItem(c.slot);
            if (!c.item.matches(stowed) || !c.title.equals(Title.at(RightsStateData.get(npc.getServer()), WitnessedItemEndpoint.personal(npc).id(), c.slot, stowed))) throw new IllegalStateException("original primary source changed");
            return new Arms(held, WarMinecraft12111.inspect(stowed).ranged().filter(p -> p.kind() == primary), held.melee().filter(p -> p.kind() == WarAttackKind.SWORD_STRIKE).isPresent(), ChangeStatus.READY, "");
        } catch (RuntimeException e) { return new Arms(held, Optional.empty(), false, ChangeStatus.REVIEW, "ORIGINAL_WEAPON_SOURCE_REQUIRES_REVIEW"); }
    }
    public WeaponChange equip(NpcBase npc, UUID contract, WarAttackKind primary, WarAttackKind desired) {
        try {
            original(npc);
            var memo = read(npc);
            if (memo != null && !memo.verified && !memo.denied) {
                var progress = advance(npc, memo); return progress.status() == ChangeStatus.READY ? new WeaponChange(ChangeStatus.PENDING, "WEAPON_TRANSFER_CONFIRMED_RECHECK_ROLE") : progress;
            }
            if (!supportedPrimary(primary)) return new WeaponChange(ChangeStatus.BLOCKED, "WEAPON_ROLE_UNAVAILABLE");
            boolean own = usable(npc, memo, contract, primary);
            var held = npc.getMainHandItem(); var heldProfile = WarMinecraft12111.inspect(held);
            if (desired == primary && matchesPrimary(heldProfile, primary) && (!own || memo.cursor.item.matches(held) && memo.cursor.title.equals(Title.at(RightsStateData.get(npc.getServer()), WitnessedItemEndpoint.equipment(npc).id(), HAND, held)))) return new WeaponChange(ChangeStatus.READY, "PRIMARY_WEAPON_READY");
            if (desired == WarAttackKind.SWORD_STRIKE && own && heldProfile.melee().filter(p -> p.kind() == desired).isPresent()) return new WeaponChange(ChangeStatus.READY, "BACKUP_WEAPON_READY");
            if (desired != primary && desired != WarAttackKind.SWORD_STRIKE) return new WeaponChange(ChangeStatus.BLOCKED, "WEAPON_ROLE_UNAVAILABLE");
            var rights = RightsStateData.get(npc.getServer()); var bag = WitnessedItemEndpoint.personal(npc); var gear = WitnessedItemEndpoint.equipment(npc); var person = SubjectRef.character(npc.getCharacterId());
            if (rights.context(bag.id()) == null || rights.context(gear.id()) == null) return new WeaponChange(ChangeStatus.BLOCKED, "ORIGINAL_WEAPON_RIGHTS_CONTEXT_REQUIRED");
            if (memo != null && memo.denied && memo.cursor.intent.transfer().orElseThrow().expectedRevisions().stream().allMatch(r -> rights.context(r.containerId()).revision() == r.revision())) return new WeaponChange(ChangeStatus.BLOCKED, "WEAPON_TRANSFER_PROVED_DENIED");
            if (!held.isEmpty() && held.getCount() != 1) return new WeaponChange(ChangeStatus.REVIEW, "ORIGINAL_PRIMARY_WEAPON_REQUIRED");
            // First arming uses the same witnessed relocation as later weapon changes.
            // No civilian command, profession, ownership or inventory slot is rewritten directly.
            boolean initial = !own && !matchesPrimary(heldProfile, primary);
            if (initial && desired != primary) return new WeaponChange(ChangeStatus.REVIEW, "ORIGINAL_PRIMARY_WEAPON_REQUIRED");
            int initialSlot = initial ? initialSlot(npc, primary) : -1;
            if (initial && initialSlot < 0) return new WeaponChange(ChangeStatus.BLOCKED, "ACTUAL_SELF_OWNED_PRIMARY_REQUIRED");
            ItemFingerprint original = own ? memo.cursor.item : ItemFingerprint.of(initial ? bag.container().getItem(initialSlot) : held);
            Title title = own ? memo.cursor.title : Title.at(rights, initial ? bag.id() : gear.id(), initial ? initialSlot : HAND, initial ? bag.container().getItem(initialSlot) : held);
            int slot = -1;
            for (int i = 0; i < bag.container().getContainerSize(); i++) {
                var stack = bag.container().getItem(i); if (stack.isEmpty() || stack.getCount() != 1) continue;
                var p = WarMinecraft12111.inspect(stack);
                if (desired == primary ? !original.matches(stack) || !title.equals(Title.at(rights, bag.id(), i, stack)) || !matchesPrimary(p, primary)
                        : p.melee().filter(m -> m.kind() == WarAttackKind.SWORD_STRIKE).isEmpty()) continue;
                slot = i; break;
            }
            if (slot < 0) return new WeaponChange(ChangeStatus.BLOCKED, desired == primary ? "ORIGINAL_PRIMARY_WEAPON_UNAVAILABLE" : "ACTUAL_BACKUP_SWORD_REQUIRED");
            var legs = new ArrayList<TransferLeg>();
            if (!held.isEmpty()) legs.add(new TransferLeg(gear.id(), bag.id(), ItemFingerprint.of(held), 1, HAND, slot));
            legs.add(new TransferLeg(bag.id(), gear.id(), ItemFingerprint.of(bag.container().getItem(slot)), 1, slot, HAND));
            var intent = PhysicalItemOperation.Intent.transfer(new TransferRequest(UUID.randomUUID(), person, TransferIntent.RELOCATE, legs,
                    List.of(new ExpectedRevision(bag.id(), rights.context(bag.id()).revision()), new ExpectedRevision(gear.id(), rights.context(gear.id()).revision())), "war-switch:" + contract, npc.level().getGameTime()));
            var c = new Cursor(contract, npc.getUUID(), npc.getCharacterId(), primary, original, title, slot, desired, intent, false); validate(npc, c); store(npc, c);
            return new WeaponChange(ChangeStatus.PENDING, "WEAPON_TRANSFER_INTENT_RECORDED");
        } catch (RuntimeException e) { return new WeaponChange(ChangeStatus.REVIEW, "WEAPON_TRANSFER_REQUIRES_REVIEW"); }
    }
    private static boolean supportedPrimary(WarAttackKind kind) {
        return kind == WarAttackKind.SWORD_STRIKE || kind == WarAttackKind.SPEAR_JAB
                || kind == WarAttackKind.BOW_SHOT || kind == WarAttackKind.CROSSBOW_SHOT;
    }
    private static boolean matchesPrimary(WarWeaponProfile profile, WarAttackKind kind) {
        return profile.melee().filter(p -> p.kind() == kind).isPresent() || profile.ranged().filter(p -> p.kind() == kind).isPresent();
    }
    private static int initialSlot(NpcBase npc, WarAttackKind primary) {
        var rights = RightsStateData.get(npc.getServer()); var bag = WitnessedItemEndpoint.personal(npc); var person = SubjectRef.character(npc.getCharacterId());
        if (rights.context(bag.id()) == null) return -1;
        for (int i = 0; i < bag.container().getContainerSize(); i++) {
            var stack = bag.container().getItem(i);
            if (stack.getCount() != 1 || !matchesPrimary(WarMinecraft12111.inspect(stack), primary)) continue;
            var title = Title.at(rights, bag.id(), i, stack);
            if (title.owner.equals(person) && title.custodian.equals(person) && title.encumbrances.isEmpty() && title.reservations.isEmpty()) return i;
        }
        return -1;
    }
    public void settle(NpcBase npc) { try { var memo = read(npc); if (memo != null && !memo.verified && !memo.denied) advance(npc, memo); } catch (RuntimeException ignored) { /* Invalid original evidence remains untouched and blocks equip/arms. */ } }
    private WeaponChange advance(NpcBase npc, Memo memo) {
        try {
            original(npc);
            var bag = WitnessedItemEndpoint.personal(npc); var gear = WitnessedItemEndpoint.equipment(npc);
            var reply = PhysicalItemRuntime.activate(npc.getServer()).submit(memo.cursor.intent, Map.of(bag.id(), bag, gear.id(), gear), npc.level().getGameTime());
            if (reply.status() == PhysicalItemService.Status.APPLIED) {
                var c = memo.cursor; var next = c.done ? memo : store(npc, new Cursor(c.contract, c.body, c.character, c.primary, c.item, c.title, c.slot, c.desired, c.intent, true));
                next.verified = true; return new WeaponChange(ChangeStatus.READY, "WEAPON_TRANSFER_APPLIED");
            }
            if (reply.status() == PhysicalItemService.Status.DENIED) { memo.denied = true; return new WeaponChange(ChangeStatus.BLOCKED, "WEAPON_TRANSFER_DENIED:" + reply.reason()); }
            return new WeaponChange(switch (reply.status()) { case PENDING, UNAVAILABLE -> ChangeStatus.PENDING; default -> ChangeStatus.REVIEW; }, "WEAPON_TRANSFER_" + reply.status() + ":" + reply.reason());
        } catch (IOException e) { return new WeaponChange(ChangeStatus.REVIEW, "WEAPON_PHYSICAL_SERVICE_UNAVAILABLE"); }
    }
    private static ListTag strings(Set<String> values) { var rows = new ListTag(); values.stream().sorted().forEach(v -> rows.add(StringTag.valueOf(v))); return rows; }
    private static Set<String> strings(CompoundTag t, String key) {
        if (!(t.get(key) instanceof ListTag rows) || rows.size() > 64 || !rows.isEmpty() && MinecraftApi.elementType(rows) != Tag.TAG_STRING) throw new IllegalArgumentException("invalid title set");
        var values = new HashSet<String>(); for (Tag row : rows) if (!values.add(row.asString().orElseThrow())) throw new IllegalArgumentException("duplicate title entry"); return Set.copyOf(values);
    }
    private static void fields(CompoundTag t, Set<String> names) { if (!t.keySet().equals(names)) throw new IllegalArgumentException("unknown switch fields"); }
    private static void original(NpcBase npc) {
        var characters = CharacterRegistryData.get(npc.getServer()); var binding = characters.bodyOf(npc.getCharacterId()); var character = characters.byId(npc.getCharacterId());
        if (!npc.isAlive() || binding == null || character == null || !character.alive() || !binding.entityId().equals(npc.getUUID())
                || !binding.dimensionId().equals(npc.level().dimension().identifier().toString())) throw new IllegalStateException("original registered weapon body required");
    }
}
