package xyz.aresius.audiobooks.debug

import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.aresius.audiobooks.core.auth.CodeFormat

class FakeBackendCodesTest {
    @Test fun theDemoInviteIsAValidCodeSoTheSignInScreenAcceptsIt() {
        assertTrue(CodeFormat.isValid(CodeFormat.normalize(FakeBackend.DEMO_INVITE)), FakeBackend.DEMO_INVITE)
    }
}
