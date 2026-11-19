package dev.mythicwardrobe;

import dev.mythicwardrobe.model.BodyTypeOverrideRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyTypeOverrideRuleTest {

    @Test
    void matchesByMythicIdOrEquippableModelKeyCaseInsensitively() {
        BodyTypeOverrideRule rule = new BodyTypeOverrideRule(
                "vest",
                "TacticalVest",
                "mythicarmor:tactical_vest_slim_chest_piece",
                "mythicarmor:tactical_vest_wide_chest_piece"
        );

        assertTrue(rule.matches("tacticalvest", null));
        assertTrue(rule.matches("TacticalVest", "mythicarmor:other"));
        assertFalse(rule.matches("OtherVest", "mythicarmor:other"));

        BodyTypeOverrideRule modelRule = new BodyTypeOverrideRule(
                "vest_model",
                "mythicarmor:tactical_vest_chest_piece",
                "mythicarmor:tactical_vest_slim_chest_piece",
                "mythicarmor:tactical_vest_wide_chest_piece"
        );
        assertTrue(modelRule.matches(null, "MYTHICARMOR:TACTICAL_VEST_CHEST_PIECE"));
        assertFalse(modelRule.matches("TacticalVest", "mythicarmor:other"));
    }

    @Test
    void selectsSlimOrWideTargetAndDetectsModelKeys() {
        BodyTypeOverrideRule rule = new BodyTypeOverrideRule(
                "vest",
                "TacticalVest",
                "mythicarmor:tactical_vest_slim_chest_piece",
                "TacticalVest_Wide"
        );

        assertEquals("mythicarmor:tactical_vest_slim_chest_piece", rule.targetFor(true));
        assertEquals("TacticalVest_Wide", rule.targetFor(false));
        assertTrue(BodyTypeOverrideRule.isModelKey(rule.targetFor(true)));
        assertFalse(BodyTypeOverrideRule.isModelKey(rule.targetFor(false)));
    }
}
