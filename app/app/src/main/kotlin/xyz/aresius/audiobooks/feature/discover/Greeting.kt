package xyz.aresius.audiobooks.feature.discover

enum class Greeting { Morning, Afternoon, Evening, Night }

/** Greeting for an hour of the day (0..23): morning from 5, afternoon from 12, evening from 18, night from 23. */
fun greetingForHour(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.Morning
    in 12..17 -> Greeting.Afternoon
    in 18..22 -> Greeting.Evening
    else -> Greeting.Night
}

/** Splits a flat list into shelves of [size]; the last one may be shorter. */
fun <T> shelves(items: List<T>, size: Int = SHELF_SIZE): List<List<T>> = items.chunked(size)

const val SHELF_SIZE = 8
