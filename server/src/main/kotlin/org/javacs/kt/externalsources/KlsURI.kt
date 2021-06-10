package org.javacs.kt.externalsources

import org.javacs.kt.util.partitionAroundLast
import org.javacs.kt.util.TemporaryDirectory
import java.net.URI
import java.net.URL
import java.net.JarURLConnection
import java.io.BufferedReader
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.Paths

fun URI.toKlsURI(): KlsURI? = when (scheme) {
    "kls" -> KlsURI(URI("kls:$schemeSpecificPart"))
    "file" -> KlsURI(URI("kls:$this"))
    else -> null
}

/**
 * Identifies a class or source file inside a JAR archive using a Uniform
 * Resource Identifier (URI) with a "kls" (Kotlin language server) scheme.
 * The URI should be structured as follows:
 *
 * <p><pre>
 * kls:file:///path/to/jarFile.jar!/path/to/jvmClass.class
 * </pre></p>
 *
 * Other file extensions for classes (such as .kt and .java) are supported too, in
 * which case the file will directly be used without invoking the decompiler.
 */
data class KlsURI(val fileUri: URI, val query: Map<QueryParam, Any>) {
    /** Possible KLS URI query parameters. */
    enum class QueryParam(val parameterName: String) {
        SOURCE("source");

        override fun toString(): String = parameterName

        companion object {
            val VALUE_PARSERS: Map<QueryParam, (String) -> Any> = mapOf(
                Pair(QueryParam.SOURCE) { it.toBoolean() }
            )
        }
    }

    private val queryString: String
        get() = if (query.isEmpty()) "" else query.entries.fold("?") { accum, next -> "$accum${next.key}=${next.value}" }

    val fileName: String
        get() = fileUri.toString().substringAfterLast("/")
    val fileExtension: String?
        get() = fileName
            .split(".")
            .takeIf { it.size > 1 }
            ?.lastOrNull()

    val jarPath: Path
        get() = Paths.get(fileUri.schemeSpecificPart.split("!")[0])
    val innerPath: String?
        get() = fileUri.schemeSpecificPart.split("!", limit = 2).get(1)

    val source: Boolean
        get() = query[QueryParam.SOURCE] as? Boolean ?: false
    val isCompiled: Boolean
        get() = fileExtension == "class"

    constructor(uri: URI) : this(parseKlsURIFileURI(uri), parseKlsURIQuery(uri))

    fun withJarPath(newJarPath: Path): KlsURI =
        KlsURI(URI(newJarPath.toUri().toString() + (innerPath?.let { "!$it" } ?: "")), query)

    fun withFileExtension(newExtension: String): KlsURI {
        val (parentUri, fileName) = fileUri.toString().partitionAroundLast("/")
        val newUri = URI("$parentUri${fileName.split(".").first()}.$newExtension")
        return KlsURI(newUri, query)
    }

    fun withSource(source: Boolean): KlsURI {
        val newQuery: MutableMap<QueryParam, Any> = mutableMapOf()
        newQuery.putAll(query)
        newQuery[QueryParam.SOURCE] = source.toString()
        return KlsURI(fileUri, newQuery)
    }

    fun toURI(): URI = URI(fileUri.toString() + queryString)

    private fun toJarURL(): URL = URL("jar:${fileUri.schemeSpecificPart}")

    private fun openJarURLConnection() = toJarURL().openConnection() as JarURLConnection

    private inline fun <T> withJarURLConnection(action: (JarURLConnection) -> T): T {
        val connection = openJarURLConnection()
        val result = action(connection)
        // https://bugs.openjdk.java.net/browse/JDK-8080094
        if (connection.useCaches) {
            connection.jarFile.close()
        }
        return result
    }

    fun readContents(): String = withJarURLConnection {
        it.jarFile
            .getInputStream(it.jarEntry)
            .bufferedReader()
            .use(BufferedReader::readText)
    }

    fun extractToTemporaryFile(dir: TemporaryDirectory): Path = withJarURLConnection {
        val name = it.jarEntry.name.substringAfterLast("/").split(".")
        val tmpFile = dir.createTempFile(name[0], ".${name[1]}")

        it.jarFile
            .getInputStream(it.jarEntry)
            .use { input -> Files.newOutputStream(tmpFile).use { input.copyTo(it) } }

        tmpFile
    }

    override fun toString(): String = toURI().toString()
}

private fun parseKlsURIFileURI(uri: URI): URI = URI(uri.toString().split("?")[0])

private fun parseKlsURIQuery(uri: URI): Map<KlsURI.QueryParam, Any> = parseQuery(uri.toString().split("?").getOrElse(1) { "" })

private fun parseQuery(query: String): Map<KlsURI.QueryParam, Any> =
    query.split("&").mapNotNull {
        val parts = it.split("=")
        if (parts.size == 2) getQueryParameter(parts[0], parts[1]) else null
    }.toMap()

private fun getQueryParameter(property: String, value: String): Pair<KlsURI.QueryParam, Any>? {
    val queryParam: KlsURI.QueryParam? = KlsURI.QueryParam.values().find { it.parameterName == property }

    if (queryParam != null) {
        val typeParser = KlsURI.QueryParam.VALUE_PARSERS[queryParam]
        val queryParamValue = typeParser?.invoke(value)
        return queryParamValue?.let { Pair(queryParam, it) }
    }

    return null
}
