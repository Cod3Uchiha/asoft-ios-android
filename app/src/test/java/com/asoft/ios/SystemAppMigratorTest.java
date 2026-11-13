package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemAppMigratorTest {
    private static final String DUMP = " /System/Library/DataClassMigrators/SpringBoard.migrator/Info.plist: <plist> x";

    @Test
    public void onlyADebugBuildWithTheFlagKeepsIt() {
        assertFalse(SystemAppMigrator.keep(false, false));
        assertFalse(SystemAppMigrator.keep(false, true));      // a release build ignores a stray flag file
        assertFalse(SystemAppMigrator.keep(true, false));
        assertTrue(SystemAppMigrator.keep(true, true));
    }

    @Test
    public void theListingSaysWhetherItIsThere() {
        assertEquals(SystemAppMigrator.State.PRESENT,
                SystemAppMigrator.state("AppleAccount.migrator SpringBoard.migrator SystemAppMigrator.migrator Utilities.migrator " + DUMP));
        assertEquals(SystemAppMigrator.State.OUT,
                SystemAppMigrator.state("AppleAccount.migrator SpringBoard.migrator Utilities.migrator " + DUMP));
        assertEquals(SystemAppMigrator.State.PRESENT, SystemAppMigrator.state("SystemAppMigrator.migrator \n"));
    }

    @Test
    public void aNameInTheDumpOrAnotherNameDoesNotCount() {
        assertEquals(SystemAppMigrator.State.OUT,
                SystemAppMigrator.state("SpringBoard.migrator " + DUMP + " SystemAppMigrator.migrator"));
        assertEquals(SystemAppMigrator.State.OUT, SystemAppMigrator.state("SpringBoard.migrator OldSystemAppMigrator.migrator.bak"));
    }

    @Test
    public void noListingIsUnknownNotOut() {
        assertEquals(SystemAppMigrator.State.UNKNOWN, SystemAppMigrator.state(null));
        assertEquals(SystemAppMigrator.State.UNKNOWN, SystemAppMigrator.state(""));
        assertEquals(SystemAppMigrator.State.UNKNOWN, SystemAppMigrator.state("ls: /System/Library/DataClassMigrators: No such file"));
    }

    @Test
    public void thePcMinutesFollowTheVariant() {
        assertEquals(35, SystemAppMigrator.pcMinutes(SystemAppMigrator.State.OUT));
        assertEquals(50, SystemAppMigrator.pcMinutes(SystemAppMigrator.State.PRESENT));
        assertEquals(50, SystemAppMigrator.pcMinutes(SystemAppMigrator.State.UNKNOWN));
        assertTrue(SystemAppMigrator.describe(SystemAppMigrator.State.OUT).contains("out"));
        assertTrue(SystemAppMigrator.describe(SystemAppMigrator.State.PRESENT).contains("present"));
    }
}
