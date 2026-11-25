package xyz.aresius.audiobooks.feature.admin

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.aresius.audiobooks.core.network.ApiError
import xyz.aresius.audiobooks.core.network.AudiobooksJson
import xyz.aresius.audiobooks.core.network.ErrorCode
import xyz.aresius.audiobooks.core.network.Me
import xyz.aresius.audiobooks.feature.common.Load
import xyz.aresius.audiobooks.testutil.FakeServer
import xyz.aresius.audiobooks.testutil.MainDispatcherRule
import xyz.aresius.audiobooks.testutil.Samples
import xyz.aresius.audiobooks.testutil.TestApp
import xyz.aresius.audiobooks.testutil.awaitState

class UsersViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val server = FakeServer()
    private val app = TestApp(server)

    @AfterTest fun tearDown() = server.close()

    private fun user(id: String, name: String, disabled: Boolean = false, role: String = "user") =
        """{"id":"$id","display_name":"$name","role":"$role","disabled":$disabled,"created_at":"2026-11-25T10:00:00Z","device_count":1,"debrid":{"torbox":true}}"""

    private fun setup(): UsersViewModel {
        runBlocking { app.me.set(AudiobooksJson.decodeFromString(Me.serializer(), Samples.ME_ADMIN)) } // id u1
        server.onJson("GET", "/v1/admin/users", """{"users":[${user("u1", "Ada", role = "admin")},${user("u2", "Bob")}]}""")
        server.onJson("GET", "/v1/admin/invites", """{"invites":[{"id":"i1","note":"for Cy","created_at":"2026-11-25T10:00:00Z","expires_at":"2026-11-28T10:00:00Z"}]}""")
        return UsersViewModel(app.admin, app.me)
    }

    @Test fun loadsUsersAndInvites() = runBlocking<Unit> {
        val vm = setup()
        val s = vm.state.awaitState { it.users is Load.Content<*> && it.invites is Load.Content<*> }
        assertEquals(listOf("Ada", "Bob"), (s.users as Load.Content).value.map { it.displayName })
        assertEquals("u1", s.currentUserId)
        assertEquals(1, (s.invites as Load.Content).value.size)
    }

    @Test fun aNonAdminSeesTheForbiddenError() = runBlocking<Unit> {
        server.on("GET", "/v1/admin/users", FakeServer.error(403, "forbidden", "admin only"))
        server.on("GET", "/v1/admin/invites", FakeServer.error(403, "forbidden", "admin only"))
        val vm = UsersViewModel(app.admin, app.me)
        val failed = vm.state.awaitState { it.users is Load.Failed }.users as Load.Failed
        assertEquals(ErrorCode.Forbidden, (failed.error as ApiError.Http).code)
    }

    @Test fun disablingAUserUpdatesTheListAndYouCannotDisableYourself() = runBlocking<Unit> {
        val vm = setup()
        val s = vm.state.awaitState { it.users is Load.Content<*> }
        val users = (s.users as Load.Content).value
        vm.setDisabled(users[0], true) // yourself
        assertTrue(server.requestsTo("PATCH", "/v1/admin/users/u1").isEmpty())
        server.onJson("PATCH", "/v1/admin/users/u2", user("u2", "Bob", disabled = true))
        vm.setDisabled(users[1], true)
        val after = vm.state.awaitState { st -> (st.users as? Load.Content)?.value?.get(1)?.disabled == true }
        assertNull(after.busyUserId)
        assertEquals("""{"disabled":true}""", server.lastBody("PATCH", "/v1/admin/users/u2"))
    }

    @Test fun theServersRefusalIsShown() = runBlocking<Unit> {
        val vm = setup()
        val users = ((vm.state.awaitState { it.users is Load.Content<*> }).users as Load.Content).value
        server.on("PATCH", "/v1/admin/users/u2", FakeServer.error(409, "conflict", "last admin"))
        vm.setDisabled(users[1], true)
        assertEquals(ErrorCode.Conflict, (vm.state.awaitState { it.error != null }.error as ApiError.Http).code)
        vm.consumeError()
        assertNull(vm.state.value.error)
    }

    @Test fun aCreatedInviteShowsItsCodeOnceAndJoinsTheList() = runBlocking<Unit> {
        val vm = setup()
        vm.state.awaitState { it.invites is Load.Content<*> }
        server.onJson("POST", "/v1/admin/invites", """{"id":"i2","note":"for Di","created_at":"2026-11-25T10:00:00Z","expires_at":"2026-11-28T10:00:00Z","code":"INV-SECRET"}""")
        vm.createInvite("for Di", 72)
        val s = vm.state.awaitState { it.createdInvite != null }
        assertEquals("INV-SECRET", s.createdInvite?.code)
        assertEquals(2, (s.invites as Load.Content).value.size)
        assertFalse(s.toString().contains("INV-SECRET"))
        vm.dismissCreatedInvite()
        assertNull(vm.state.value.createdInvite)
    }

    @Test fun revokingAnInviteRemovesIt() = runBlocking<Unit> {
        val vm = setup()
        val invite = ((vm.state.awaitState { it.invites is Load.Content<*> }).invites as Load.Content).value.single()
        server.on("DELETE", "/v1/admin/invites/i1", FakeServer.empty())
        vm.revokeInvite(invite)
        val s = vm.state.awaitState { (it.invites as? Load.Content)?.value?.isEmpty() == true }
        assertNull(s.busyInviteId)
        assertIs<Load.Content<*>>(s.invites)
    }
}
