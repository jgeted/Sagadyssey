package com.jgeted.sagadyssey.social.production;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ProductionLaborDataTest {
    @Test void combatTimeIsNotLabor(){var d=new ProductionLaborData();var id=UUID.randomUUID();assertEquals(1,d.tick(id,80,100));assertEquals(2,d.tick(id,80,24100));}
    @Test void sameTickIsAnIdempotentReceipt(){var d=new ProductionLaborData();var id=UUID.randomUUID();d.tick(id,80,100);assertEquals(1,d.tick(id,80,100));assertEquals(1,d.tick(id,80,99));}
    @Test void originalOrderProgressSurvivesReload(){var d=new ProductionLaborData();var id=UUID.randomUUID();d.tick(id,80,100);var loaded=ProductionLaborData.load(d.save(new CompoundTag(),null),null);assertEquals(1,loaded.worked(id));assertEquals(2,loaded.tick(id,80,72000));}
    @Test void oldOrderStartsAtZeroRatherThanFabricatedElapsedWork(){assertEquals(0,new ProductionLaborData().worked(UUID.randomUUID()));}
    @Test void invalidOrMissingSchemaFailsClosed(){assertThrows(IllegalArgumentException.class,()->ProductionLaborData.load(new CompoundTag(),null));var t=new ProductionLaborData().save(new CompoundTag(),null);t.putInt("Schema",2);assertThrows(IllegalArgumentException.class,()->ProductionLaborData.load(t,null));}
    @Test void duplicateOrdersCannotOverwriteSavedProgress(){var d=new ProductionLaborData();d.tick(UUID.randomUUID(),80,100);var t=d.save(new CompoundTag(),null);var l=t.getList("Progress",Tag.TAG_COMPOUND);l.add(l.get(0).copy());assertThrows(IllegalArgumentException.class,()->ProductionLaborData.load(t,null));}
    @Test void actualProgressHasBoundedWorkAndTime(){var d=new ProductionLaborData();assertThrows(IllegalArgumentException.class,()->d.tick(UUID.randomUUID(),0,100));assertThrows(IllegalArgumentException.class,()->d.tick(UUID.randomUUID(),80,-1));}
}
