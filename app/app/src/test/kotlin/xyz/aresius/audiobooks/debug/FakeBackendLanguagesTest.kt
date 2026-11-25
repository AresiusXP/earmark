package xyz.aresius.audiobooks.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.SettingsPatch
import xyz.aresius.audiobooks.core.network.UpdateMeRequest

/** The demo backend takes "es" in `settings.languages` and lists its Spanish books only then. */
class FakeBackendLanguagesTest {
    private val api: AudiobooksApi = Retrofit.Builder()
        .baseUrl("http://${FakeBackend.HOST}/")
        .client(OkHttpClient.Builder().addInterceptor(FakeBackend(latencyMs = 0)).build())
        .addConverterFactory(AudiobooksJson.asConverterFactory("application/json".toMediaType()))
        .build().create(AudiobooksApi::class.java)

    private fun discoverAll() = runBlocking { (0..3).flatMap { api.discover(it).books } }

    @Test fun spanishBooksAppearOnlyWithSpanishSelected() = runBlocking<Unit> {
        assertTrue(discoverAll().none { it.language == "es" })
        assertTrue(api.search("Quijote").books.isEmpty())

        val me = api.updateMe(UpdateMeRequest(settings = SettingsPatch(languages = listOf("en", "es"))))
        assertEquals(listOf("en", "es"), me.settings.languages)
        val spanish = discoverAll().filter { it.language == "es" }
        assertEquals(listOf("Don Quijote de la Mancha", "Martín Fierro"), spanish.map { it.title })
        assertEquals("Don Quijote de la Mancha", api.search("Quijote").books.single().title)
        assertEquals("es", api.getBook(spanish.first().asin).book.language)

        api.updateMe(UpdateMeRequest(settings = SettingsPatch(languages = listOf("en"))))
        assertTrue(discoverAll().none { it.language == "es" })
    }

    @Test fun anEmptyLanguageListIsStillRefused() = runBlocking<Unit> {
        val code = try { api.updateMe(UpdateMeRequest(settings = SettingsPatch(languages = emptyList()))); 200 } catch (e: HttpException) { e.code() }
        assertEquals(400, code)
    }
}
