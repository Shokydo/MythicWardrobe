package dev.mythicwardrobe;

import dev.mythicwardrobe.manager.WardrobeManager;
import dev.mythicwardrobe.model.MenuMode;
import dev.mythicwardrobe.model.PlayerWardrobeState;
import dev.mythicwardrobe.storage.WardrobeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class UnwardrobeLogicTest {

    @Test
    void unwardrobeSingleAndMultiLayerRemoval() {
        PlayerWardrobeState state = new PlayerWardrobeState(UUID.randomUUID());
        state.setSkillLayer("jacket", "jackets/demo_jacket.png");
        state.setSkillLayer("undershirt", "undershirts/white_tshirt.png");
        state.setSkillLayer("pants", "pants/blue_jeans.png");

        int removed = WardrobeManager.applySkillLayerRemoval(state, false, List.of("jacket", "undershirt"), "");
        assertEquals(2, removed);
        assertNull(state.getSkillLayer("jacket"));
        assertNull(state.getSkillLayer("undershirt"));
        assertEquals("pants/blue_jeans.png", state.getSkillLayer("pants"));
    }

    @Test
    void unwardrobeTextureGuardPreventsSwapRaceCondition() {
        PlayerWardrobeState state = new PlayerWardrobeState(UUID.randomUUID());
        // Suppose player already swapped to red_jacket.png right before leather_jacket's ~onUnequip fired
        state.setSkillLayer("jacket", "jackets/red_jacket.png");

        int removedMismatch = WardrobeManager.applySkillLayerRemoval(state, false, List.of("jacket"), "jackets/leather_jacket.png");
        assertEquals(0, removedMismatch, "Must NOT remove a newer jacket when texture filter does not match");
        assertEquals("jackets/red_jacket.png", state.getSkillLayer("jacket"));

        // Basename without .png should still match cleanly
        int removedMatch = WardrobeManager.applySkillLayerRemoval(state, false, List.of("jacket"), "red_jacket");
        assertEquals(1, removedMatch);
        assertNull(state.getSkillLayer("jacket"));
    }

    @Test
    void unwardrobeClearAllWithAndWithoutTextureFilter() {
        PlayerWardrobeState state = new PlayerWardrobeState(UUID.randomUUID());
        state.setSkillLayer("jacket", "jackets/coat.png");
        state.setSkillLayer("pants", "pants/jeans.png");

        // Filtered clear-all only strips matching texture
        int removedOne = WardrobeManager.applySkillLayerRemoval(state, true, List.of(), "coat.png");
        assertEquals(1, removedOne);
        assertNull(state.getSkillLayer("jacket"));
        assertEquals("pants/jeans.png", state.getSkillLayer("pants"));

        // Unfiltered clear-all wipes remainder
        int removedRest = WardrobeManager.applySkillLayerRemoval(state, true, List.of(), "");
        assertEquals(1, removedRest);
        assertTrue(state.getSkillLayers().isEmpty());
    }

    @Test
    void databasePersistsAndClearsPlayerMenuMode(@TempDir Path tempDir) throws Exception {
        File dbFile = tempDir.resolve("wardrobe_mode_test.db").toFile();
        UUID uuid = UUID.randomUUID();

        try (WardrobeDatabase db = new WardrobeDatabase(dbFile, Logger.getLogger("test"))) {
            db.init();
            assertEquals(Optional.empty(), db.loadMenuMode(uuid));

            db.saveMenuMode(uuid, MenuMode.UNIFIED);
            assertEquals(Optional.of(MenuMode.UNIFIED), db.loadMenuMode(uuid));

            db.saveMenuMode(uuid, MenuMode.TABBED);
            assertEquals(Optional.of(MenuMode.TABBED), db.loadMenuMode(uuid));

            db.saveMenuMode(uuid, null);
            assertEquals(Optional.empty(), db.loadMenuMode(uuid));
        }
    }
}
