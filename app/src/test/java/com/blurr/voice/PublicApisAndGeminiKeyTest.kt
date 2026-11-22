package com.blurr.voice

import com.blurr.voice.publicapis.PublicApisRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApisAndGeminiKeyTest {

    @Test
    fun parseReadmeMarkdown_extractsCategoriesAndRows() {
        val sampleMarkdown = """
            ### Index
            * [Animals](#animals)

            ### Animals
            API | Description | Auth | HTTPS | CORS
            |---|---|---|---|---|
            | [Cat Facts](https://alexwohlbruck.github.io/cat-facts/) | Daily cat facts | No | Yes | No |
            | [AdoptAPet](https://www.adoptapet.com/public/apis/pet_list.html) | Resource to help get pets adopted | `apiKey` | Yes | Yes |

            ### Weather
            API | Description | Auth | HTTPS | CORS
            |---|---|---|---|---|
            | [Open-Meteo](https://open-meteo.com/) | Open-source weather forecast API | No | Yes | Yes |
        """.trimIndent()

        val parsed = PublicApisRepository.parseReadmeMarkdown(sampleMarkdown)
        assertEquals(3, parsed.size)

        val catFacts = parsed[0]
        assertEquals("Cat Facts", catFacts.api)
        assertEquals("Animals", catFacts.category)
        assertEquals("No", catFacts.auth)
        assertTrue(catFacts.https)

        val adoptAPet = parsed[1]
        assertEquals("apiKey", adoptAPet.auth)

        val filteredNoAuthWeather = PublicApisRepository.filter(
            entries = parsed,
            query = "weather",
            category = "Weather",
            noAuthOnly = true,
            httpsOnly = true
        )
        assertEquals(1, filteredNoAuthWeather.size)
        assertEquals("Open-Meteo", filteredNoAuthWeather[0].api)

        val filteredApiKey = PublicApisRepository.filter(
            entries = parsed,
            query = "pets",
            category = "All",
            noAuthOnly = true,
            httpsOnly = true
        )
        assertFalse(filteredApiKey.any { it.api == "AdoptAPet" })
    }
}
