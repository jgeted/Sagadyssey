package com.jgeted.sagadyssey.npc.client;

import com.google.gson.Gson;
import com.jgeted.sagadyssey.npc.appearance.NpcMilitaryRole;
import com.jgeted.sagadyssey.npc.entity.NpcBase;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BannerItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;

/** Observes synchronized service clothing on a normal client's real entity renderer. */
public final class NpcCampaignAppearanceGameTests {
    private NpcCampaignAppearanceGameTests() {}

    public static boolean capture(Minecraft client, NpcBase npc, String outfit, Path evidence) throws Exception {
        if (!npc.getClothingOutfit().equals(outfit)) return false;
        if (!(client.getEntityRenderDispatcher().getRenderer(npc) instanceof NpcRenderer renderer))
            throw new IllegalStateException("native NPC renderer unavailable");
        var state = renderer.createRenderState();
        renderer.extractRenderState(npc,state,0);
        renderer.getModel().setupAnim(state);
        var result = new LinkedHashMap<String,Object>();
        result.put("body",npc.getUUID()); result.put("character",npc.getCharacterId());
        result.put("outfit",outfit); result.put("faction",state.clothingFaction);
        result.put("head",state.headShape); result.put("hair",state.hairStyle); result.put("skin",state.skinTone);
        result.put("mainArm",state.mainArm.toString()); result.put("mounted",state.mounted);
        if(state.militaryClothing && client.getResourceManager().getResource(NpcOutfitTextures.military(state)).isEmpty())
            throw new IllegalStateException("synchronized clothing texture unavailable");
        if(NpcMilitaryRole.find(outfit).isPresent() && client.getResourceManager().getResource(NpcOutfitTextures.roleAccessories(state)).isEmpty())
            throw new IllegalStateException("synchronized role accessories unavailable");
        if(outfit.equals(NpcMilitaryRole.STANDARD_BEARER.id())){
            if(!(npc.getOffhandItem().getItem() instanceof BannerItem))
                throw new IllegalStateException("original office requires an offhand banner");
            if(!state.carryingStandard||state.standardArm()==state.mainArm)throw new IllegalStateException("formal flag holder did not render the original offhand banner");
            var grip=NpcStandardGripGameTests.check(renderer,state);
            result.put("offhandStandard",true); result.put("standardArm",state.standardArm().toString()); result.put("palmToPole",grip.palmToPole());
        }
        Files.writeString(evidence,new Gson().toJson(result),StandardOpenOption.CREATE_NEW);
        return true;
    }
}
