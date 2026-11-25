package xyz.aresius.audiobooks.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InputValidationTest {
    private fun url(input: String, anyHost: Boolean = false) = InputValidation.validateUrl(input, anyHost)

    @Test fun httpsIsTheDefaultSchemeAndTheUrlIsNormalised() {
        assertEquals(UrlValidation.Valid("https://audiobooks.aresius.xyz"), url(" audiobooks.aresius.xyz/ "))
        assertEquals(UrlValidation.Valid("https://host.example/prefix"), url("https://host.example:443/prefix/"))
        assertEquals(UrlValidation.Valid("https://host.example:8443"), url("https://host.example:8443"))
    }

    @Test fun plainHttpOnlyForLocalHostsInReleaseBuilds() {
        assertEquals(UrlValidation.Valid("http://localhost:8080"), url("http://localhost:8080"))
        assertEquals(UrlValidation.Valid("http://10.0.2.2:8080"), url("http://10.0.2.2:8080"))
        assertEquals(UrlValidation.Valid("http://127.0.0.1:7000"), url("http://127.0.0.1:7000"))
        assertEquals(UrlValidation.Invalid(InputProblem.UrlNeedsHttps), url("http://192.168.1.20:8080"))
        assertEquals(UrlValidation.Invalid(InputProblem.UrlNeedsHttps), url("http://audiobooks.aresius.xyz"))
    }

    @Test fun debugBuildsMayUseHttpEverywhere() {
        assertEquals(UrlValidation.Valid("http://192.168.1.20:8080"), url("http://192.168.1.20:8080", anyHost = true))
        assertEquals(UrlValidation.Valid("http://demo.audiobooks.test"), url("http://demo.audiobooks.test", anyHost = true))
    }

    @Test fun emptyAndBrokenUrlsAreRefused() {
        assertEquals(UrlValidation.Invalid(InputProblem.UrlEmpty), url("   "))
        assertEquals(UrlValidation.Invalid(InputProblem.UrlInvalid), url("https://"))
        assertEquals(UrlValidation.Invalid(InputProblem.UrlInvalid), url("not a url"))
    }

    @Test fun nameLimitsFollowTheContract() {
        assertNull(InputValidation.validateDisplayName("Ada"))
        assertEquals(InputProblem.NameEmpty, InputValidation.validateDisplayName("  "))
        assertEquals(InputProblem.NameTooLong, InputValidation.validateDisplayName("x".repeat(41)))
        assertNull(InputValidation.validateDisplayName("x".repeat(40)))
        assertNull(InputValidation.validateDeviceName("x".repeat(80)))
        assertEquals(InputProblem.DeviceNameTooLong, InputValidation.validateDeviceName("x".repeat(81)))
        assertEquals(InputProblem.DeviceNameEmpty, InputValidation.validateDeviceName(""))
    }
}
