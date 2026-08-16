package com.p2p.supermaster.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.HttpsURLConnection

class NetworkTimeFetcher {
    /**
     * Fetches real network time by querying a reliable server via Tor Proxy (SOCKS5).
     * Returns timestamp in milliseconds, or null if failed.
     */
    suspend fun fetchTimeViaTor(
        socksHost: String = "127.0.0.1",
        socksPort: Int = 9050,
    ): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
                val url = URL("https://google.com")
                val connection = url.openConnection(proxy) as HttpsURLConnection

                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val dateStr = connection.getHeaderField("Date")
                if (dateStr != null) {
                    val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("GMT")
                    val date = sdf.parse(dateStr)
                    date?.time
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
