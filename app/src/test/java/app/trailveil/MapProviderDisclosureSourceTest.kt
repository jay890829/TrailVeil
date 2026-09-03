package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-006`: the privacy sheet names the active basemap provider through three string keys that
 * every provider source set must define in both locales. This pins the shape (keys, locales) and
 * the content the two providers' terms require: the Google build must name the Google Maps
 * Additional Terms and the Google Privacy Policy (Google Maps Platform Terms 3.2.2(a)); the
 * MapLibre build must carry the OpenStreetMap attribution and licence pointer the ODbL requires.
 */
class MapProviderDisclosureSourceTest {
    private val keys = listOf(
        "map_provider_disclosure_name",
        "map_provider_privacy_body",
        "map_provider_terms_body",
    )
    private val locales = listOf("values", "values-zh-rTW")

    @Test
    fun everyProviderSourceSetDefinesTheDisclosureInBothLocales() {
        for (sourceSet in listOf("mapLibre", "googlePoc")) {
            for (locale in locales) {
                val strings = stringsOf(sourceSet, locale)
                for (key in keys) {
                    assertTrue("$sourceSet/$locale is missing $key", strings.containsKey(key))
                    assertTrue("$sourceSet/$locale has an empty $key", strings.getValue(key).isNotBlank())
                }
            }
        }
    }

    @Test
    fun theSharedCopyNamesNoProviderAndCarriesTheProviderLabel() {
        for (locale in locales) {
            val strings = stringsOf("main", locale)
            val body = strings.getValue("recording_entry_privacy_body")
            assertFalse("main/$locale privacy body names OpenFreeMap", body.contains("OpenFreeMap"))
            assertFalse("main/$locale privacy body names Google", body.contains("Google"))
            assertTrue(strings.getValue("recording_entry_privacy_provider_label").contains("%1\$s"))
        }
    }

    @Test
    fun theGoogleDisclosureNamesTheDocumentsTheMapsPlatformTermsRequire() {
        for (locale in locales) {
            val strings = stringsOf("googlePoc", locale)
            assertTrue(strings.getValue("map_provider_disclosure_name").contains("Google"))
            assertTrue(strings.getValue("map_provider_privacy_body").contains("policies.google.com/privacy"))
            assertTrue(strings.getValue("map_provider_terms_body").contains("maps.google.com/help/terms_maps"))
        }
    }

    @Test
    fun theOpenFreeMapDisclosureCarriesTheOpenStreetMapAttribution() {
        for (locale in locales) {
            val strings = stringsOf("mapLibre", locale)
            assertEquals("OpenFreeMap", strings.getValue("map_provider_disclosure_name"))
            assertTrue(strings.getValue("map_provider_privacy_body").contains("OpenFreeMap"))
            val terms = strings.getValue("map_provider_terms_body")
            assertTrue(terms.contains("OpenStreetMap"))
            assertTrue(terms.contains("openstreetmap.org/copyright"))
            assertTrue(terms.contains("OpenMapTiles"))
        }
    }

    private fun stringsOf(sourceSet: String, locale: String): Map<String, String> {
        val file = File(moduleRoot(), "src/$sourceSet/res/$locale/strings.xml")
        require(file.isFile) { "missing ${file.path}" }
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    private fun moduleRoot(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            if (File(directory, "src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        error("app module root not found from " + System.getProperty("user.dir"))
    }
}
