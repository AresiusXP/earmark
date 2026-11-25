package xyz.aresius.audiobooks.testutil

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import xyz.aresius.audiobooks.core.network.AudiobooksApi
import xyz.aresius.audiobooks.core.network.AudiobooksJson

/**
 * A MockWebServer that answers by "METHOD /path": register a response (or a list answered in order, the last one repeating) with [on],
 * everything else is a 404 in the contract's error format. [requests] records what arrived.
 */
class FakeServer(
    client: OkHttpClient = OkHttpClient(),
) : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = HashMap<String, MutableList<(RecordedRequest) -> MockResponse>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val handlers = synchronized(routes) { routes["${request.method} ${request.url.encodedPath}"] }
                val handler = synchronized(routes) { handlers?.let { if (it.size > 1) it.removeAt(0) else it[0] } }
                return handler?.invoke(request) ?: error(404, "not_found", "no route")
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    val api: AudiobooksApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(AudiobooksJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AudiobooksApi::class.java)

    fun on(method: String, path: String, vararg responses: MockResponse) {
        synchronized(routes) { routes["$method $path"] = responses.map { r -> { _: RecordedRequest -> r } }.toMutableList() }
    }

    fun onJson(method: String, path: String, body: String, code: Int = 200) = on(method, path, json(body, code))

    fun onRequest(method: String, path: String, handler: (RecordedRequest) -> MockResponse) {
        synchronized(routes) { routes["$method $path"] = mutableListOf(handler) }
    }

    fun requestsTo(method: String, path: String) = requests.filter { it.method == method && it.url.encodedPath == path }

    fun lastBody(method: String, path: String): String = requestsTo(method, path).last().body?.utf8().orEmpty()

    override fun close() = server.close()

    companion object {
        fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").apply { headers.forEach { (k, v) -> addHeader(k, v) } }.body(body).build()

        fun error(code: Int, errorCode: String, message: String? = null, vararg headers: Pair<String, String>): MockResponse {
            val msg = message?.let { ""","message":"$it"""" }.orEmpty()
            return json("""{"error":{"code":"$errorCode"$msg}}""", code, *headers)
        }

        fun empty(code: Int = 204): MockResponse = MockResponse.Builder().code(code).build()
    }
}

val TestJson: Json = Json { ignoreUnknownKeys = true }

fun <T> FakeServer.await(timeoutMs: Long = 3000, predicate: () -> T?): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        predicate()?.let { return it }
        Thread.sleep(10)
    }
    error("condition not met within $timeoutMs ms")
}
