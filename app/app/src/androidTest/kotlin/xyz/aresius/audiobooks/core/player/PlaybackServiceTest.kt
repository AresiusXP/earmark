package xyz.aresius.audiobooks.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The service starts through its media session and speaks the book-level commands; an unknown command is refused. */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var controller: MediaController? = null

    @After fun tearDown() {
        controller?.let { c -> onMain { c.release() } }
    }

    private fun connect(): MediaController {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = onMain { MediaController.Builder(context, token).buildAsync() }
        return future.get(15, TimeUnit.SECONDS).also { controller = it }
    }

    @Test fun connectsAndOffersTheStandardAndTheBookCommands() {
        val c = connect()
        onMain {
            assertTrue(c.isConnected)
            assertTrue(c.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(c.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH))
        }
        val close = onMain { c.sendCustomCommand(SessionCommand(PlayerCommands.CLOSE, Bundle.EMPTY), Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, close.resultCode)
        val unknown = onMain { c.sendCustomCommand(SessionCommand("xyz.unknown", Bundle.EMPTY), Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        assertTrue(unknown.resultCode != SessionResult.RESULT_SUCCESS)
    }
}
