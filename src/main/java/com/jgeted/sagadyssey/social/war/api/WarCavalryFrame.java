package com.jgeted.sagadyssey.social.war.api;

import java.util.*;

/** Bounded actual observations, not projected horse or soldier simulation. */
public record WarCavalryFrame(UUID unit,UUID army,UUID order,long sequence,long now,WarIntent.Position assembly,
                              WarIntent.Position objective,int organization,List<Member> members){
    public record Member(UUID contract,UUID character,UUID body,boolean eligible,boolean atAssembly,boolean atExit){
        public Member{Objects.requireNonNull(contract);Objects.requireNonNull(character);Objects.requireNonNull(body);}
    }
    public WarCavalryFrame{
        Objects.requireNonNull(unit);Objects.requireNonNull(army);Objects.requireNonNull(order);Objects.requireNonNull(assembly);Objects.requireNonNull(objective);members=List.copyOf(members);
        if(sequence<1||now<0||organization<0||organization>10000||members.isEmpty()||members.size()>128||members.stream().map(Member::contract).distinct().count()!=members.size())throw new IllegalArgumentException("invalid cavalry observation");
    }
    public boolean allEligible(){return members.stream().allMatch(Member::eligible);}
    public boolean assembled(){return allEligible()&&members.stream().allMatch(Member::atAssembly);}
}
