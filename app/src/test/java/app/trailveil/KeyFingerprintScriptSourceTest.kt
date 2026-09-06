package app.trailveil

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-013`: what the key-fingerprint helper must keep refusing, and must keep not printing.
 *
 * `scripts/set-google-maps-key-fingerprint.ps1` edits the one file that holds a Google Maps key, so
 * it is the only script in this repository that reads a secret and writes back into the file it
 * came from. Nothing else in the build runs it, which means nothing else notices when one of its
 * guards is deleted - and the failures it prevents are silent ones: a fingerprint blessing a
 * malformed key, a key written into the working tree where `git add -A` would publish it, or a key
 * echoed into a terminal, a shell history or an agent transcript.
 *
 * This does not run the script. It asserts that each refusal is still written down, in the same
 * spirit as [ReleaseScriptSourceTest].
 */
class KeyFingerprintScriptSourceTest {
    @Test
    fun `nothing the script prints can carry the key or its digest`() {
        // The whole point of deriving the digest from the file is that neither value has to pass
        // through a screen. An interpolated diagnostic would undo that in one line, and it would be
        // the kind of line somebody adds while debugging and forgets.
        val printed = fingerprintScript()
            .lineSequence()
            .filter { line -> line.contains("Write-Output") || line.contains("Write-Host") }
            .toList()

        assertTrue("the script prints nothing at all, which cannot be right", printed.isNotEmpty())
        listOf("\$key", "\$digest", "\$storedFingerprint", "\$writtenKey", "\$writtenFingerprint")
            .forEach { secret ->
                val offending = printed.filter { line -> line.contains(secret) }
                assertTrue(
                    "a printed line interpolates $secret: $offending",
                    offending.isEmpty(),
                )
            }
        assertTrue(
            "the script no longer says that it printed neither",
            fingerprintScript().contains("Neither the key nor the digest was printed."),
        )
    }

    @Test
    fun `a key file inside the repository is refused`() {
        // The build refuses one for the same reason. A helper that happily wrote a fresh secret
        // into the checkout would be the most convenient possible way to leak one.
        assertTrue(
            "the repository-containment refusal is gone",
            fingerprintScript().contains("it is inside the repository"),
        )
    }

    @Test
    fun `a malformed key is refused rather than fingerprinted`() {
        val script = fingerprintScript()

        // The same pattern the build applies. Fingerprinting a typo produces a file that is
        // internally consistent and still builds the missing-key sentinel, which is a worse state
        // to debug than a plain mismatch.
        assertTrue(
            "the key shape is no longer checked before hashing",
            script.contains("^AIza[A-Za-z0-9_-]{35}$"),
        )
        assertTrue(
            "a malformed key must not be hashed",
            script.contains("is not shaped like a Google Maps key"),
        )
    }

    @Test
    fun `the digest is written in the only form the release script accepts`() {
        val script = fingerprintScript()

        // `build-github-release.ps1` matches `^\s*releaseApiKeySha256\s*=\s*([0-9a-fA-F]{64})\s*$`.
        // A colon separator is legal in a .properties file and would be read by the build, so the
        // two could agree while publication still refused - hence the write side is pinned to `=`,
        // and the script re-reads its own work through the strict pattern before claiming success.
        assertTrue(
            "the fingerprint is no longer written with '='",
            script.contains("\"\$fingerprintProperty=\$digest\""),
        )
        assertTrue(
            "the written fingerprint is no longer read back strictly",
            script.contains("'\\s*=\\s*([0-9a-fA-F]{64})\\s*\$'"),
        )
        assertTrue(
            "the key is no longer checked for having survived the rewrite",
            script.contains("did not survive the rewrite intact"),
        )
    }

    @Test
    fun `the digest matches the one the build and the release audit compute`() {
        val script = fingerprintScript()

        // UTF-8 bytes, lowercase hex, no trailing newline - the same three choices as
        // `String.sha256Hex` in the build and the packaged-key hash in the release script. Any one
        // of them differing produces a fingerprint that is wrong in a way nothing reports until a
        // release build refuses to publish.
        assertTrue(
            "the digest is no longer taken over UTF-8 bytes without a BOM",
            script.contains("[System.Text.UTF8Encoding]::new(\$false).GetBytes(\$Text)"),
        )
        assertTrue(
            "the digest is no longer lowercase hex",
            script.contains("\$_.ToString('x2')"),
        )
    }

    private fun fingerprintScript(): String {
        val script = File(repositoryRoot(), "scripts/set-google-maps-key-fingerprint.ps1")
        assertTrue("fingerprint script not found at ${script.path}", script.isFile)
        return script.readText()
    }

    private fun repositoryRoot(): File {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return requireNotNull(moduleDirectory.parentFile)
    }
}
