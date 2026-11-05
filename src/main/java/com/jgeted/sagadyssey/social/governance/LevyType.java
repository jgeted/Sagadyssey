package com.jgeted.sagadyssey.social.governance;

import com.jgeted.sagadyssey.social.institution.OfficeAuthority;

public enum LevyType {
    TAX(OfficeAuthority.ASSESS_TAX),
    RENT(OfficeAuthority.ASSESS_RENT),
    GUILD_DUES(OfficeAuthority.ASSESS_DUES),
    TITHE(OfficeAuthority.ASSESS_DUES),
    FINE(OfficeAuthority.ADJUDICATE);

    private final OfficeAuthority assessmentAuthority;
    LevyType(OfficeAuthority assessmentAuthority) { this.assessmentAuthority = assessmentAuthority; }
    public OfficeAuthority assessmentAuthority() { return assessmentAuthority; }
}
