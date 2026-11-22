package dev.multisystem.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PageSerializationTest {
    @Test fun navigationKeysSurviveStateSerialization() {
        listOf(Page.Main, Page.Theme, Page.About, Page.Import, Page.Sources, Page.CompatibilityGuide).forEach { page ->
            assertEquals(page, Json.decodeFromString<Page>(Json.encodeToString<Page>(page)))
        }
    }
}
