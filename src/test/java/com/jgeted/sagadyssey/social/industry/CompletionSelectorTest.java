package com.jgeted.sagadyssey.social.industry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CompletionSelectorTest {
 @Test void exactOptInOneSceneAndDisabledOtherwise(){
  var a=System.getProperty("sagadyssey.industry.native");var b=System.getProperty("sagadyssey.completion.phase");
  try{System.setProperty("sagadyssey.industry.native","true");
   for(String phase:List.of("wood-start","wood-resume","ore-start","ore-resume","crop-two","wool-start","wool-resume","fish-next","settlement-loop")){System.setProperty("sagadyssey.completion.phase",phase);var t=IndustryCompletionGameTests.tests();assertEquals(1,t.size());assertTrue(t.iterator().next().structureName().startsWith("sagadyssey_industries_completion:"));}
   System.setProperty("sagadyssey.completion.phase","disabled");assertTrue(IndustryCompletionGameTests.tests().isEmpty());System.setProperty("sagadyssey.industry.native","false");System.setProperty("sagadyssey.completion.phase","ore-start");assertTrue(IndustryCompletionGameTests.tests().isEmpty());
  }finally{if(a==null)System.clearProperty("sagadyssey.industry.native");else System.setProperty("sagadyssey.industry.native",a);if(b==null)System.clearProperty("sagadyssey.completion.phase");else System.setProperty("sagadyssey.completion.phase",b);}
 }
}