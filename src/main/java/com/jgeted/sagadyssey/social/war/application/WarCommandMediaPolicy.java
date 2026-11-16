package com.jgeted.sagadyssey.social.war.application;

import com.jgeted.sagadyssey.social.war.domain.WarTransmission;

public final class WarCommandMediaPolicy {
    private WarCommandMediaPolicy(){}
    /** A horn is local. Distant orders must use the existing delayed courier channel. */
    public static boolean audible(WarTransmission.Route route,long now){return route.channel()==WarTransmission.Channel.DIRECT && now>=route.reportedAt() && now-route.reportedAt()<=40 && now==route.sentAt();}
}
