package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcOutfitCatalog;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** 资源包可重绑阵营配色；肤色、脸、头发和真实装备不参与染色。 */
public final class NpcOutfitTextures extends SimplePreparableReloadListener<NpcOutfitCatalog> {
    private static volatile NpcOutfitCatalog catalog = NpcOutfitCatalog.bundled();
    private static final Identifier CONFIG = Identifier.fromNamespaceAndPath("sagadyssey", "npc_outfits.json");
    @Override protected NpcOutfitCatalog prepare(ResourceManager manager, ProfilerFiller profiler) {
        try (var reader = manager.openAsReader(CONFIG)) { return NpcOutfitCatalog.read(reader); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot reload outfit catalog", e); }
    }
    @Override protected void apply(NpcOutfitCatalog value, ResourceManager manager, ProfilerFiller profiler) { catalog = value; }
    public static String palette(NpcBase npc) { return catalog.palette(npc.getClothingFactionId(), npc.getClothingPaletteOverride()); }
    public static Identifier civilian(NpcBase npc) {
        return location(palette(npc), "civilian/" + npc.getSocialStatus().id() + "_" + npc.getGender().id());
    }
    public static Identifier military(NpcBase npc) {
        return location(palette(npc), militaryFile(npc.getClothingOutfit(), npc.getGender().id()));
    }
    public static String palette(NpcRenderState state) { return catalog.palette(state.clothingFaction, state.clothingPalette); }
    public static Identifier civilian(NpcRenderState state) { return location(palette(state), "civilian/" + state.socialStatus.id() + "_" + state.gender.id()); }
    public static Identifier military(NpcRenderState state) { return location(palette(state), militaryFile(state.clothingOutfit, state.gender.id())); }
    public static Identifier roleAccessories(NpcRenderState state) { return location(palette(state), "support/materials"); }
    private static String militaryFile(String outfit, String gender) {
        return (com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole.find(outfit).isPresent() ? "support/" : "military/")
                + outfit + "_" + gender;
    }
    private static Identifier location(String palette, String file) {
        return Identifier.fromNamespaceAndPath("sagadyssey", "textures/entity/npc/outfits/" + palette + "/" + file + ".png");
    }
}
