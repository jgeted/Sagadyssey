package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcSkinTone;
import net.minecraft.resources.Identifier;
import java.util.concurrent.ConcurrentHashMap;

/** Only exposed skin is palette-mapped; garments keep their existing textures. */
public final class NpcAppearanceTextures {
    private static final String ROOT="textures/entity/npc/";
    private static final ConcurrentHashMap<String,Identifier> CACHE=new ConcurrentHashMap<>();
    private NpcAppearanceTextures() {}

    public static Identifier body(NpcRenderState state) {
        return body(state.profession,state.skinTone);
    }
    public static Identifier body(com.jgeted.sagadyssey.npc.profession.NpcProfession profession,NpcSkinTone tone) {
        return skin(NpcRenderer.professionTexture(profession),tone);
    }
    public static Identifier face(NpcRenderState state) {
        return face(state.gender,state.face,
                new com.jgeted.sagadyssey.npc.appearance.NpcAppearanceProfile(state.headShape,state.hairStyle,state.skinTone));
    }
    public static Identifier face(com.jgeted.sagadyssey.npc.appearance.NpcGender gender,
            com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant face,
            com.jgeted.sagadyssey.npc.appearance.NpcAppearanceProfile appearance) {
        Identifier original=Identifier.fromNamespaceAndPath("sagadyssey",ROOT+"revision_v2/face/"
                +gender.id()+"_"+face.id()+".png");
        if(appearance.skin()==NpcSkinTone.ORIGINAL
                &&(appearance.head()!=com.jgeted.sagadyssey.npc.appearance.NpcHeadShape.CLASSIC
                    ||appearance.hair()!=com.jgeted.sagadyssey.npc.appearance.NpcHairStyle.CLASSIC))
            return derived(original,appearance.skin());
        return skin(original,appearance.skin());
    }
    private static Identifier skin(Identifier original,NpcSkinTone tone) {
        if (tone==NpcSkinTone.ORIGINAL) return original;
        return derived(original,tone);
    }
    private static Identifier derived(Identifier original,NpcSkinTone tone) {
        String file=ROOT+"appearance_v3/skin/"+tone.id()+"/"+original.getPath().substring(ROOT.length());
        return CACHE.computeIfAbsent(file,path->Identifier.fromNamespaceAndPath("sagadyssey",path));
    }
}
