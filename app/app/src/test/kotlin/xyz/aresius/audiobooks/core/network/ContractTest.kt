package xyz.aresius.audiobooks.core.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * The contract (api/openapi.yaml) is binding: every (method, path) it declares must have a method on [AudiobooksApi], and the service
 * must not invent routes. The YAML is read with a line scanner (paths sit at two spaces, methods at four) to avoid a YAML dependency.
 */
class ContractTest {
    private fun openApiFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "api/openapi.yaml").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("api/openapi.yaml not found above ${File("").absolutePath}")
    }

    private fun normalise(path: String) = path.trimStart('/').split('/').joinToString("/") { if (it.startsWith("{")) "{}" else it }

    private fun contractRoutes(): Set<String> {
        val routes = HashSet<String>()
        var inPaths = false
        var current: String? = null
        for (line in openApiFile().readLines()) {
            if (line.startsWith("paths:")) { inPaths = true; continue }
            if (inPaths && line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) break
            if (!inPaths) continue
            if (line.startsWith("  /") && line.trimEnd().endsWith(":")) current = line.trim().removeSuffix(":")
            else if (current != null && line.startsWith("    ") && !line.startsWith("     ")) {
                val key = line.trim().removeSuffix(":")
                if (key in setOf("get", "put", "post", "patch", "delete")) routes += "${key.uppercase()} ${normalise(current)}"
            }
        }
        return routes
    }

    private fun serviceRoutes(): Set<String> = AudiobooksApi::class.java.methods.mapNotNull { m ->
        m.getAnnotation(GET::class.java)?.let { "GET ${normalise(it.value)}" }
            ?: m.getAnnotation(POST::class.java)?.let { "POST ${normalise(it.value)}" }
            ?: m.getAnnotation(PUT::class.java)?.let { "PUT ${normalise(it.value)}" }
            ?: m.getAnnotation(PATCH::class.java)?.let { "PATCH ${normalise(it.value)}" }
            ?: m.getAnnotation(DELETE::class.java)?.let { "DELETE ${normalise(it.value)}" }
    }.toSet()

    @Test fun theScannerFoundTheContract() {
        val routes = contractRoutes()
        assertTrue(routes.size >= 36, "only ${routes.size} routes found")
        assertTrue("POST v1/auth/bootstrap" in routes)
        assertTrue("PUT v1/me/debrid/{}" in routes)
    }

    @Test fun everyPathOfTheContractHasAServiceMethod() {
        val missing = contractRoutes() - serviceRoutes()
        assertTrue(missing.isEmpty(), "missing in AudiobooksApi: $missing")
    }

    @Test fun theServiceInventsNoRoutes() {
        val extra = serviceRoutes() - contractRoutes()
        assertTrue(extra.isEmpty(), "not in api/openapi.yaml: $extra")
    }

    @Test fun healthzHasNoVersionPrefix() {
        assertEquals(true, "GET healthz" in contractRoutes())
    }
}
