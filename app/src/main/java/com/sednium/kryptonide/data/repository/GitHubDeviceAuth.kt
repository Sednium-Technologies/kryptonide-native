package com.sednium.kryptonide.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub OAuth Device Authorization flow (RFC 8628) — the sign-in method for
 * apps that have no backend server to keep a client secret on, which is
 * exactly Kryptonide's situation. Unlike the Authorization Code flow, this
 * needs no client secret embedded in the app and no redirect URI/deep link
 * registered — the user approves a short code in their own browser, and we
 * just poll GitHub until they do.
 *
 * Setup required once, outside this code: register a GitHub OAuth App
 * (Settings → Developer settings → OAuth Apps), copy its Client ID into
 * [CLIENT_ID] below, and tick "Enable Device Flow" in that app's settings —
 * GitHub requires that box checked explicitly or every request here 400s.
 *
 * This coexists with the manual personal-access-token path already in
 * [GithubRepository] / [GitViewModel] — that one still works for anyone who
 * prefers pasting a scoped PAT directly (e.g. GitHub Enterprise setups this
 * flow doesn't fit as cleanly), it's just no longer the primary path.
 */
class GitHubDeviceAuth(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        // Not a secret — OAuth App Client IDs are public by design (they're
        // sent as a URL parameter in the authorize step). Replace with the
        // real Client ID from KryptonIDE's own registered OAuth App.
        const val CLIENT_ID = "Ov23liIRHVlIQUeLrp58"

        // repo:    full read/write on private + public repos, needed to push
        //          project files, read/write releases, trigger Actions runs.
        // workflow: separate scope specifically required to create/update
        //          files under .github/workflows/ — `repo` alone 403s on that.
        const val SCOPES = "repo workflow"
    }

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int
    )

    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        object ExpiredOrDenied : PollResult()
        data class Error(val message: String) : PollResult()
    }

    /** Step 1: request a device code + the short code the user will enter. */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPES)
            .build()
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val resp = client.newCall(req).execute()
        val bodyStr = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("Failed to start device flow (${resp.code}): $bodyStr")
        val obj = Json.parseToJsonElement(bodyStr).jsonObject
        DeviceCodeResponse(
            deviceCode       = obj["device_code"]!!.jsonPrimitive.content,
            userCode         = obj["user_code"]!!.jsonPrimitive.content,
            verificationUri  = obj["verification_uri"]?.jsonPrimitive?.content ?: "https://github.com/login/device",
            expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.int ?: 900,
            intervalSeconds  = obj["interval"]?.jsonPrimitive?.int ?: 5
        )
    }

    /** Step 2: one poll attempt against the token endpoint. Caller loops this at [DeviceCodeResponse.intervalSeconds]. */
    suspend fun pollForToken(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .post(body)
            .build()

        val resp = runCatching { client.newCall(req).execute() }
            .getOrElse { return@withContext PollResult.Error(it.message ?: "Network error") }
        val bodyStr = resp.body?.string().orEmpty()
        val obj = runCatching { Json.parseToJsonElement(bodyStr).jsonObject }
            .getOrElse { return@withContext PollResult.Error("Malformed response") }

        obj["access_token"]?.jsonPrimitive?.contentOrNull?.let { return@withContext PollResult.Success(it) }

        when (obj["error"]?.jsonPrimitive?.contentOrNull) {
            "authorization_pending" -> PollResult.Pending
            "slow_down"              -> PollResult.SlowDown
            "expired_token", "access_denied" -> PollResult.ExpiredOrDenied
            else -> PollResult.Error(obj["error_description"]?.jsonPrimitive?.contentOrNull ?: "Unknown error")
        }
    }

    /**
     * Convenience wrapper: runs the full poll loop for the caller, respecting
     * the server-provided interval (and backing off further on slow_down).
     * Returns the access token, or null if expired/denied/cancelled.
     */
    suspend fun pollUntilComplete(
        device: DeviceCodeResponse,
        isCancelled: () -> Boolean = { false },
        onTick: (secondsRemaining: Int) -> Unit = {}
    ): String? {
        var interval = device.intervalSeconds
        var elapsed = 0
        while (elapsed < device.expiresInSeconds) {
            if (isCancelled()) return null
            delay(interval * 1000L)
            elapsed += interval
            onTick(device.expiresInSeconds - elapsed)
            when (val result = pollForToken(device.deviceCode)) {
                is PollResult.Success -> return result.accessToken
                PollResult.Pending -> { /* keep polling */ }
                PollResult.SlowDown -> interval += 5
                PollResult.ExpiredOrDenied -> return null
                is PollResult.Error -> return null
            }
        }
        return null
    }
}
