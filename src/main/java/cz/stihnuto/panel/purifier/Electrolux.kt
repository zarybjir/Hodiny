package cz.stihnuto.panel.purifier

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ElectroluxException(msg: String, val code: Int = 0) : IOException(msg)

/**
 * Oficiální cloudové rozhraní Electrolux Group (developer.electrolux.one) — stejné,
 * přes které jde aplikace Electrolux. Čistička nemá ovládání v domácí síti.
 *
 * Přístup: API klíč + access token (platí řádově hodiny) + refresh token. Při obnově
 * dostaneme i nový refresh token a starý přestane platit, proto se oba hned uloží
 * ([onTokens]). Postup odpovídá knihovně pyelectroluxgroup.
 */
class Electrolux(
    private val apiKey: String,
    accessToken: String,
    refreshToken: String,
    /** Uložit nové tokeny (volá se po každé obnově). */
    private val onTokens: (access: String, refresh: String) -> Unit,
    /** Adresa rozhraní (jiná jen v testech). */
    private val base: String = BASE,
) {
    @Volatile private var access = accessToken.trim()
    @Volatile private var refresh = refreshToken.trim()

    class Appliance(val id: String, val name: String, val type: String)

    /** Info o spotřebiči: model a co umí (rozsahy a povolené hodnoty vlastností). */
    class Info(val model: String, val capabilities: JSONObject)

    /** Stav: připojení a hlášené hodnoty (PM2_5, Workmode, Fanspeed…). */
    class State(val connected: Boolean, val reported: JSONObject)

    fun appliances(): List<Appliance> {
        val arr = JSONArray(call("GET", "appliances"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Appliance(it.optString("applianceId"), it.optString("applianceName"), it.optString("applianceType"))
        }
    }

    fun info(id: String): Info {
        val j = JSONObject(call("GET", "appliances/${enc(id)}/info"))
        val info = j.optJSONObject("applianceInfo") ?: JSONObject()
        return Info(info.optString("model"), j.optJSONObject("capabilities") ?: JSONObject())
    }

    fun state(id: String): State {
        val j = JSONObject(call("GET", "appliances/${enc(id)}/state"))
        val reported = j.optJSONObject("properties")?.optJSONObject("reported") ?: JSONObject()
        val conn = j.optString("connectionState", reported.optString("connectionState", "connected"))
        return State(!conn.equals("disconnected", true), reported)
    }

    /** Pošle příkaz, např. {"Workmode":"Auto"} nebo {"Fanspeed":3}. */
    fun command(id: String, cmd: JSONObject) {
        call("PUT", "appliances/${enc(id)}/command", cmd.toString())
    }

    // ------------------------------------------------------------------

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")

    @Synchronized
    private fun call(method: String, path: String, body: String? = null): String {
        if (apiKey.isBlank() || refresh.isBlank()) throw ElectroluxException("Chybí přihlášení k Electroluxu (API klíč a token)")
        if (access.isBlank() || expiresInMin(access) < 10) renew()
        var r = http(method, path, body, auth = true)
        if (r.first == 401) { // token mohl skončit dřív, než tvrdí
            renew()
            r = http(method, path, body, auth = true)
        }
        val (code, text) = r
        if (code in 200..299) return text.ifBlank { "{}" }
        val msg = try { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("detail") } } catch (e: Exception) { "" }
        throw ElectroluxException(when (code) {
            401, 403 -> "Electrolux odmítl přihlášení ($code) — vlož v nastavení nový token."
            404 -> "Čistička na účtu Electrolux nenalezena."
            406 -> "Čistička teď tenhle příkaz nepřijme (406). $msg".trim()
            429 -> "Electrolux hlásí příliš mnoho dotazů, zkusím to později."
            else -> "Electrolux vrátil chybu $code. $msg".trim()
        }, code)
    }

    private fun renew() {
        val (code, text) = http("POST", "token/refresh", JSONObject().put("refreshToken", refresh).toString(), auth = false)
        if (code !in 200..299) {
            throw ElectroluxException(
                if (code == 401 || code == 400 || code == 403) "Token k Electroluxu vypršel — vygeneruj nový na developer.electrolux.one a vlož ho do nastavení."
                else "Obnova přihlášení k Electroluxu selhala ($code).", code,
            )
        }
        val j = JSONObject(text)
        val a = j.optString("accessToken"); val rf = j.optString("refreshToken")
        if (a.isBlank()) throw ElectroluxException("Electrolux nevrátil nový token.")
        access = a
        if (rf.isNotBlank()) refresh = rf
        onTokens(access, refresh)
    }

    private fun http(method: String, path: String, body: String?, auth: Boolean): Pair<Int, String> {
        val c = URL("$base/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("x-api-key", apiKey.trim())
            if (auth) c.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { s -> s.readBytes().let { if (it.size > 2_000_000) "" else String(it, Charsets.UTF_8) } } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val BASE = "https://api.developer.electrolux.one/api/v1"

        /** Kolik minut zbývá do vypršení tokenu (JWT, pole exp); neznámé = 0. */
        fun expiresInMin(jwt: String): Long = try {
            val part = jwt.split('.')[1]
            val payload = JSONObject(String(java.util.Base64.getUrlDecoder().decode(part.trimEnd('=')), Charsets.UTF_8))
            (payload.getLong("exp") * 1000 - System.currentTimeMillis()) / 60_000
        } catch (e: Exception) {
            0
        }
    }
}
