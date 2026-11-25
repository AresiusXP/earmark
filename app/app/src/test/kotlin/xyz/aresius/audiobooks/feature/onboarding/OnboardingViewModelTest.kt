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
import xyz.aresius.audiobooks.core.auth.InputProblem
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server, session = false)

    @AfterTest fun tearDown() = server.close()

    private fun vm(debugTools: Boolean = false) = OnboardingViewModel(app.auth(), app.sessions, app.prefs, BuildInfo("0.3.0", 300, debugTools, "Pixel 9"))

    private fun version(bootstrapped: Boolean) = server.onJson("GET", "/v1/version", """{"version":"0.4.0","api_version":1,"bootstrapped":$bootstrapped}""")

    @Test fun startsOnTheServerStepWithTheStoredUrlAndTheDeviceModel() = runBlocking<Unit> {
        val vm = vm()
        val s = vm.state.awaitState { it.serverUrl.isNotEmpty() }
        assertEquals(OnboardingStep.Server, s.step)
        assertEquals("https://audiobooks.aresius.xyz", s.serverUrl)
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
        vm.onSecretChange("INV-123")
        vm.onDisplayNameChange("Bob")
        vm.signIn()
        val s = vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertEquals("", s.secret)
        assertNotNull(app.sessions.load())
        assertTrue(server.lastBody("POST", "/v1/auth/join").contains("INV-123"))
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
        vm.onSecretChange("WRONG")
        vm.onDisplayNameChange("Bob")
        vm.signIn()
        val s = vm.state.awaitState { it.error != null }
        assertEquals(AuthError.BadCredentials, s.error)
        assertEquals("WRONG", s.secret)
        assertEquals(OnboardingStep.Details, s.step)
        assertNull(app.sessions.load())
        vm.onSecretChange("RIGHT")
        assertNull(vm.state.value.error)
    }

    @Test fun linkingSendsNoName() = runBlocking<Unit> {
        server.onJson("POST", "/v1/auth/link", Samples.session())
        val vm = vm()
        vm.onServerUrlChange(server.baseUrl)
        vm.chooseMethod(OnboardingMethod.Link)
        vm.onSecretChange("LINK-9")
        vm.signIn()
        vm.state.awaitState { it.step == OnboardingStep.Debrid }
        assertFalse(server.lastBody("POST", "/v1/auth/link").contains("display_name"))
    }

    @Test fun aSessionWithoutTheDoneFlagResumesAtTheDebridStep() = runBlocking<Unit> {
        val resumed = TestApp(server, session = true)
        val vm = OnboardingViewModel(resumed.auth(), resumed.sessions, resumed.prefs, BuildInfo("0.3.0", 300, false))
        assertEquals(OnboardingStep.Debrid, vm.state.awaitState { it.step == OnboardingStep.Debrid }.step)
    }

    @Test fun theDemoShortcutFillsTheDemoHost() {
        val vm = vm(debugTools = true)
        vm.useDemoServer()
        assertEquals(OnboardingViewModel.DEMO_SERVER_URL, vm.state.value.serverUrl)
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.awaitDone() {
        kotlinx.coroutines.withTimeout(3000) { first { it } }
    }
}
