package com.jgeted.sagadyssey.social.religion.life;

import net.minecraft.core.UUIDUtil;

import com.jgeted.sagadyssey.worldsim.rights.SubjectRef;
import net.minecraft.nbt.*;
import java.util.*;

/** A scoped, bilateral local obligation, distinct from every faction reputation. */
public record ChurchCompact(UUID id,UUID temple,SubjectRef partner,String terms,long created,long expires,
        UUID proposer,UUID templeSigner,UUID partnerSigner) {
    public ChurchCompact {Objects.requireNonNull(id);Objects.requireNonNull(temple);Objects.requireNonNull(partner);Objects.requireNonNull(proposer);
        if(partner.type()!=SubjectRef.SubjectType.INSTITUTION&&partner.type()!=SubjectRef.SubjectType.HOUSEHOLD
                ||partner.equals(SubjectRef.institution(temple))||terms==null||terms.isBlank()||terms.length()>256||created<0||expires<=created)throw new IllegalArgumentException("invalid local compact");}
    public boolean templeSigned(){return templeSigner!=null;}
    public boolean partnerSigned(){return partnerSigner!=null;}
    public boolean active(long now){return templeSigned()&&partnerSigned()&&now>=created&&now<expires;}
    public ChurchCompact sign(boolean church,UUID person){UUID old=church?templeSigner:partnerSigner;if(old!=null&&!old.equals(person))throw new IllegalStateException("cannot replace fixed signatory");return new ChurchCompact(id,temple,partner,terms,created,expires,proposer,church?person:templeSigner,church?partnerSigner:person);}
    public CompoundTag save(){var t=new CompoundTag();t.store("Id", UUIDUtil.CODEC,id);t.store("Temple", UUIDUtil.CODEC,temple);t.put("Partner",partner.save());t.putString("Terms",terms);t.putLong("Created",created);t.putLong("Expires",expires);t.store("Proposer", UUIDUtil.CODEC,proposer);t.putBoolean("TempleSigned",templeSigned());t.putBoolean("PartnerSigned",partnerSigned());if(templeSigned())t.store("TempleSigner", UUIDUtil.CODEC,templeSigner);if(partnerSigned())t.store("PartnerSigner", UUIDUtil.CODEC,partnerSigner);return t;}
    public static ChurchCompact load(CompoundTag t){ChurchLifeData.require(t,"Partner",Tag.TAG_COMPOUND);ChurchLifeData.require(t.getCompoundOrEmpty("Partner"),"Type",Tag.TAG_STRING);ChurchLifeData.uuid(t.getCompoundOrEmpty("Partner"),"Id");ChurchLifeData.require(t,"Terms",Tag.TAG_STRING);ChurchLifeData.require(t,"Created",Tag.TAG_LONG);ChurchLifeData.require(t,"Expires",Tag.TAG_LONG);ChurchLifeData.require(t,"TempleSigned",Tag.TAG_BYTE);ChurchLifeData.require(t,"PartnerSigned",Tag.TAG_BYTE);
        return new ChurchCompact(ChurchLifeData.uuid(t,"Id"),ChurchLifeData.uuid(t,"Temple"),SubjectRef.load(t.getCompoundOrEmpty("Partner")),t.getStringOr("Terms", ""),t.getLongOr("Created", 0L),t.getLongOr("Expires", 0L),ChurchLifeData.uuid(t,"Proposer"),t.getBooleanOr("TempleSigned", false)?ChurchLifeData.uuid(t,"TempleSigner"):null,t.getBooleanOr("PartnerSigned", false)?ChurchLifeData.uuid(t,"PartnerSigner"):null);}
}
