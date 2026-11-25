package xyz.aresius.audiobooks.feature.onboarding

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.auth.AuthError
import xyz.aresius.audiobooks.core.auth.BuildInfo
import xyz.aresius.audiobooks.core.auth.GoogleTokenResult
import xyz.aresius.audiobooks.core.auth.InputProblem
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.ScriptedGoogle
import xyz.aresius.audiobooks.testutil.TestCodes
import xyz.aresius.audiobooks.testutil.UnusedTokenSource
import xyz.aresius.audiobooks.testutil.googleAuth
import xyz.aresius.audiobooks.testutil.awaitState

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server, session = false)

    @AfterTest fun tearDown() = server.close()

    private fun vm(debugTools: Boolean = false) = OnboardingViewModel(app.auth(), app.googleAuth(), UnusedTokenSource(), app.sessions, app.prefs, BuildInfo("0.3.0", 300, debugTools, "Pixel 9"))

    private fun version(bootstrapped: Boolean) = server.onJson("GET", "/v1/version", """{"version":"0.4.0","api_version":1,"bootstrapped":$bootstrapped}""")

    @Test fun startsOnTheServerStepWithTheStoredUrlAndTheDeviceModel() = runBlocking<Unit> {
        val vm = vm()
        val s = vm.state.awaitState { it.serverUrl.isNotEmpty() }
        assertEquals(OnboardingStep.Server, s.step)
        assertEquals("https://earmark.aresius.xyz", s.serverUrl)
        assertEquals("Pixel 9", s.deviceName)
        assertFalse(s.showDemoShortcut)
        assertTrue(vm().state.value.showDemoShortcut.not())
        assertTrue(vm(debugTools = true).state.value.showDemoShortcut)
    }

    @Test fun anUnreachableOrWrongUrlStaysOnTheStepWithTheError() = runBlocking<Unit> {
        val vm = vm()
        vm.onServerUrlChange("")
        vm.checkServer()
        assertEquals(AuthError.InvalidInput(InputProblem.UrlEmpty), vm.state.awaitState { it.error != null }.error)
        vm.onServerUrlChange(server.baseUrl)
        server.on("GET", "/v1/version", FakeServer.error(404, "not_found"))
        vm.checkServer()
        val s = vm.state.awaitState { it.error is AuthError.Server }
        assertEquals(OnboardingStep.Server, s.step)
        assertFalse(s.checkingServer)
    }

    @Test fun aFreshInstanceSuggestsFirstSetup() = runBlocking<Unit> {
        version(bootstrapped = false)
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.checkServer()
        val s = vm.state.awaitState { it.step == OnboardingStep.Method }
        assertEquals(false, s.bootstrapped)
        assertEquals(OnboardingMethod.FirstSetup, s.method)
    }

    @Test fun anExistingInstanceDefaultsToInviteAndTheThreeWaysAreSelectable() = runBlocking<Unit> {
        version(bootstrapped = true)
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.checkServer()
        assertEquals(OnboardingMethod.Invite, vm.state.awaitState { it.step == OnboardingStep.Method }.method)
        vm.chooseMethod(OnboardingMethod.Link)
        assertEquals(OnboardingStep.Details, vm.state.value.step)
        assertFalse(vm.state.value.needsDisplayName)
        vm.back()
        assertEquals(OnboardingStep.Method, vm.state.value.step)
        vm.chooseMethod(OnboardingMethod.FirstSetup)
        assertTrue(vm.state.value.needsDisplayName)
        vm.back(); vm.back()
        assertEquals(OnboardingStep.Server, vm.state.value.step)
    }

    @Test fun joiningSignsInAndMovesToTheDebridStepAndDropsTheCode() = runBlocking<Unit> {
        version(bootstrapped = true)
        server.onJson("POST", "/v1/auth/join", Samples.session(Samples.ME_USER))
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.checkServer()
        vm.state.awaitState { it.step == OnboardingStep.Method }
        vm.chooseMethod(OnboardingMethod.Invite)
        vm.onSecretChange(TestCodes.INVITE.chunked(5).joinToString(" ").lowercase())
        vm.onDisplayNameChange("Bob")
        vm.signIn()
        val s = vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertEquals("", s.secret)
        assertNotNull(app.sessions.load())
        assertTrue(server.lastBody("POST", "/v1/auth/join").contains(TestCodes.INVITE))
        assertFalse(app.prefs.doneNow, "onboarding is not over until the last step")
        vm.debridDone()
        assertEquals(OnboardingStep.BitTorrent, vm.state.value.step)
        vm.finish()
        app.prefs.onboardingDone.awaitDone()
    }

    @Test fun aBadCodeShowsTheErrorAndKeepsTheInput() = runBlocking<Unit> {
        server.on("POST", "/v1/auth/join", FakeServer.error(401, "unauthorized"))
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.chooseMethod(OnboardingMethod.Invite)
        vm.onSecretChange(TestCodes.OTHER)
        vm.onDisplayNameChange("Bob")
        vm.signIn()
        val s = vm.state.awaitState { it.error != null }
        assertEquals(AuthError.BadCredentials, s.error)
        assertEquals(TestCodes.OTHER, s.secret)
        assertEquals(OnboardingStep.Details, s.step)
        assertNull(app.sessions.load())
        vm.onSecretChange(TestCodes.INVITE)
        assertNull(vm.state.value.error)
    }

    @Test fun linkingSendsNoName() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/link", Samples.session())
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.chooseMethod(OnboardingMethod.Link)
        vm.onSecretChange(TestCodes.LINK)
        vm.signIn()
        vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertFalse(server.lastBody("POST", "/v1/auth/link").contains("display_name"))
    }

    @Test fun aSessionWithoutTheDoneFlagResumesAtTheDebridStep() = runBlocking<Unit> {
        val resumed = TestApp(server, session = true)
        val vm = OnboardingViewModel(resumed.auth(), resumed.googleAuth(), UnusedTokenSource(), resumed.sessions, resumed.prefs, BuildInfo("0.3.0", 300, false))
        assertEquals(OnboardingStep.Debrid, vm.state.awaitState { it.step == OnboardingStep.Debrid }.step)
    }

    @Test fun theDemoShortcutFillsTheDemoHost() {
        val vm = vm(debugTools = true)
        vm.useDemoServer()
        assertEquals(OnboardingViewModel.DEMO_SERVER_URL, vm.state.value.serverUrl)
    }

    // --- Sign in with Google -------------------------------------------------------------------------------------

    private fun googleVersion(enabled: Boolean) =
        server.onJson("GET", "/v1/version", """{"version":"0.4.0","api_version":1,"bootstrapped":true,"google_enabled":$enabled}""")

    private suspend fun OnboardingViewModel.reachMethodStep() {
        onServerUrlChange(server.baseUrl)
        checkServer()
        state.awaitState { it.step == OnboardingStep.Method }
    }

    private fun nonces(vararg values: String) =
        server.on("GET", "/v1/auth/google/nonce", *values.map { FakeServer.json("""{"nonce":"$it","expires_at":"2026-11-25T10:05:00Z"}""") }.toTypedArray())

    @Test fun theGoogleCardOnlyShowsWhenTheServerHasGoogleEnabled() = runBlocking<Unit> {
        googleVersion(enabled = false)
        val off = vm()
        off.reachMethodStep()
        assertFalse(off.state.value.googleEnabled)
        googleVersion(enabled = true)
        val on = vm()
        on.reachMethodStep()
        assertTrue(on.state.value.googleEnabled)
        version(bootstrapped = true) // an older server without the field
        val old = vm()
        old.reachMethodStep()
        assertFalse(old.state.value.googleEnabled)
    }

    @Test fun continueWithGoogleSignsInAndMovesToTheDebridStep() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1")
        server.onJson("POST", "/v1/auth/google", Samples.session(Samples.ME_USER))
        val vm = vm()
        vm.reachMethodStep()
        vm.continueWithGoogle(ScriptedGoogle())
        val s = vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertFalse(s.busy)
        assertNotNull(app.sessions.load())
        assertTrue(server.lastBody("POST", "/v1/auth/google").contains(""""device_name":"Pixel 9""""), "the model name is the device name")
    }

    @Test fun anUnlinkedGoogleAccountOffersJoiningWithAnInviteAndPrefillsTheGivenName() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1", "n2")
        server.on("POST", "/v1/auth/google", FakeServer.error(401, "google_not_linked"))
        server.onJson("POST", "/v1/auth/google/join", Samples.session(Samples.ME_USER))
        val vm = vm()
        vm.reachMethodStep()
        val google = ScriptedGoogle(ScriptedGoogle.token(given = "Grace"))
        vm.continueWithGoogle(google)
        val s = vm.state.awaitState { it.method == OnboardingMethod.GoogleJoin }
        assertEquals(OnboardingStep.Details, s.step)
        assertEquals(AuthError.GoogleNotLinked, s.error)
        assertEquals("Grace", s.displayName)
        assertEquals("", s.secret)
        assertNull(app.sessions.load())
        vm.onSecretChange(TestCodes.INVITE)
        vm.joinWithGoogle(google)
        vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertEquals(listOf("n1", "n2"), google.nonces, "joining asks Google again with a fresh nonce")
        val body = server.lastBody("POST", "/v1/auth/google/join")
        assertTrue(body.contains("id-token-for-n2") && body.contains(TestCodes.INVITE) && body.contains(""""display_name":"Grace""""), body)
    }

    @Test fun aFailedJoinKeepsTheFormAndTheNextTryUsesFreshCredentials() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1", "n2", "n3")
        server.on("POST", "/v1/auth/google", FakeServer.error(401, "google_not_linked"))
        server.on("POST", "/v1/auth/google/join", FakeServer.error(409, "conflict"), FakeServer.json(Samples.session(Samples.ME_USER)))
        val vm = vm()
        vm.reachMethodStep()
        val google = ScriptedGoogle()
        vm.continueWithGoogle(google)
        vm.state.awaitState { it.method == OnboardingMethod.GoogleJoin }
        vm.onSecretChange(TestCodes.INVITE)
        vm.joinWithGoogle(google)
        val failed = vm.state.awaitState { it.error == AuthError.GoogleConflict }
        assertEquals(OnboardingStep.Details, failed.step)
        assertEquals(TestCodes.INVITE, failed.secret)
        vm.joinWithGoogle(google)
        vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertEquals(listOf("n1", "n2", "n3"), google.nonces)
    }

    @Test fun cancellingTheGoogleSheetChangesNothing() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1")
        val vm = vm()
        vm.reachMethodStep()
        vm.continueWithGoogle(ScriptedGoogle(ScriptedGoogle.cancelled()))
        val s = vm.state.awaitState { !it.busy }
        assertEquals(OnboardingStep.Method, s.step)
        assertNull(s.error)
    }

    @Test fun noGoogleAccountAndNoPlayServicesAreExplainedOnTheMethodStep() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1")
        val vm = vm()
        vm.reachMethodStep()
        vm.continueWithGoogle(ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.NoAccount)))
        assertEquals(AuthError.GoogleNoAccount, vm.state.awaitState { it.error != null }.error)
        vm.continueWithGoogle(ScriptedGoogle(ScriptedGoogle.result(GoogleTokenResult.Unavailable)))
        assertEquals(AuthError.GoogleUnavailable, vm.state.awaitState { it.error == AuthError.GoogleUnavailable }.error)
        assertEquals(OnboardingStep.Method, vm.state.value.step)
    }

    @Test fun backFromTheJoinFormGoesToTheMethodStepAndDropsTheNotice() = runBlocking<Unit> {
        googleVersion(enabled = true)
        nonces("n1")
        server.on("POST", "/v1/auth/google", FakeServer.error(401, "google_not_linked"))
        val vm = vm()
        vm.reachMethodStep()
        vm.continueWithGoogle(ScriptedGoogle())
        vm.state.awaitState { it.method == OnboardingMethod.GoogleJoin }
        vm.back()
        assertEquals(OnboardingStep.Method, vm.state.value.step)
        assertNull(vm.state.value.error)
    }

    @Test fun aPastedOrScannedCodeIsShownInBlocksAndAnythingElseIsFlagged() = runBlocking<Unit> {
        val vm = vm()
        vm.chooseMethod(OnboardingMethod.Link)
        vm.onCodePasted("  " + TestCodes.LINK.lowercase().chunked(4).joinToString("-") + "\n")
        assertEquals(TestCodes.LINK.chunked(5).joinToString("-"), vm.state.value.secret)
        assertNull(vm.state.value.error)
        vm.onScanResult(ScanResult.Text("https://example.test/not-a-code"))
        assertEquals(AuthError.InvalidInput(InputProblem.CodeInvalid), vm.state.value.error)
        assertEquals(TestCodes.LINK.chunked(5).joinToString("-").length, 31, "sanity")
        vm.onScanResult(ScanResult.Text(TestCodes.INVITE))
        assertEquals(TestCodes.INVITE.chunked(5).joinToString("-"), vm.state.value.secret)
        assertNull(vm.state.value.error)
        vm.onScanResult(ScanResult.Cancelled)
        assertNull(vm.state.value.error)
        vm.onScanResult(ScanResult.Unavailable)
        assertEquals(AuthError.ScannerUnavailable, vm.state.value.error)
        assertEquals(TestCodes.INVITE.chunked(5).joinToString("-"), vm.state.value.secret, "the field keeps its text: the user pastes instead")
    }

    @Test fun aScannedLinkCodeThenSignsIn() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/link", Samples.session())
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.chooseMethod(OnboardingMethod.Link)
        vm.onScanResult(ScanResult.Text(TestCodes.LINK))
        vm.signIn()
        vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertTrue(server.lastBody("POST", "/v1/auth/link").contains(""""link_code":"${TestCodes.LINK}""""))
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.awaitDone() {
        kotlinx.coroutines.withTimeout(3000) { first { it } }
    }
}
