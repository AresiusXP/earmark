package xyz.aresius.audiobooks.app

import kotlinx.serialization.Serializable

/** Type-safe navigation routes of the signed-in app (onboarding is not part of the graph: it is a gate, see AppRoot). */
object Routes {
    @Serializable data object Discover
    @Serializable data object Search
    @Serializable data object Library
    @Serializable data object Settings
    @Serializable data class Book(val asin: String)
    @Serializable data object Users
}
