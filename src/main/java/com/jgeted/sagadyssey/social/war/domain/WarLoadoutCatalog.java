package com.jgeted.sagadyssey.social.war.domain;

import com.jgeted.sagadyssey.social.war.api.WarKey;
import com.jgeted.sagadyssey.social.war.api.WarLoadout;

import java.util.*;

/** 48种配置共用行为；六个旧ID作为明确的默认配装保留，不重命名旧兵团或改实物。 */
public final class WarLoadoutCatalog {
    private final Map<WarKey, WarLoadout> profiles;
    private final Map<WarKey, WarKey> legacyAliases;

    private WarLoadoutCatalog(Map<WarKey, WarLoadout> profiles, Map<WarKey, WarKey> aliases) {
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        this.legacyAliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
    }
    public Map<WarKey, WarLoadout> profiles() { return profiles; }
    public Map<WarKey, WarKey> legacyAliases() { return legacyAliases; }
    public Optional<WarLoadout> find(WarKey id) { return Optional.ofNullable(profiles.get(legacyAliases.getOrDefault(id, id))); }

    public static WarLoadoutCatalog initial() {
        Map<WarKey, WarLoadout> profiles = new LinkedHashMap<>();
        for (var mount : WarLoadout.Mount.values()) for (var weapon : WarLoadout.Weapon.values()) {
            for (boolean shield : new boolean[]{false, true}) for (boolean armor : new boolean[]{false, true}) {
                var profile = new WarLoadout(mount, weapon, shield, armor); profiles.put(profile.id(), profile);
            }
        }
        Map<WarKey, WarKey> aliases = new LinkedHashMap<>();
        alias(aliases, "shield_infantry", WarLoadout.Mount.FOOT, WarLoadout.Weapon.SWORD, true, true);
        alias(aliases, "pike_infantry", WarLoadout.Mount.FOOT, WarLoadout.Weapon.SPEAR, false, false);
        alias(aliases, "archer", WarLoadout.Mount.FOOT, WarLoadout.Weapon.BOW, false, false);
        alias(aliases, "crossbowman", WarLoadout.Mount.FOOT, WarLoadout.Weapon.CROSSBOW, false, false);
        alias(aliases, "light_cavalry", WarLoadout.Mount.LIGHT, WarLoadout.Weapon.SWORD, false, false);
        alias(aliases, "heavy_cavalry", WarLoadout.Mount.HEAVY, WarLoadout.Weapon.SPEAR, true, true);
        return new WarLoadoutCatalog(profiles, aliases);
    }
    private static void alias(Map<WarKey, WarKey> aliases, String legacy, WarLoadout.Mount mount,
                              WarLoadout.Weapon weapon, boolean shield, boolean armor) {
        aliases.put(new WarKey("sagadyssey", "war/troop/" + legacy), new WarLoadout(mount, weapon, shield, armor).id());
    }
}
