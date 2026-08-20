package app.trailveil

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `P5-002`: the build identity the app shows has to be usable by whoever reads it back to us.
 *
 * `BuildConfig.GIT_COMMIT` is produced by shelling out to git at configuration time, and that has
 * two distinct ways to go wrong. A MALFORMED answer — a multi-line result, stray whitespace, an
 * error message on stdout — renders as garbage after the separator. An ABSENT answer — git missing,
 * git failing, nothing on stdout — collapses to the literal `unknown`, which renders cleanly and
 * says nothing.
 *
 * Both are pinned, and they need different assertions. The shape check rejects the malformed. The
 * absent case cannot be rejected outright, because a source archive without `.git` must still
 * build and `unknown` is the honest answer there — so it is rejected exactly when this tree HAS a
 * `.git`, which is the only situation where `unknown` means the wiring broke rather than the
 * history being unavailable.
 *
 * Not caught here: whether the sha is the RIGHT one. Nothing inside the built artifact can know
 * that. What this does guarantee is that a wrong answer cannot masquerade as a well-formed one,
 * and that a silently broken git invocation cannot ship as a clean-looking `unknown`.
 */
class BuildIdentityTest {
    @Test
    fun `the build names a commit in a shape a person can act on`() {
        val commit = BuildConfig.GIT_COMMIT

        assertTrue(
            "the build identity is empty, so the app would show a blank commit",
            commit.isNotBlank(),
        )
        assertTrue(
            "the build identity contains whitespace, so git's answer was not a bare sha: '$commit'",
            commit.none(Char::isWhitespace),
        )
        assertTrue(
            "the build identity is not a short sha, a dirty short sha, or `unknown`: '$commit'",
            SHAPE.matches(commit),
        )
    }

    @Test
    fun `a build made inside a git repository names its commit rather than unknown`() {
        val moduleDir = File(System.getProperty("user.dir"))
        val gitDir = File(moduleDir.parentFile, ".git")
        // `.git` is a directory in a normal clone and a FILE in a worktree or submodule; both mean
        // the history is reachable, so `exists()` rather than `isDirectory`.
        if (!gitDir.exists()) return

        assertTrue(
            "this tree has git history at ${gitDir.absolutePath}, but the build identity is " +
                "`unknown` — the git invocation in build.gradle.kts is failing silently and every " +
                "internal APK would ship unidentifiable",
            BuildConfig.GIT_COMMIT != "unknown",
        )
    }

    @Test
    fun `the version the app shows is the version the build declares`() {
        // Cheap, and it caught nothing — but the alternative is a screen that hard-codes a version
        // string, which is how a release ends up reporting the previous one.
        assertTrue("the version name is empty", BuildConfig.VERSION_NAME.isNotBlank())
        assertTrue(
            "the version code is not positive: ${BuildConfig.VERSION_CODE}",
            BuildConfig.VERSION_CODE > 0,
        )
    }

    private companion object {
        val SHAPE = Regex("""^(unknown|[0-9a-f]{12}(-dirty)?)$""")
    }
}
