package org.fossify.contacts.helpers

import android.util.Base64
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Uploads the backup file to a GitHub repository through the REST "contents" API.
 * Needs a personal access token with write access to the repository contents.
 */
object GithubBackup {
    private const val API_URL = "https://api.github.com"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    class GithubException(message: String) : Exception(message)

    data class Target(
        val repo: String,
        val branch: String,
        val folder: String,
        val token: String,
    ) {
        val filePath: String
            get() = listOf(folder.trim('/'), GITHUB_BACKUP_FILE_NAME).filter { it.isNotEmpty() }.joinToString("/")
    }

    private class Response(val code: Int, val body: String)

    /** Checks that the repository and branch exist and that the token is allowed to push. */
    fun verify(target: Target) {
        val repoResponse = request("GET", "$API_URL/repos/${target.repo}", target.token)
        if (repoResponse.code != 200) {
            throw GithubException(errorMessage(repoResponse))
        }

        val permissions = JSONObject(repoResponse.body).optJSONObject("permissions")
        if (permissions != null && !permissions.optBoolean("push", false)) {
            throw GithubException("The token has no write access to ${target.repo}")
        }

        val branchResponse = request("GET", "$API_URL/repos/${target.repo}/branches/${encode(target.branch)}", target.token)
        if (branchResponse.code != 200) {
            throw GithubException(errorMessage(branchResponse))
        }
    }

    /**
     * Creates or updates [Target.filePath] on [Target.branch] with [content].
     * @return false when the repository already holds identical content, so no commit was made.
     */
    fun upload(target: Target, content: ByteArray, commitMessage: String): Boolean {
        val url = "$API_URL/repos/${target.repo}/contents/${encodePath(target.filePath)}"
        val existing = request("GET", "$url?ref=${encode(target.branch)}", target.token)
        val existingSha = when (existing.code) {
            200 -> JSONObject(existing.body).optString("sha").takeIf { it.isNotEmpty() }
            404 -> null
            else -> throw GithubException(errorMessage(existing))
        }

        if (existingSha != null && existingSha == gitBlobSha(content)) {
            return false
        }

        val body = JSONObject().apply {
            put("message", commitMessage)
            put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            put("branch", target.branch)
            if (existingSha != null) {
                put("sha", existingSha)
            }
        }

        val response = request("PUT", url, target.token, body.toString())
        if (response.code != 200 && response.code != 201) {
            throw GithubException(errorMessage(response))
        }
        return true
    }

    /** The SHA that git itself assigns to a file with this content, as returned by the contents API. */
    private fun gitBlobSha(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${content.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.update(content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun request(method: String, url: String, token: String, body: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Fossify-Contacts-Backup")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(response: Response): String {
        val apiMessage = try {
            JSONObject(response.body).optString("message")
        } catch (e: Exception) {
            ""
        }

        val hint = when (response.code) {
            401 -> "Token rejected"
            403 -> "Access denied or rate limited"
            404 -> "Repository, branch or path not found (or the token cannot see it)"
            409, 422 -> "Request rejected"
            else -> "Request failed"
        }
        return if (apiMessage.isNotEmpty()) "$hint (${response.code}): $apiMessage" else "$hint (${response.code})"
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String) = path.split("/").joinToString("/") { encode(it) }
}

/** Writes everything to [primary] and keeps a copy in [copy]. Closing only closes [primary]. */
class TeeOutputStream(private val primary: OutputStream, private val copy: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        primary.write(b)
        copy.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        primary.write(b, off, len)
        copy.write(b, off, len)
    }

    override fun flush() = primary.flush()

    override fun close() = primary.close()
}
