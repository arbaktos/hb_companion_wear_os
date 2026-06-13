package com.arbaktos.babywatch.data

import android.content.Context
import com.arbaktos.babywatch.BuildConfig
import com.arbaktos.babywatch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Mirror of the server's Status model (python_service/main.py). */
@Serializable
data class SleepStatus(
    val state: String, // "awake" | "sleeping" | "paused"
    @SerialName("session_started_at") val sessionStartedAt: Double? = null,
    @SerialName("session_elapsed_sec") val sessionElapsedSec: Double? = null,
    @SerialName("last_sleep_end") val lastSleepEnd: Double? = null,
    @SerialName("server_time") val serverTime: Double,
)

class ApiException(message: String) : IOException(message)

/**
 * The sleep-tracking calls the ViewModel depends on. Extracted as an interface
 * so the ViewModel can be unit-tested against a fake (no Context, no network).
 */
interface SleepApi {
    suspend fun status(): SleepStatus
    suspend fun start(): SleepStatus
    suspend fun pause(): SleepStatus
    suspend fun resume(): SleepStatus
    suspend fun stop(): SleepStatus
}

/**
 * Thin HTTPS client for baby-svc. TLS trusts exactly the bundled self-signed
 * cert (res/raw/baby_svc.crt — gitignored, copy from python_service/deploy/),
 * which is both stronger than CA trust and keeps the repo free of the VM
 * address. 10s timeout per the architecture; errors surface as ApiException.
 */
class BabyApi(context: Context) : SleepApi {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = run {
        val cert = context.resources.openRawResource(R.raw.baby_svc).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val certs = HandshakeCertificates.Builder()
            .addTrustedCertificate(cert)
            .build()
        OkHttpClient.Builder()
            .sslSocketFactory(certs.sslSocketFactory(), certs.trustManager)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun status(): SleepStatus = call("GET", "/status")

    override suspend fun start(): SleepStatus = call("POST", "/sleep/start")
    override suspend fun pause(): SleepStatus = call("POST", "/sleep/pause")
    override suspend fun resume(): SleepStatus = call("POST", "/sleep/resume")
    override suspend fun stop(): SleepStatus = call("POST", "/sleep/stop")

    private suspend fun call(method: String, path: String): SleepStatus =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(BuildConfig.BASE_URL + path)
                .header("Authorization", "Bearer " + BuildConfig.BEARER_TOKEN)
                // Report this device's IANA zone (e.g. "Europe/London") so the
                // server tracks where the baby is and shares it with other
                // clients (Garmin can't name its zone). Cheap local lookup;
                // sent every call so travel/DST self-correct, server-side.
                .header("X-TZ", ZoneId.systemDefault().id)
                .method(method, if (method == "POST") ByteArray(0).toRequestBody() else null)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw ApiException("HTTP ${response.code} on $path")
                }
                json.decodeFromString<SleepStatus>(body)
            }
        }
}
