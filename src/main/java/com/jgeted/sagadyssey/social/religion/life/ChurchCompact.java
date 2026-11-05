package com.jgeted.sagadyssey.social.religion.life;

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
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putUUID("Temple",temple);t.put("Partner",partner.save());t.putString("Terms",terms);t.putLong("Created",created);t.putLong("Expires",expires);t.putUUID("Proposer",proposer);t.putBoolean("TempleSigned",templeSigned());t.putBoolean("PartnerSigned",partnerSigned());if(templeSigned())t.putUUID("TempleSigner",templeSigner);if(partnerSigned())t.putUUID("PartnerSigner",partnerSigner);return t;}
    public static ChurchCompact load(CompoundTag t){ChurchLifeData.require(t,"Partner",Tag.TAG_COMPOUND);ChurchLifeData.require(t.getCompound("Partner"),"Type",Tag.TAG_STRING);ChurchLifeData.uuid(t.getCompound("Partner"),"Id");ChurchLifeData.require(t,"Terms",Tag.TAG_STRING);ChurchLifeData.require(t,"Created",Tag.TAG_LONG);ChurchLifeData.require(t,"Expires",Tag.TAG_LONG);ChurchLifeData.require(t,"TempleSigned",Tag.TAG_BYTE);ChurchLifeData.require(t,"PartnerSigned",Tag.TAG_BYTE);
        return new ChurchCompact(ChurchLifeData.uuid(t,"Id"),ChurchLifeData.uuid(t,"Temple"),SubjectRef.load(t.getCompound("Partner")),t.getString("Terms"),t.getLong("Created"),t.getLong("Expires"),ChurchLifeData.uuid(t,"Proposer"),t.getBoolean("TempleSigned")?ChurchLifeData.uuid(t,"TempleSigner"):null,t.getBoolean("PartnerSigned")?ChurchLifeData.uuid(t,"PartnerSigner"):null);}
}
