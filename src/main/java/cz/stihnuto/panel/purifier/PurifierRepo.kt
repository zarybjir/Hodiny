package cz.stihnuto.panel.purifier

import android.util.Log
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Repo
import org.json.JSONObject

/** Co čistička právě hlásí. Hodnota null = tenhle model ji neměří / nehlásí. */
class Purifier(
    val name: String,
    val model: String,
    val online: Boolean,
    /** PowerOff, Auto, Manual (případně Smart, Quiet…) */
    val workmode: String,
    val fan: Int?,
    val fanMin: Int,
    val fanMax: Int,
    val pm1: Double?,
    val pm25: Double?,
    val pm10: Double?,
    val tvoc: Double?,
    val co2: Double?,
    val temp: Double?,
    val humidity: Double?,
    val filterLife: Int?,
    /** Přepínače, které model umí (Ionizer, UILight, SafetyLock) → zapnuto/vypnuto. */
    val features: Map<String, Boolean>,
    val fetchedAt: Long,
) {
    val on get() = online && !workmode.equals("PowerOff", true)
    val auto get() = workmode.equals("Auto", true) || workmode.equals("Smart", true)

    /**
     * Kvalita vzduchu 0 = dobrá … 3 = špatná, podle nejhoršího z toho, co čistička měří:
     * prach (PM2.5), těkavé látky (TVOC) a CO₂, pokud ho model má. null = nic neměří.
     */
    val level: Int?
        get() = listOfNotNull(pm25?.let { levelPm25(it) }, tvoc?.let { levelTvoc(it) }, co2?.let { levelCo2(it) }).maxOrNull()

    fun copy(workmode: String = this.workmode, fan: Int? = this.fan, features: Map<String, Boolean> = this.features) = Purifier(
        name, model, online, workmode, fan, fanMin, fanMax, pm1, pm25, pm10, tvoc, co2, temp, humidity, filterLife, features, fetchedAt,
    )

    companion object {
        // Prach podle doporučení WHO (denní limit 15 µg/m³), CO₂ podle běžných doporučení pro větrání.
        fun levelPm25(v: Double) = when { v <= 10 -> 0; v <= 25 -> 1; v <= 50 -> 2; else -> 3 }
        fun levelCo2(v: Double) = when { v <= 1000 -> 0; v <= 1500 -> 1; v <= 2000 -> 2; else -> 3 }
        // TVOC v ppb podle stupnic německého úřadu pro životní prostředí (UBA), převedeno na ppb
        fun levelTvoc(v: Double) = when { v <= 220 -> 0; v <= 660 -> 1; v <= 2200 -> 2; else -> 3 }
        val FEATURES = listOf("Ionizer", "UILight", "SafetyLock")
    }
}

/**
 * Čistička vzduchu Electrolux (Pure A9 a podobné) přes cloud Electroluxu.
 * Stav se načítá každou minutu, když je vidět okno Vzduch, jinak každých 5 minut.
 * Cloud hlásí změny se zpožděním, proto se po příkazu displej změní hned (optimisticky)
 * a skutečný stav se dočte za chvíli.
 */
class PurifierRepo(app: App) : Repo(app) {
    @Volatile var data: Purifier? = null; private set
    @Volatile var error: String? = null; private set
    @Volatile var loaded = false; private set
    private var client: Electrolux? = null
    private var clientKey = ""
    private var applianceId: String? = null
    private var info: Electrolux.Info? = null
    private var lastFetch = 0L
    @Volatile private var loading = false
    /** Do kdy držet hodnoty nastavené z hodin (cloud je hlásí se zpožděním). */
    private val pendingUntil = java.util.concurrent.ConcurrentHashMap<String, Pair<Any, Long>>()

    init { app.main.postDelayed({ tick() }, 12_000) }

    val configured get() = app.settings.elxApiKey.isNotBlank() && app.settings.elxRefreshToken.isNotBlank()

    private fun tick() {
        val interval = if (hasListeners) 60_000L else 5 * 60_000L
        if (configured && System.currentTimeMillis() - lastFetch >= interval) refresh()
        app.main.postDelayed({ tick() }, 15_000L)
    }

    /** Po změně přihlášení v nastavení začít znovu. */
    fun reset() {
        synchronized(this) { client = null; applianceId = null; info = null }
        data = null; error = null; loaded = false
        refresh()
    }

    private fun client(): Electrolux = synchronized(this) {
        val s = app.settings
        val key = s.elxApiKey + "|" + s.elxRefreshToken
        client?.takeIf { clientKey == key } ?: Electrolux(s.elxApiKey, s.elxAccessToken, s.elxRefreshToken, onTokens = { a, r ->
            s.elxAccessToken = a; s.elxRefreshToken = r
            synchronized(this) { clientKey = s.elxApiKey + "|" + r }
        }).also { client = it; clientKey = key }
    }

    fun refresh() {
        if (!configured) { error = null; loaded = true; notifyChanged(); return }
        if (loading) return
        loading = true
        lastFetch = System.currentTimeMillis()
        app.io.execute {
            try {
                val c = client()
                val id = applianceId ?: findPurifier(c).also { applianceId = it }
                val inf = info ?: c.info(id).also { info = it }
                val st = c.state(id)
                data = parse(st, inf, c.appliances().firstOrNull { it.id == id }?.name ?: data?.name ?: "Čistička")
                error = null
            } catch (e: Exception) {
                Log.w("Panel", "čistička", e)
                error = when (e) {
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                        "Electrolux není dostupný (hodiny jsou bez internetu?)"
                    else -> e.message ?: e.javaClass.simpleName
                }
                if (e is ElectroluxException && (e.code == 404)) applianceId = null
            } finally {
                loading = false
                loaded = true
                notifyChanged()
            }
        }
    }

    /** První spotřebič na účtu, který vypadá jako čistička (má rychlost ventilátoru a režim). */
    private fun findPurifier(c: Electrolux): String {
        val list = c.appliances()
        if (list.isEmpty()) throw ElectroluxException("Na účtu Electrolux není žádný spotřebič.")
        val named = list.firstOrNull { a ->
            val t = (a.type + " " + a.name).uppercase()
            t.contains("PURE") || t.contains("WELL") || t.contains("AIR") || t.contains("PURIF") || t.contains("ČISTI")
        }
        if (named != null) return named.id
        for (a in list) {
            val caps = try { c.info(a.id).capabilities } catch (e: Exception) { continue }
            if (caps.has("Fanspeed") && caps.has("Workmode")) return a.id
        }
        return list.first().id
    }

    private fun parse(st: Electrolux.State, inf: Electrolux.Info, name: String): Purifier {
        val r = st.reported
        fun num(k: String) = if (r.has(k) && !r.isNull(k)) r.optDouble(k).takeIf { !it.isNaN() } else null
        fun bool(v: Any?) = when (v) {
            is Boolean -> v
            is String -> v.equals("on", true) || v.equals("true", true)
            is Number -> v.toInt() != 0
            else -> null
        }
        val fanCap = inf.capabilities.optJSONObject("Fanspeed")
        val fanMin = (fanCap?.optInt("min", 1) ?: 1).coerceAtLeast(1)
        val fanMax = (fanCap?.optInt("max", 0) ?: 0).takeIf { it >= fanMin }
            ?: if (inf.model.contains("A9", true) || r.has("CO2")) 9 else 5
        val features = Purifier.FEATURES.mapNotNull { f ->
            if (!r.has(f) && !inf.capabilities.has(f)) null else f to (bool(r.opt(f)) ?: false)
        }.toMap()
        var p = Purifier(
            name = name, model = inf.model, online = st.connected,
            workmode = r.optString("Workmode", "PowerOff"),
            fan = num("Fanspeed")?.toInt(), fanMin = fanMin, fanMax = fanMax,
            pm1 = num("PM1"), pm25 = num("PM2_5") ?: num("PM2_5_approximate"), pm10 = num("PM10"),
            // jen skutečné čidlo CO₂; „ECO2“ je hodnota dopočítaná z těkavých látek, ne měření
            tvoc = num("TVOC"), co2 = num("CO2"),
            temp = num("Temp"), humidity = num("Humidity"),
            filterLife = (num("FilterLife") ?: num("FilterLife_1"))?.toInt(),
            features = features, fetchedAt = System.currentTimeMillis(),
        )
        // příkazy z posledních chvil mají přednost (cloud je ještě nehlásí)
        val now = System.currentTimeMillis()
        pendingUntil.entries.removeIf { it.value.second < now }
        pendingUntil["Workmode"]?.let { p = p.copy(workmode = it.first as String) }
        pendingUntil["Fanspeed"]?.let { p = p.copy(fan = it.first as Int) }
        val feat = p.features.toMutableMap()
        Purifier.FEATURES.forEach { f -> pendingUntil[f]?.let { feat[f] = it.first as Boolean } }
        return p.copy(features = feat)
    }

    // --- ovládání (z displeje, z asistenta i z webového API)

    fun setPower(on: Boolean, done: ((String?) -> Unit)? = null) =
        send(JSONObject().put("Workmode", if (on) "Auto" else "PowerOff"), done) { it.copy(workmode = if (on) "Auto" else "PowerOff") }

    fun setAuto(auto: Boolean, done: ((String?) -> Unit)? = null) =
        send(JSONObject().put("Workmode", if (auto) "Auto" else "Manual"), done) { it.copy(workmode = if (auto) "Auto" else "Manual") }

    /** Rychlost ventilátoru; přepne do ručního režimu (v automatice ji čistička ignoruje). */
    fun setFan(level: Int, done: ((String?) -> Unit)? = null) {
        val d = data
        val v = level.coerceIn(d?.fanMin ?: 1, d?.fanMax ?: 9)
        val needManual = d == null || !d.workmode.equals("Manual", true)
        app.io.execute {
            val err = try {
                if (needManual) { command(JSONObject().put("Workmode", "Manual")); hold("Workmode", "Manual") }
                command(JSONObject().put("Fanspeed", v)); hold("Fanspeed", v)
                null
            } catch (e: Exception) { e.message ?: "chyba" }
            after(err, done)
        }
        data = d?.copy(workmode = "Manual", fan = v)
        notifyChanged()
    }

    fun nudgeFan(delta: Int) {
        val d = data ?: return
        setFan((d.fan ?: d.fanMin) + delta)
    }

    fun setFeature(name: String, on: Boolean, done: ((String?) -> Unit)? = null) =
        send(JSONObject().put(name, on), done) { it.copy(features = it.features + (name to on)) }

    private fun send(cmd: JSONObject, done: ((String?) -> Unit)?, optimistic: (Purifier) -> Purifier) {
        data?.let { data = optimistic(it); notifyChanged() }
        app.io.execute {
            val err = try {
                command(cmd)
                cmd.keys().forEach { k -> hold(k, cmd.get(k)) }
                null
            } catch (e: Exception) { e.message ?: "chyba" }
            after(err, done)
        }
    }

    private fun command(cmd: JSONObject) {
        val c = client()
        val id = applianceId ?: findPurifier(c).also { applianceId = it }
        c.command(id, cmd)
    }

    private fun hold(key: String, value: Any) { pendingUntil[key] = value to System.currentTimeMillis() + 30_000 }

    private fun after(err: String?, done: ((String?) -> Unit)?) {
        if (err != null) { error = "Příkaz se nepovedl: $err"; pendingUntil.clear() }
        done?.invoke(err)
        // skutečný stav dočíst, až ho cloud převezme
        app.main.postDelayed({ refresh() }, if (err != null) 500 else 4_000)
        app.main.postDelayed({ refresh() }, 25_000)
    }
}
