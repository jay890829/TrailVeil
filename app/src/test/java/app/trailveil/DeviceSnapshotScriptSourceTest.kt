package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-013`: what the device-snapshot helper must keep guaranteeing.
 *
 * `scripts/capture-device-database-snapshot.ps1` installs a debuggable build over the shipped one
 * on a real phone to read its database, and then puts the shipped build back. Every dangerous thing
 * about that is in the "and then": if the restore is skipped, conditional, or attempted only on the
 * happy path, the device is left carrying a build whose entire exploration history anyone with an
 * authorised adb session can copy - which is exactly the exposure the owner declined on 2026-09-07
 * when a debuggable published APK was proposed.
 *
 * Nothing in the build runs this script, so nothing else would notice a guard being removed. This
 * asserts the guards are still written down, in the same spirit as [ReleaseScriptSourceTest].
 */
class DeviceSnapshotScriptSourceTest {
    @Test
    fun `the shipped build is restored even when the capture fails`() {
        val script = snapshotScript()

        // A restore that only runs after a successful copy is the whole failure mode: the runs that
        // go wrong are exactly the ones that would leave the twin installed.
        assertTrue(
            "the restore is no longer in a finally block",
            script.contains("} finally {") && script.contains("if (\$swapped) {"),
        )
        assertTrue(
            "a failed restore no longer warns loudly",
            script.contains("RESTORE FAILED"),
        )
        // Restoring the wrong APK is a silent version of the same failure, so the restored build is
        // asked whether it answers `run-as` rather than assumed not to.
        assertTrue(
            "the restored build is no longer re-checked for being debuggable",
            script.contains("The restored build still answers"),
        )
    }

    @Test
    fun `the build that will be restored is verified before anything is installed`() {
        val script = snapshotScript()

        val restoreCheck = script.indexOf("The restore APK is debuggable")
        val firstInstall = script.indexOf("-Description 'Twin install'")
        assertTrue("the restore-not-debuggable check is gone", restoreCheck > 0)
        assertTrue("the twin install is gone", firstInstall > 0)
        assertTrue(
            "the restore APK is now verified after the twin is already installed, which is too late",
            restoreCheck < firstInstall,
        )
        assertTrue(
            "installing over a different certificate would wipe the data being snapshotted",
            script.contains("signed by different certificates"),
        )
        assertTrue(
            "a restore that cannot be installed back is no longer refused up front",
            script.contains("could not be installed back"),
        )
    }

    @Test
    fun `the archive is copied as bytes and proven to be an archive`() {
        val script = snapshotScript()

        // PowerShell's redirect re-encodes the stream and prepends a BOM: measured 2026-09-07 on the
        // API 36 emulator, the same capture came out 205,800 bytes beginning EF BB BF and `tar`
        // refused it, against 204,800 bytes and a clean parse for the byte copy.
        assertTrue(
            "the raw byte copy is gone",
            script.contains("StandardOutput.BaseStream.CopyTo"),
        )
        assertFalse(
            "the capture uses a redirect again, which corrupts the tar",
            script.contains("tar -cf - databases > "),
        )
        // A corrupt archive that is never opened looks exactly like evidence, so the header is read.
        assertTrue(
            "the tar magic is no longer checked",
            script.contains("ustar"),
        )
    }

    @Test
    fun `the snapshot is never written into the repository`() {
        // The tar is a copy of the user's real track history. Inside the checkout it is one
        // `git add -A` from being published, which is the same rule the key file follows.
        assertTrue(
            "the repository-containment refusal is gone",
            snapshotScript().contains("it is inside the repository"),
        )
    }

    @Test
    fun `the app is never launched under the twin`() {
        // A launch lets the app write, and the snapshot is meant to be the state the shipped build
        // left behind - not that state plus whatever a cold start did to it.
        assertTrue(
            "the force-stop before the copy is gone",
            snapshotScript().contains("-Description 'Force-stop'"),
        )
    }

    /**
     * Both defects that stopped the first real run of this script, on 2026-09-07, before it had
     * installed anything. Source pins because nothing in the build executes the script, and because
     * neither is visible in its output until a device is attached and a real phone is mid-swap.
     *
     * 1. PowerShell 5.1 wraps every stderr line from a native command in a `NativeCommandError`,
     *    and this script runs under `ErrorActionPreference = 'Stop'`. The probe that asks "is the
     *    installed package debuggable" answers by running a command that FAILS when it is not - so
     *    the answer the whole twin-install path depends on arrived as a crash instead of a false.
     * 2. PowerShell unrolls a one-element array when a function returns it, so
     *    `(Invoke-CheckedNative ...)[0].Trim()` took the first CHARACTER of a one-line answer and
     *    called `.Trim()` on a `[char]`. All three manifest reads were written that way.
     */
    @Test
    fun `native invocations survive stderr and one-line answers`() {
        // Comment lines are stripped first. Both guards below are about what the script DOES, and
        // the script explains each defect in prose that necessarily quotes the broken form - so a
        // check over the raw text fails on its own documentation, which is how this test first ran.
        val script = snapshotScript()
            .lines()
            .filterNot { line -> line.trimStart().startsWith("#") }
            .joinToString(separator = "\n")

        assertTrue(
            "the debuggable probe must be the shared helper, not a bare command whose stderr is " +
                "a terminating error under ErrorActionPreference = Stop",
            script.contains("function Test-PackageDebuggable"),
        )
        assertEquals(
            "both the twin decision and the post-restore check must go through that helper",
            2,
            script.split("Test-PackageDebuggable").size - 1 - 1,
        )
        assertEquals(
            "every native call that can write to stderr must lower ErrorActionPreference around " +
                "itself and restore it in a finally",
            2,
            script.split("\$ErrorActionPreference = 'Continue'").size - 1,
        )
        assertEquals(
            "and each must put the previous value back",
            2,
            script.split("\$ErrorActionPreference = \$previousPreference").size - 1,
        )

        assertFalse(
            "a one-line answer indexed straight off the call is the unrolling bug: PowerShell " +
                "returns it as a String and [0] is then a Char",
            script.contains(")[0].Trim()"),
        )
        assertTrue(
            "the manifest reads must re-wrap the result with @() before indexing",
            script.contains("\$lines = @(Invoke-CheckedNative"),
        )
    }

    private fun snapshotScript(): String {
        val script = File(repositoryRoot(), "scripts/capture-device-database-snapshot.ps1")
        assertTrue("snapshot script not found at ${script.path}", script.isFile)
        return script.readText()
    }

    private fun repositoryRoot(): File {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return requireNotNull(moduleDirectory.parentFile)
    }
}
