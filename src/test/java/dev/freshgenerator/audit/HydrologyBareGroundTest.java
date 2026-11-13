package dev.freshgenerator.audit;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Audit-only: genuine soil changes must not disappear with native decorations. */
class HydrologyBareGroundTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static int ground(BlockState... bottomToTop) {
        for (int y = bottomToTop.length - 1; y >= 0; y--)
            if (HydrologyWorldAudit.isBareGround(bottomToTop[y], !bottomToTop[y].is(Blocks.AIR))) return y;
        return -1;
    }
    @Test void observedBambooAndPumpkinsAreNotNewBankSoil() {
        var soil = Blocks.GRASS_BLOCK.defaultBlockState();
        assertEquals(0, ground(soil));
        assertEquals(0, ground(soil, Blocks.PUMPKIN.defaultBlockState()));
        assertEquals(0, ground(soil, Blocks.BAMBOO.defaultBlockState(), Blocks.BAMBOO.defaultBlockState()));
        assertTrue(HydrologyWorldAudit.isBankPlant(Blocks.BAMBOO.defaultBlockState()));
        assertTrue(HydrologyWorldAudit.isBankPlant(Blocks.PUMPKIN.defaultBlockState()));
    }
    @Test void observedMelonIsRecordedButDoesNotReplaceTheMeasuredSoil() {
        var grass = Blocks.GRASS_BLOCK.defaultBlockState();
        var melon = Blocks.MELON.defaultBlockState();
        assertTrue(HydrologyWorldAudit.isBankPlant(melon));
        assertEquals(0, ground(grass, melon));
        assertEquals(1, ground(grass, Blocks.DIRT.defaultBlockState(), melon));
        assertEquals(-1, ground(Blocks.AIR.defaultBlockState(), melon));
        assertTrue(HydrologyWorldAudit.isBareGround(Blocks.CARVED_PUMPKIN.defaultBlockState(), true));
    }

    @Test void liftedOrRemovedTerrainStillChangesTheMeasuredBank() {
        var soil = Blocks.DIRT.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        assertEquals(1, ground(soil, soil, Blocks.PUMPKIN.defaultBlockState()));
        assertEquals(1, ground(soil, Blocks.STONE.defaultBlockState(), Blocks.BAMBOO.defaultBlockState()));
        assertEquals(-1, ground(air, Blocks.PUMPKIN.defaultBlockState()));
        assertEquals(0, ground(soil, air, Blocks.BAMBOO.defaultBlockState()));
    }
    @Test void unrecognizedSolidsAreNotSilentlyWhitelisted() {
        assertTrue(HydrologyWorldAudit.isBareGround(Blocks.DIAMOND_BLOCK.defaultBlockState(), true));
        assertTrue(HydrologyWorldAudit.isBareGround(Blocks.STONE.defaultBlockState(), true));
        assertFalse(HydrologyWorldAudit.isBankPlant(Blocks.CARVED_PUMPKIN.defaultBlockState()));
        assertFalse(HydrologyWorldAudit.isBareGround(Blocks.WATER.defaultBlockState(), true));
        assertFalse(HydrologyWorldAudit.isBareGround(Blocks.LAVA.defaultBlockState(), true));
        assertFalse(HydrologyWorldAudit.isBareGround(Blocks.ICE.defaultBlockState(), true));
        assertFalse(HydrologyWorldAudit.isBareGround(Blocks.AIR.defaultBlockState(), false));
    }
}
