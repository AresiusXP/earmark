package xyz.aresius.audiobooks.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.aresius.audiobooks.testutil.TestCodes

class CodeFormatTest {
    private val code = TestCodes.INVITE

    @Test fun aServerCodeWithDashesInGroupsOfFourIsValid() {
        val server = code.chunked(4).joinToString("-")
        assertEquals(code, CodeFormat.parse(server))
    }

    @Test fun caseSpacesDashesAndLineBreaksAreForgiven() {
        val messy = "  " + code.lowercase().chunked(5).joinToString(" - ") + " \n"
        assertEquals(code, CodeFormat.parse(messy))
        assertEquals(code, CodeFormat.parse(code.lowercase().chunked(6).joinToString("\u00A0")))
        assertEquals(code, CodeFormat.parse(code.chunked(7).joinToString("\u2013")))
    }

    @Test fun lookAlikesAreFoldedTheWayTheServerDoesIt() {
        val base = "0123456789ABCDEFGHJKMNPQRS" // starts with 0 and 1
        assertTrue(CodeFormat.isValid(base))
        assertEquals(base, CodeFormat.parse("o" + "l" + base.drop(2)))
        assertEquals(base, CodeFormat.parse("O" + "I" + base.drop(2)))
    }

    @Test fun uIsNotInTheAlphabetAndLengthMustBeExact() {
        assertNull(CodeFormat.parse("U" + code.drop(1)))
        assertNull(CodeFormat.parse(code.dropLast(1)))
        assertNull(CodeFormat.parse(code + "A"))
        assertNull(CodeFormat.parse(""))
        assertNull(CodeFormat.parse("   "))
        assertNull(CodeFormat.parse(code.dropLast(1) + "!"))
        assertNull(CodeFormat.parse("https://example.test/" + code))
    }

    @Test fun normalizeKeepsInvalidCharactersSoTheyCanBeCounted() {
        assertEquals("AB!C", CodeFormat.normalize("a b-!c"))
        assertFalse(CodeFormat.isValid(CodeFormat.normalize("a b-!c")))
    }

    @Test fun groupedShowsBlocksOfFive() {
        assertEquals("K7QD9 XMP4H NTZW2 BC5VR YE3FG A", CodeFormat.grouped(code))
        assertEquals("K7QD9-XMP4H-NTZW2-BC5VR-YE3FG-A", CodeFormat.grouped(code, "-"))
        assertEquals(code, CodeFormat.parse(CodeFormat.grouped(code)))
    }

    @Test fun everyCharacterOfTheAlphabetSurvivesNormalisationUnlessItIsALookAlike() {
        CodeFormat.ALPHABET.forEach { c -> assertEquals(c.toString(), CodeFormat.normalize(c.toString())) }
        assertEquals(32, CodeFormat.ALPHABET.length)
    }
}
