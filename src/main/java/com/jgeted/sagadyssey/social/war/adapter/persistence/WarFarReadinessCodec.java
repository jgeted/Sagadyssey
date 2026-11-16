package com.jgeted.sagadyssey.social.war.adapter.persistence;

import com.jgeted.sagadyssey.core.persistence.NbtSnapshotCodec;
import com.jgeted.sagadyssey.social.war.domain.WarFarReadiness;
import com.jgeted.sagadyssey.social.war.domain.WarTransmission;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.*;

public final class WarFarReadinessCodec extends NbtSnapshotCodec<WarFarReadiness> {
    public WarFarReadinessCodec() { super("sagadyssey:war_far_readiness/v1", 2 * 1024 * 1024, 128); }

    @Override protected UUID identity(WarFarReadiness plan) { return plan.unit(); }

    @Override protected CompoundTag encode(WarFarReadiness plan) {
        var tag = new CompoundTag();
        WarHazardCodec.id(tag, "Unit", plan.unit());
        WarHazardCodec.id(tag, "Army", plan.army());
        WarHazardCodec.id(tag, "Principal", plan.principal());
        WarHazardCodec.id(tag, "Order", plan.order());
        WarHazardCodec.id(tag, "Operation", plan.operation());
        tag.putLong("ApprovedAt", plan.approvedAt());
        tag.putLong("DepartedAt", plan.departedAt());
        tag.putLong("ChangedAt", plan.changedAt());
        tag.putLong("Revision", plan.revision());
        tag.putInt("Duration", plan.duration());
        tag.putInt("Fit", plan.fitCount());
        tag.putString("Phase", plan.phase().name());
        tag.putString("Reason", plan.reason());
        var posts = new ListTag();
        for (var post : plan.posts()) {
            var row = new CompoundTag();
            WarHazardCodec.id(row, "Contract", post.member().contract());
            WarHazardCodec.id(row, "Character", post.member().character());
            WarHazardCodec.id(row, "Body", post.member().body());
            row.put("Station", WarHazardCodec.position(post.station()));
            posts.add(row);
        }
        tag.put("Posts", posts);
        return tag;
    }

    @Override protected WarFarReadiness decode(CompoundTag tag) {
        WarHazardCodec.fields(tag, "Unit,Army,Principal,Order,Operation,ApprovedAt,DepartedAt,ChangedAt,Revision,Duration,Fit,Phase,Reason,Posts");
        var posts = new ArrayList<WarFarReadiness.Post>();
        for (var raw : WarHazardCodec.rows(tag, "Posts", WarFarReadiness.MAX_MEMBERS)) {
            var row = (CompoundTag)raw;
            WarHazardCodec.fields(row, "Contract,Character,Body,Station");
            var member = new WarTransmission.Member(uuid(row, "Contract"), uuid(row, "Character"), uuid(row, "Body"));
            posts.add(new WarFarReadiness.Post(member, WarHazardCodec.position(WarHazardCodec.compound(row, "Station"))));
        }
        return new WarFarReadiness(uuid(tag,"Unit"), uuid(tag,"Army"), uuid(tag,"Principal"), uuid(tag,"Order"), uuid(tag,"Operation"),
                posts, WarHazardCodec.integer(tag,"Duration"), number(tag,"ApprovedAt"), number(tag,"DepartedAt"),
                WarFarReadiness.Phase.valueOf(string(tag,"Phase")), WarHazardCodec.integer(tag,"Fit"),
                number(tag,"Revision"), number(tag,"ChangedAt"), string(tag,"Reason"));
    }
}
