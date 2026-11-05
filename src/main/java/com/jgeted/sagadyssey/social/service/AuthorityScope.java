package com.jgeted.sagadyssey.social.service;

/** A deliberately narrow action family granted by a service relationship. */
public enum AuthorityScope {
    FOLLOW("随行"),
    HOLD_POSITION("驻守"),
    WORK("劳动"),
    LOGISTICS("运输"),
    PATROL("巡逻"),
    DEFEND_PRINCIPAL("保护委托方"),
    ASSIGN_WORKPLACE("分配工作地点"),
    ASSIGN_SCHEDULE("调整排班"),
    ASSIGN_ROLE("协商岗位"),
    MANAGE_ISSUED_EQUIPMENT("管理配发装备"),
    OPERATE_MOUNT("管理工作坐骑"),
    END_SERVICE("解除服务关系");

    private final String displayName;

    AuthorityScope(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
