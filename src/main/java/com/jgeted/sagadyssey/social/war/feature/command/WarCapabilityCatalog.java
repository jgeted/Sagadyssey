package com.jgeted.sagadyssey.social.war.feature.command;

import com.jgeted.sagadyssey.social.war.api.*;
import com.jgeted.sagadyssey.social.war.spi.WarFieldPorts.Support;
import java.util.*;

/** M0之外的八个实现阶段。状态表示本构建实现程度，不代替世界绑定、权限或资格核验。 */
public final class WarCapabilityCatalog {
    public enum Stage { M1, M2, M3, M4, M5, M6, M7, M8 }
    public enum State { IMPLEMENTED, STUB, PLANNED }
    public record Entry(WarKey id, Stage stage, State state, String minimumMinecraft, String reason) {}
    private static final Map<WarKey, Entry> ENTRIES;
    static {
        var entries = new LinkedHashMap<WarKey, Entry>();
        add(entries, "core", Stage.M1, State.IMPLEMENTED, "", "PURE_JAVA_CORE");
        add(entries, "target_1_21_11", Stage.M1, State.IMPLEMENTED, "1.21.11", "NATIVE_1_21_11_BINDING");
        add(entries, "militia_slice", Stage.M2, State.IMPLEMENTED, "", "EXPLICIT_NATIVE_BINDINGS_REQUIRED");
        add(entries, "legacy_item_identity_migration", Stage.M2, State.STUB, "1.21.11", "LEGACY_COMPONENT_FINGERPRINT_MAPPING_UNVERIFIED");
        attack(entries, WarAttackKind.SWORD_STRIKE, Stage.M3, State.IMPLEMENTED, "", "NATIVE_SWORD_ADAPTER");
        attack(entries, WarAttackKind.SPEAR_JAB, Stage.M3, State.IMPLEMENTED, "1.21.11", "NATIVE_PIERCING_WEAPON_WITH_CURRENT_CONTACT_GUARD");
        attack(entries, WarAttackKind.SPEAR_CHARGE, Stage.M3, State.IMPLEMENTED, "1.21.11", "NATIVE_KINETIC_WEAPON_REAL_PREPARATION_AND_MOTION");
        attack(entries, WarAttackKind.BOW_SHOT, Stage.M3, State.IMPLEMENTED, "", "NATIVE_PLAIN_BOW_PROJECTILE_ADAPTER");
        attack(entries, WarAttackKind.CROSSBOW_SHOT, Stage.M3, State.IMPLEMENTED, "", "NATIVE_PLAIN_CROSSBOW_PROJECTILE_ADAPTER");
        add(entries, "weapon_slot_switching", Stage.M3, State.IMPLEMENTED, "", "SAME_CUSTODIAN_NATIVE_SWORD_BACKUP_EXCHANGE");
        add(entries, "organization_morale_retreat", Stage.M3, State.IMPLEMENTED, "", "DURABLE_ORGANIZATION_SHARED_MORALE_PHYSICAL_REGROUP_SLICE");
        attack(entries, WarAttackKind.MOUNTED_SPEAR_CHARGE, Stage.M4, State.IMPLEMENTED, "1.21.11", "NATIVE_KINETIC_WEAPON_DURABLE_CAVALRY_CONTACT");
        add(entries, "existing_mount_execution", Stage.M4, State.IMPLEMENTED, "1.21.11", "EXISTING_OWNED_HORSE_NATIVE_MOUNTED_AND_DISMOUNT_SLICE");
        add(entries, "physical_command_media", Stage.M4, State.IMPLEMENTED, "1.21.11", "EXISTING_ARMY_MAP_CLICK_FIXED_BANNER_NATIVE_HORN");
        add(entries, "sword_cavalry_cycle", Stage.M4, State.IMPLEMENTED, "1.21.11", "DURABLE_UNIT_REFORM_SINGLE_SWORD_CONTACT_AND_DISENGAGE");
        add(entries, "cavalry", Stage.M4, State.IMPLEMENTED, "1.21.11", "EXISTING_OWNED_MOUNTS_SWORD_AND_NATIVE_SPEAR_SORTIES");
        add(entries, "advanced_cavalry_tactics", Stage.M3, State.PLANNED, "", "FLANK_BREACH_TRAINING_AND_MIXED_UNIT_BALANCE_PENDING");
        add(entries, "horse_campaign_costs", Stage.M5, State.IMPLEMENTED, "1.21.11", "ACTUAL_PERIODIC_HAY_SHARED_WAGES_AND_ORIGINAL_MOUNT_RECEIPTS");
        add(entries, "command_map_panel", Stage.M4, State.IMPLEMENTED, "1.21.11", "HELD_MAP_OWN_REPORTS_BOUNDED_PREVIEW_AND_ONE_USE_CONFIRMATION");
        add(entries, "map_flag_horn", Stage.M4, State.IMPLEMENTED, "1.21.11", "ORDINARY_MUSTER_CONSENT_AUTHORITY_AND_NATIVE_MAP_BANNER_HORN");
        add(entries, "offices_and_logistics", Stage.M5, State.IMPLEMENTED, "1.21.11", "OPTIONAL_SCALED_OFFICES_EXPLICIT_STIPENDS_DEPOT_AND_BOUNDED_PHYSICAL_TRIPS");
        add(entries, "part_time_staffing", Stage.M5, State.IMPLEMENTED, "", "EXPLICIT_ESTABLISHMENT_SHARED_TERM_OFFICE_AND_OWN_REPORT_ADVICE");
        add(entries, "field_engineering", Stage.M6, State.IMPLEMENTED, "1.21.11", "BOUNDED_ORIGINAL_FIELDWORK_MATERIAL_TOOL_AND_RECOVERY");
        add(entries, "traps_and_tunnels", Stage.M7, State.IMPLEMENTED, "1.21.11", "NATIVE_TRAP_TEMPLATES_DESIGNATED_TUNNEL_AND_VISIBLE_COUNTERWORK");
        add(entries, "campaign_scale_and_far_field", Stage.M8, State.IMPLEMENTED, "1.21.11", "BOUNDED_NEAR_EXECUTION_UNLOADED_READINESS_AND_CONFIRMED_DISPATCH_ARCHIVE");
        ENTRIES = Collections.unmodifiableMap(entries);
    }
    private WarCapabilityCatalog() {}
    private static void add(Map<WarKey, Entry> entries, String path, Stage stage, State state, String version, String reason) {
        var key = new WarKey("sagadyssey", "war/capability/" + path); entries.put(key, new Entry(key, stage, state, version, reason));
    }
    private static void attack(Map<WarKey, Entry> entries, WarAttackKind kind, Stage stage, State state, String version, String reason) {
        entries.put(kind.key(), new Entry(kind.key(), stage, state, version, reason));
    }
    public static Collection<Entry> entries() { return ENTRIES.values(); }
    public static Support attack(WarAttackKind kind) {
        var entry = ENTRIES.get(Objects.requireNonNull(kind).key());
        return new Support(entry != null && entry.state() == State.IMPLEMENTED, entry == null ? "UNKNOWN_CAPABILITY" : entry.reason());
    }
}
