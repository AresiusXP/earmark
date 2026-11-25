package xyz.aresius.audiobooks.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.aresius.audiobooks.core.network.Provider
import xyz.aresius.audiobooks.core.network.ReleaseView
import xyz.aresius.audiobooks.core.network.Usable

class ReleaseChooserTest {
    private fun release(hash: String, debrid: List<String> = emptyList(), cached: Map<String, Boolean> = emptyMap()) =
        ReleaseView(infoHash = hash, cached = cached, usable = Usable(debrid = debrid))

    @Test
    fun `no releases, no choice`() {
        assertNull(ReleaseChooser.choose(emptyList(), null, null))
    }

    @Test
    fun `cached usable release beats a better ranked uncached one`() {
        val list = listOf(release("a", listOf("torbox"), mapOf("torbox" to false)), release("b", listOf("torbox"), mapOf("torbox" to true)), release("c", listOf("torbox")))
        val choice = ReleaseChooser.choose(list, null, null)!!
        assertEquals("b", choice.release.infoHash)
        assertEquals(Provider.TorBox, choice.provider)
    }

    @Test
    fun `without a cached one the best ranked usable release wins and unusable ones are skipped`() {
        val list = listOf(release("a"), release("b", listOf("realdebrid")), release("c", listOf("torbox")))
        assertEquals("b", ReleaseChooser.choose(list, null, null)!!.release.infoHash)
    }

    @Test
    fun `the remembered choice wins while it exists`() {
        val list = listOf(release("a", listOf("torbox"), mapOf("torbox" to true)), release("b", listOf("torbox")))
        assertEquals("b", ReleaseChooser.choose(list, "b", null)!!.release.infoHash)
        assertEquals("a", ReleaseChooser.choose(list, "gone", null)!!.release.infoHash)
    }

    @Test
    fun `the preferred provider is used when the release is usable with it, else a cached one`() {
        val r = release("a", listOf("torbox", "realdebrid"), mapOf("torbox" to false, "realdebrid" to true))
        assertEquals(Provider.RealDebrid, ReleaseChooser.providerFor(r, null))
        assertEquals(Provider.TorBox, ReleaseChooser.providerFor(r, Provider.TorBox))
        assertEquals(Provider.RealDebrid, ReleaseChooser.providerFor(release("x", listOf("realdebrid")), Provider.TorBox))
        assertNull(ReleaseChooser.providerFor(release("y"), Provider.TorBox))
    }

    @Test
    fun `with no key the best release that has local files, else the first, with no provider`() {
        val list = listOf(release("a"), release("b"), release("c"))
        val local = ReleaseChooser.choose(list, null, null, hasLocal = { it.infoHash == "c" })!!
        assertEquals("c", local.release.infoHash)
        assertNull(local.provider)
        assertEquals("a", ReleaseChooser.choose(list, null, null)!!.release.infoHash)
    }
}
