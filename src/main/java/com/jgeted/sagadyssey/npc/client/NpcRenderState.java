package com.jgeted.sagadyssey.npc.client;

import com.jgeted.sagadyssey.npc.appearance.NpcFaceVariant;
import com.jgeted.sagadyssey.npc.appearance.NpcGender;
import com.jgeted.sagadyssey.npc.entity.NpcAction;
import com.jgeted.sagadyssey.npc.identity.NpcSocialStatus;
import com.jgeted.sagadyssey.npc.profession.NpcProfession;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public final class NpcRenderState extends HumanoidRenderState {
    public NpcGender gender;
    public NpcFaceVariant face;
    public NpcSocialStatus socialStatus;
    public NpcProfession profession;
    public NpcAction action;
    public float actionProgress;
    public boolean helmeted;
    public boolean resting;
    public boolean mounted;
    public int logisticsStage;
    public String logisticsText;
}
