package xyz.aresius.audiobooks.core.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Set by the navigation host so a cover in a list can fly into the Book detail without every screen knowing about transitions. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks a cover as the shared element of book [asin]. Use it for ONE cover per book per screen (a key may exist only once at a time);
 * without a transition scope (previews, tests) it does nothing.
 */
@Composable
fun Modifier.coverSharedElement(asin: String): Modifier {
    val transition = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedScope.current ?: return this
    return with(transition) {
        this@coverSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key = "cover-$asin"),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> Motion.spatial() },
        )
    }
}
