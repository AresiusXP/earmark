package xyz.aresius.audiobooks.feature.discover

import java.util.Locale

enum class Greeting { Morning, Afternoon, Evening, Night }

/** Greeting for an hour of the day (0..23): morning from 5, afternoon from 12, evening from [eveningFrom] (default 18), night from 23. */
fun greetingForHour(hour: Int, eveningFrom: Int = 18): Greeting = when {
    hour in 5..11 -> Greeting.Morning
    hour in 12 until eveningFrom -> Greeting.Afternoon
    hour in eveningFrom..22 -> Greeting.Evening
    else -> Greeting.Night
}

/** Spanish keeps "Buenas tardes" until 20:00 (dusk comes late in the Spanish-speaking world); other languages switch at 18:00. */
fun eveningFromHour(language: String = Locale.getDefault().language): Int = if (language == "es") 20 else 18

/** Splits a flat list into shelves of [size]; the last one may be shorter. */
fun <T> shelves(items: List<T>, size: Int = SHELF_SIZE): List<List<T>> = items.chunked(size)

const val SHELF_SIZE = 8
