package xyz.aresius.audiobooks.core.player

import android.content.pm.PackageManager
import androidx.media3.common.Player
import androidx.media3.session.MediaSession.ControllerInfo

/** What the platform can tell about a uid (a thin view of `PackageManager`, faked in tests). */
fun interface CallerDirectory {
    /** Packages that run under [uid] (several for a shared uid); empty when unknown or not visible to this app. */
    fun packagesForUid(uid: Int): List<String>
}

class PackageManagerCallerDirectory(private val pm: PackageManager) : CallerDirectory {
    override fun packagesForUid(uid: Int): List<String> = runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()
}

/** What a controller claims and what the platform says about it. */
data class CallerClaim(
    val packageName: String,
    val uid: Int,
    /** `ControllerInfo.isTrusted`: system uid, `MEDIA_CONTENT_CONTROL`, or an enabled notification listener. */
    val platformTrusted: Boolean,
    /** `ControllerInfo.isPackageNameVerified`. */
    val platformVerified: Boolean,
    /** `ControllerInfo.controllerVersion == LEGACY_CONTROLLER_VERSION`: a media button / `MediaControllerCompat` caller. */
    val legacy: Boolean = false,
)

/** What a controller may do. */
enum class ControllerAccess {
    /** Connection refused. */
    NONE,

    /** The standard playback keys only (play/pause/stop/skip/seek): no custom commands, nothing else. */
    KEYS,

    /** Standard player commands and the book-level custom commands ([PlayerCommands]). */
    FULL,
}

data class TrustVerdict(val access: ControllerAccess, val reason: String)

/**
 * Who may control the exported playback service. The service must be `exported` for the lock screen, Bluetooth and Android Auto to bind,
 * so the gate is `PlaybackService`'s `onConnect` (and every custom command again). The pattern is the one of Echoes (`core/auto`):
 *  1. this app (same uid; the package name a controller claims is never enough on its own),
 *  2. trusted by the platform (`ControllerInfo.isTrusted`) or the system uid,
 *  3. an [ALLOWED_PACKAGES] entry (System UI, the Bluetooth stack, Android Auto, Assistant, Wear OS) whose package name is verified against
 *     its uid (Media3 says so, or `getPackagesForUid(uid)` contains it): FULL access;
 *  4. a media-button / legacy caller running under a platform uid (< [FIRST_APPLICATION_UID]: ordinary apps are never assigned one):
 *     [ControllerAccess.KEYS], i.e. the standard playback keys only, no custom commands.
 * Everyone else gets [ControllerAccess.NONE]. To admit another car kit or companion app, add its package to [ALLOWED_PACKAGES] (and `<queries>`).
 */
object TrustedControllers {
    const val SYSTEM_UID = 1000
    const val FIRST_APPLICATION_UID = 10000
    private const val PER_USER_RANGE = 100000

    fun isPlatformUid(uid: Int): Boolean = uid >= 0 && uid % PER_USER_RANGE < FIRST_APPLICATION_UID

    val ALLOWED_PACKAGES: Set<String> = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.projection.gearhead.emulator",
        "com.google.android.gms",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        "com.google.android.carassistant",
        "com.google.android.apps.bard",
        "com.android.systemui",
        "com.android.bluetooth",
        "com.google.android.bluetooth",
        "com.google.android.wearable.app",
        "com.google.android.apps.wear.companion",
    )

    fun decide(claim: CallerClaim, ownUid: Int, directory: CallerDirectory): TrustVerdict {
        if (claim.uid == ownUid) return TrustVerdict(ControllerAccess.FULL, "this app")
        if (claim.platformTrusted) return TrustVerdict(ControllerAccess.FULL, "trusted by the platform")
        if (claim.uid == SYSTEM_UID) return TrustVerdict(ControllerAccess.FULL, "system uid")
        val uidPackages = if (claim.uid >= 0) directory.packagesForUid(claim.uid) else emptyList()
        val verified = claim.platformVerified || claim.packageName in uidPackages
        val allowedPackage = claim.packageName.takeIf { it in ALLOWED_PACKAGES } ?: uidPackages.firstOrNull { it in ALLOWED_PACKAGES }
        return when {
            allowedPackage != null && verified -> TrustVerdict(ControllerAccess.FULL, "allowed package $allowedPackage, verified")
            claim.legacy && isPlatformUid(claim.uid) -> TrustVerdict(ControllerAccess.KEYS, "platform uid ${claim.uid}, media keys only")
            allowedPackage != null -> TrustVerdict(ControllerAccess.NONE, "package name ${claim.packageName} does not match uid ${claim.uid}")
            else -> TrustVerdict(ControllerAccess.NONE, "package ${claim.packageName} is not trusted")
        }
    }
}

/** The playback keys a [ControllerAccess.KEYS] controller gets. */
object KeyCommands {
    val player: Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
    ).build()
}

fun ControllerInfo.claim() = CallerClaim(
    packageName = packageName,
    uid = uid,
    platformTrusted = isTrusted,
    platformVerified = isPackageNameVerified,
    legacy = controllerVersion == ControllerInfo.LEGACY_CONTROLLER_VERSION,
)
