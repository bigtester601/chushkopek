package com.example.eqmusicplayer.soundcloud

import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class SoundCloudClient(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    data class PkceSession(
        val state: String,
        val codeVerifier: String,
        val codeChallenge: String
    )

    fun createPkceSession(): PkceSession {
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        val stateBytes = ByteArray(24)
        random.nextBytes(verifierBytes)
        random.nextBytes(stateBytes)

        val verifier = base64UrlNoPadding(verifierBytes)
        val state = base64UrlNoPadding(stateBytes)
        val challengeHash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val challenge = base64UrlNoPadding(challengeHash)

        return PkceSession(
            state = state,
            codeVerifier = verifier,
            codeChallenge = challenge
        )
    }

    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        session: PkceSession
    ): String {
        require(clientId.isNotBlank()) { "SoundCloud client ID is missing." }
        require(redirectUri.isNotBlank()) { "SoundCloud redirect URI is missing." }

        return "https://secure.soundcloud.com/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", "non-expiring")
            .addQueryParameter("code_challenge", session.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", session.state)
            .build()
            .toString()
    }

    fun exchangeCodeForToken(
        clientId: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
        clientSecret: String?
    ): Result<String> {
        return runCatching {
            require(clientId.isNotBlank()) { "SoundCloud client ID is missing." }
            require(redirectUri.isNotBlank()) { "SoundCloud redirect URI is missing." }
            require(code.isNotBlank()) { "SoundCloud returned an empty auth code." }
            require(codeVerifier.isNotBlank()) { "PKCE verifier is missing." }

            val endpoints = listOf(
                "https://secure.soundcloud.com/oauth/token",
                "https://api.soundcloud.com/oauth2/token"
            )

            var lastFailure: Throwable? = null
            for (endpoint in endpoints) {
                runCatching {
                    val formBuilder = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", clientId)
                        .add("redirect_uri", redirectUri)
                        .add("code", code)
                        .add("code_verifier", codeVerifier)

                    if (!clientSecret.isNullOrBlank()) {
                        formBuilder.add("client_secret", clientSecret)
                    }

                    val request = Request.Builder()
                        .url(endpoint)
                        .post(formBuilder.build())
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        require(response.isSuccessful) {
                            "Token exchange failed: HTTP ${response.code}"
                        }
                        val body = response.body?.string().orEmpty()
                        require(body.isNotBlank()) { "Token exchange returned empty body." }
                        val accessToken = JSONObject(body).optString("access_token")
                        require(accessToken.isNotBlank()) { "No access_token in token response." }
                        return@runCatching accessToken
                    }
                }.onSuccess {
                    return@runCatching it
                }.onFailure {
                    lastFailure = it
                }
            }

            throw lastFailure ?: IllegalStateException("Token exchange failed.")
        }
    }

    fun resolvePlayableStream(
        trackUrl: String,
        clientId: String,
        accessToken: String?
    ): Result<String> {
        return runCatching {
            require(trackUrl.isNotBlank()) { "Enter a SoundCloud track URL." }
            require(clientId.isNotBlank()) {
                "SoundCloud client ID is missing. Set SOUNDCLOUD_CLIENT_ID in gradle.properties."
            }

            val resolveUrl = "https://api-v2.soundcloud.com/resolve".toHttpUrl().newBuilder()
                .addQueryParameter("url", trackUrl)
                .addQueryParameter("client_id", clientId)
                .build()

            val resolveJson = executeGetJson(resolveUrl.toString(), accessToken)
            val mediaOwner = when {
                resolveJson.optString("kind") == "track" -> resolveJson
                resolveJson.optString("kind") == "playlist" -> {
                    val tracks = resolveJson.optJSONArray("tracks") ?: JSONArray()
                    require(tracks.length() > 0) { "Playlist has no playable tracks." }
                    tracks.optJSONObject(0) ?: error("Playlist track entry is invalid.")
                }
                else -> error("Unsupported SoundCloud URL type.")
            }

            val transcodings = mediaOwner.optJSONObject("media")?.optJSONArray("transcodings") ?: JSONArray()
            require(transcodings.length() > 0) { "No playable transcodings found for this track." }

            val selectedTranscoding = pickBestTranscoding(transcodings)
                ?: error("No supported SoundCloud transcoding (progressive/hls) was found.")
            val transcodingEndpoint = selectedTranscoding.optString("url")
            require(transcodingEndpoint.isNotBlank()) { "SoundCloud transcoding URL missing." }

            val streamUrl = transcodingEndpoint.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .build()
                .toString()

            val streamJson = executeGetJson(streamUrl, accessToken)
            val finalUrl = streamJson.optString("url")
            require(finalUrl.isNotBlank()) { "SoundCloud stream URL is empty." }
            finalUrl
        }
    }

    private fun pickBestTranscoding(transcodings: JSONArray): JSONObject? {
        var hlsFallback: JSONObject? = null
        for (index in 0 until transcodings.length()) {
            val transcoding = transcodings.optJSONObject(index) ?: continue
            val protocol = transcoding.optJSONObject("format")?.optString("protocol").orEmpty()
            if (protocol == "progressive") {
                return transcoding
            }
            if (protocol == "hls" && hlsFallback == null) {
                hlsFallback = transcoding
            }
        }
        return hlsFallback
    }

    private fun executeGetJson(url: String, accessToken: String?): JSONObject {
        val attempts = mutableListOf<Pair<String, String?>>()
        if (!accessToken.isNullOrBlank()) {
            val tokenUrl = url.toHttpUrl().newBuilder()
                .addQueryParameter("oauth_token", accessToken)
                .build()
                .toString()
            attempts += tokenUrl to "Bearer $accessToken"
            attempts += tokenUrl to "OAuth $accessToken"
        }
        attempts += url to null

        var lastFailureMessage: String? = null
        for ((attemptUrl, authHeader) in attempts) {
            val requestBuilder = Request.Builder()
                .url(attemptUrl)
                .get()
            if (!authHeader.isNullOrBlank()) {
                requestBuilder.header("Authorization", authHeader)
            }

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    require(body.isNotBlank()) { "SoundCloud returned an empty response." }
                    return JSONObject(body)
                }
                val trimmedBody = body.take(300).replace('\n', ' ')
                lastFailureMessage = "SoundCloud request failed: HTTP ${response.code}. Body: $trimmedBody"
            }
        }

        error(lastFailureMessage ?: "SoundCloud request failed.")
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
