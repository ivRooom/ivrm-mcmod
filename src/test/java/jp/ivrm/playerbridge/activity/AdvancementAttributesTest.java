package jp.ivrm.playerbridge.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class AdvancementAttributesTest {

    @Test
    void vanillaStoryAndAdventureAdvancementsAreReportable() {
        assertTrue(AdvancementAttributes.isReportable("minecraft:story/root"));
        assertTrue(AdvancementAttributes.isReportable("minecraft:story/smelt_iron"));
        assertTrue(AdvancementAttributes.isReportable("minecraft:adventure/kill_all_mobs"));
        assertTrue(AdvancementAttributes.isReportable("minecraft:end/kill_dragon"));
    }

    @Test
    void modAdvancementsAreReportableUnderTheirOwnNamespace() {
        assertTrue(AdvancementAttributes.isReportable("appliedenergistics2:main/flawless_budding_quartz"));
        assertTrue(AdvancementAttributes.isReportable("ftbquests:quests/chapter_one"));
    }

    @Test
    void recipeUnlockAdvancementsAreExcludedInEveryNamespace() {
        assertFalse(AdvancementAttributes.isReportable("minecraft:recipes/misc/charcoal"));
        assertFalse(AdvancementAttributes.isReportable("minecraft:recipes/building_blocks/iron_bars"));
        assertFalse(AdvancementAttributes.isReportable("appliedenergistics2:recipes/decorative/certus_quartz_block"));
    }

    @Test
    void idsWithoutNamespaceAreJudgedByTheirPath() {
        assertTrue(AdvancementAttributes.isReportable("story/root"));
        assertFalse(AdvancementAttributes.isReportable("recipes/misc/charcoal"));
    }

    @Test
    void attributesCarryTheFullAdvancementId() {
        assertEquals(
                Map.of("advancement", "minecraft:story/smelt_iron"),
                AdvancementAttributes.attributes("minecraft:story/smelt_iron"));
    }
}
