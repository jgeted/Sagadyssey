package com.jgeted.sagadyssey.social.war.domain;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
public final class WarCampaignIdentity {
    private WarCampaignIdentity(){}
    public static UUID operation(String key){return UUID.nameUUIDFromBytes(("sagadyssey:war/campaign/v1/"+key).getBytes(StandardCharsets.UTF_8));}
    public static String cargo(UUID service,UUID task){return "sagadyssey:war/cargo/"+service+"/"+task;}
}
