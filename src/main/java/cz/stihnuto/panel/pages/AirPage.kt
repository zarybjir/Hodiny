package cz.stihnuto.panel.pages

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.purifier.Purifier

/** Čistička vzduchu: kvalita vzduchu doma a ovládání (Electrolux Pure A9 a podobné). */
class AirPage(ctx: Context) : Page(ctx) {
    private val app = App.instance
    private val u = Ui.unit(ctx)
    private val list = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((u * 5).toInt(), (u * 4).toInt(), (u * 5).toInt(), (u * 9).toInt())
    }
    override val view: View = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    private val onChange = { render() }

    override fun onShow() {
        app.purifier.listen(onChange)
        app.purifier.refresh()
    }

    override fun onHide() {
        app.purifier.unlisten(onChange)
    }

    private fun render() {
        val repo = app.purifier
        list.removeAllViews()
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(ctx, repo.data?.name?.takeIf { it.isNotBlank() } ?: "Vzduch", u * 7, Ui.TEXT, Ui.MEDIUM),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (repo.configured) header.addView(Ui.button(ctx, "Obnovit", u * 4.2f) { repo.refresh() })
        list.addView(header)

        if (!repo.configured) {
            list.addView(message("Čistička ještě není připojená. Na počítači nebo mobilu otevři nastavení hodin " +
                "(http://${cz.stihnuto.panel.web.WebServer.localIp() ?: "<ip-hodin>"}:8080) a v části Čistička vzduchu " +
                "vlož API klíč a token z developer.electrolux.one."))
            return
        }
        repo.error?.let { list.addView(message(it)) }
        val p = repo.data
        if (p == null) {
            if (repo.error == null) list.addView(message("Načítám…"))
            return
        }
        if (!p.online) list.addView(message("Čistička je offline (vypnutá ze zásuvky, nebo bez WiFi)."))

        // --- celkové hodnocení (to samé, co ukazuje proužek na hlavním okně) a proč
        p.level?.let { lvl ->
            val sum = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            sum.addView(Ui.text(ctx, OVERALL_TEXT[lvl], u * 5f, Ui.BG, Ui.MEDIUM).apply {
                background = Ui.rounded(levelColor(lvl), u * 4)
                setPadding((u * 4).toInt(), (u * 1.4f).toInt(), (u * 4).toInt(), (u * 1.6f).toInt())
            })
            p.worstCause?.let { cause ->
                sum.addView(Ui.text(ctx, "kvůli: $cause", u * 4.4f, Ui.DIM),
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 3).toInt() })
            }
            list.addView(sum, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        }
        if (p.doorOpen == true) list.addView(Ui.text(ctx, "Dvířka filtru jsou otevřená — čistička nečistí.", u * 4.6f, Ui.HEAT, Ui.MEDIUM),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })

        // --- jednotlivá čidla, každé se svým vlastním hodnocením (jen ta, která model hlásí)
        class Tile(val label: String, val value: String, val unit: String, val level: Int?)
        val tiles = listOfNotNull(
            p.pm25?.let { Tile("Prach PM2.5", "${Math.round(it)}", "µg/m³", Purifier.levelPm25(it)) },
            p.tvoc?.let { Tile("Těkavé látky", "${Math.round(it)}", "ppb", Purifier.levelTvoc(it)) },
            p.co2?.let { Tile("CO₂", "${Math.round(it)}", "ppm", Purifier.levelCo2(it)) },
            p.pm10?.let { Tile("Prach PM10", "${Math.round(it)}", "µg/m³", Purifier.levelPm10(it)) },
            p.pm1?.let { Tile("Prach PM1", "${Math.round(it)}", "µg/m³", null) },
            p.temp?.let { Tile("Teplota", Ui.num(it), "°C", null) },
            p.humidity?.let { Tile("Vlhkost", "${Math.round(it)}", "%", null) },
        )
        tiles.chunked(3).forEach { group ->
            val r = LinearLayout(ctx)
            group.forEachIndexed { i, t ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.rounded(Ui.CARD, u * 3)
                    setPadding((u * 4).toInt(), (u * 2.5f).toInt(), (u * 4).toInt(), (u * 2.5f).toInt())
                }
                card.addView(Ui.text(ctx, t.label, u * 4f, Ui.DIM))
                val vr = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
                vr.addView(Ui.text(ctx, t.value, u * 8.5f, Ui.TEXT, Ui.LIGHT))
                vr.addView(Ui.text(ctx, " ${t.unit}", u * 3.8f, Ui.DIM), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (u * 1.2f).toInt() })
                card.addView(vr)
                if (t.level != null) {
                    // hodnocení právě téhle hodnoty: barevná čárka a slovo
                    val lr = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                    lr.addView(View(ctx).apply { background = Ui.rounded(levelColor(t.level), u) },
                        LinearLayout.LayoutParams((u * 6).toInt(), (u * 1.2f).toInt()))
                    lr.addView(Ui.text(ctx, LEVEL_TEXT[t.level], u * 3.6f, Ui.DIM),
                        LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (u * 1.8f).toInt() })
                    card.addView(lr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 1.2f).toInt() })
                }
                r.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = (u * 3).toInt() })
            }
            repeat(3 - group.size) { r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = (u * 3).toInt() }) }
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        }

        // --- ovládání
        val b = (u * 14).toInt()
        val ctl = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (p.on) Ui.SKY_BG else Ui.CARD, u * 4, if (p.on) (u * 0.6f).toInt() else 0, Ui.SKY)
            setPadding((u * 4).toInt(), (u * 3).toInt(), (u * 4).toInt(), (u * 3).toInt())
            alpha = if (p.online) 1f else 0.5f
        }
        ctl.addView(Ui.button(ctx, if (p.on) "Vypnout" else "Zapnout", u * 4.8f,
            if (p.on) Ui.CARD_HI else Ui.SKY, if (p.on) Ui.TEXT else Ui.ON_SKY) { app.purifier.setPower(!p.on) }
            .apply { background = Ui.rounded(if (p.on) Ui.CARD_HI else Ui.SKY, b / 2f) },
            LinearLayout.LayoutParams((u * 26).toInt(), b))
        if (p.on) {
            ctl.addView(Ui.button(ctx, "Auto", u * 4.5f, if (p.auto) Ui.SKY else Ui.CARD_HI, if (p.auto) Ui.ON_SKY else Ui.TEXT) {
                app.purifier.setAuto(true)
            }.apply { background = Ui.rounded(if (p.auto) Ui.SKY else Ui.CARD_HI, b / 2f) },
                LinearLayout.LayoutParams((u * 18).toInt(), b).apply { leftMargin = (u * 3).toInt() })
            ctl.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            fun round(label: String, onTap: () -> Unit) = Ui.button(ctx, label, u * 7, Ui.CARD_HI, Ui.TEXT, onTap).apply {
                background = Ui.rounded(Ui.CARD_HI, b / 2f); setPadding(0, 0, 0, 0)
            }
            ctl.addView(round("−") { app.purifier.nudgeFan(-1) }, LinearLayout.LayoutParams(b, b))
            val speed = if (p.auto) "auto" else (p.fan?.toString() ?: "–")
            val sc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            sc.addView(Ui.text(ctx, speed, u * (if (p.auto) 6.5f else 10f), if (p.auto) Ui.DIM else Ui.TEXT, Ui.REGULAR))
            sc.addView(Ui.text(ctx, "rychlost 1–${p.fanMax}", u * 3.2f, Ui.DIM))
            ctl.addView(sc, LinearLayout.LayoutParams((u * 24).toInt(), -2))
            ctl.addView(round("+") { app.purifier.nudgeFan(1) }, LinearLayout.LayoutParams(b, b))
        } else {
            ctl.addView(Ui.text(ctx, "Čistička stojí.", u * 4.5f, Ui.DIM),
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (u * 4).toInt() })
        }
        list.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 4).toInt() })

        // --- přepínače, které model má
        if (p.features.isNotEmpty()) {
            val fr = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            p.features.entries.forEachIndexed { i, (k, on) ->
                fr.addView(Ui.button(ctx, (FEATURE_TEXT[k] ?: k) + if (on) " ✓" else "", u * 4.2f,
                    if (on) Ui.SKY else Ui.CARD_HI, if (on) Ui.ON_SKY else Ui.TEXT) { app.purifier.setFeature(k, !on) }
                    .apply { background = Ui.rounded(if (on) Ui.SKY else Ui.CARD_HI, u * 4.5f) },
                    LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = (u * 2.5f).toInt() })
            }
            list.addView(fr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 3).toInt() })
        }

        // --- filtr (hodnotu počítá čistička sama z doby a zátěže provozu)
        p.filterLife?.let { f ->
            list.addView(Ui.text(ctx, "Filtr" + (p.filterName?.let { " $it" } ?: "") + " $f %" + if (f <= 10) " · připrav výměnu" else "",
                u * 4.2f, if (f <= 10) Ui.HEAT else Ui.DIM),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 4).toInt() })
        }
        list.addView(Ui.text(ctx, "Změna z hodin se v cloudu Electroluxu projeví do půl minuty." +
            (p.signal?.let { " · WiFi signál: ${it.lowercase()}" } ?: ""), u * 3.4f, Ui.FAINT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 2).toInt() })
        // přehled všeho, co čistička posílá (sem se dá podívat, co ještě umí)
        list.addView(Ui.button(ctx, if (showRaw) "Skrýt údaje z čističky" else "Všechny údaje z čističky", u * 3.8f) {
            showRaw = !showRaw; render()
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (u * 3).toInt() })
        if (showRaw) list.addView(Ui.text(ctx, (if (p.model.isNotBlank()) "model ${p.model}\n" else "") +
            p.raw.entries.joinToString("\n") { "${it.key}: ${it.value}" }, u * 3.4f, Ui.DIM),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = (u * 1.5f).toInt() })
    }

    private var showRaw = false

    private fun message(t: String) = Ui.text(ctx, t, u * 4.5f, Ui.DIM).apply { setPadding(0, (u * 3).toInt(), 0, 0) }

    companion object {
        /** Hodnocení jedné hodnoty. */
        val LEVEL_TEXT = listOf("dobré", "zhoršené", "špatné", "velmi špatné")
        /** Celkové hodnocení vzduchu. */
        val OVERALL_TEXT = listOf("Dobrý vzduch", "Zhoršený vzduch", "Špatný vzduch", "Velmi špatný vzduch")
        /** Barva kvality vzduchu (i proužek na hlavním okně): zářivě modrá = dobrý. */
        fun levelColor(level: Int) = when (level) {
            0 -> 0xFF1FA2FF.toInt()
            1 -> 0xFFFFD43B.toInt()
            2 -> 0xFFFF9838.toInt()
            else -> 0xFFFF4D4D.toInt()
        }
        val FEATURE_TEXT = mapOf("Ionizer" to "Ionizátor", "UILight" to "Světlo na čističce", "SafetyLock" to "Dětská pojistka")
    }
}
