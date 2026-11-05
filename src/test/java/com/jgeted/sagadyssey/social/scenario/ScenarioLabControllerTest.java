package com.jgeted.sagadyssey.social.scenario;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLabControllerTest {
    @Test
    void recognizesOnlyDeterministicNpcsFromLabDeployments() {
        FixedScenarioDeployment lab = deployment("trader_lab");

        assertTrue(ScenarioLabController.matchesLabNpc(lab, FixedScenarioData.npcId(lab, 0)));
        assertTrue(ScenarioLabController.matchesLabNpc(lab, FixedScenarioData.npcId(lab, 1)));
        assertFalse(ScenarioLabController.matchesLabNpc(lab, UUID.randomUUID()));
        assertFalse(ScenarioLabController.matchesLabNpc(deployment("small_realm"),
                FixedScenarioData.npcId(deployment("small_realm"), 0)));
    }

    private static FixedScenarioDeployment deployment(String scenarioId) {
        UUID id = FixedScenarioData.deploymentId(scenarioId, "minecraft:overworld", BlockPos.ZERO);
        return new FixedScenarioDeployment(id, scenarioId, 1, 0,
                "minecraft:overworld", BlockPos.ZERO, null, 0L, false);
    }
}
