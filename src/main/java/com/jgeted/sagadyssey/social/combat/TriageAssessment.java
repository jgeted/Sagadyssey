package com.jgeted.sagadyssey.social.combat;

public record TriageAssessment(int activeWounds, int totalTrauma, int bleeding,
                               int infection, int mobilityPenalty,
                               int workPenalty, boolean incapacitated,
                               boolean mortalDanger) { }
