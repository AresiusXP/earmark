package xyz.aresius.audiobooks.core.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Spring motion in the spirit of the Material 3 expressive motion scheme: spatial springs may overshoot a little (movement, size),
 * effects springs never do (color, alpha). Use these instead of tweens, except for the cover color cross-fade.
 */
object Motion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.65f, stiffness = 800f)
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** The cover-derived background changes slowly, like a page turning. */
    const val COVER_COLOR_MS = 700
}
