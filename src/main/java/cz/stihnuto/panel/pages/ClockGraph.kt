package cz.stihnuto.panel.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import cz.stihnuto.panel.App
import cz.stihnuto.panel.Ui
import cz.stihnuto.panel.weather.Hour
import cz.stihnuto.panel.weather.WeatherIcon
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Vzhled hlavního okna „graf“: nahoře datum s nejbližší událostí, uprostřed velký čas,
 * pod ním proužek kvality vzduchu a dole graf dneška a zítřka — teplota (křivka), srážky
 * (sloupce), ikony počasí s větrem (šipka, kam fouká, a km/h; barva podle nárazů).
 *
 * Graf se kreslí do bitmapy jen při změně dat, hodiny nebo velikosti; každou sekundu
 * se pak jen zkopíruje (hodiny jsou slabé a hřejí se).
 */
class ClockGraph(private val u: Float) {
    private val hours = Ui.paint(Ui.PRIMARY, 1f, Ui.BLACK)
    private val minutes = Ui.paint(Ui.TEXT, 1f, Ui.BLACK)
    private val header = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
    private val label = Ui.paint(Ui.TEXT, 1f, Ui.MEDIUM, Paint.Align.CENTER)
    private val small = Ui.paint(Ui.DIM, 1f, Ui.REGULAR, Paint.Align.CENTER)
    private val rainTxt = Ui.paint(Ui.BLUE, 1f, Ui.REGULAR, Paint.Align.CENTER)
    private val windTxt = Ui.paint(Ui.DIM, 1f, Ui.MEDIUM, Paint.Align.CENTER)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private var cache: Bitmap? = null
    private var cacheKey = ""
    /** Kde leží graf (pro klepnutí → okno Po hodinách). */
    val graphRect = RectF()

    private var fitW = -1f; private var fitH = -1f; private var fitSize = 0f

    /** Vrací (střed x, y, šířka) pro proužek kvality vzduchu. */
    fun draw(c: Canvas, w: Float, h: Float, bottomInset: Float, place: (Float, Float, Float, Float) -> Unit) {
        val app = App.instance
        val now = LocalDateTime.now()

        // --- nahoře datum a nejbližší událost v jednom řádku
        val next = app.calendar.upcoming().firstOrNull()
        val today = LocalDate.now()
        var top = Ui.dateLong(today).replaceFirstChar { it.uppercase() }
        if (next != null) {
            val day = when (next.start.toLocalDate()) {
                today -> ""; today.plusDays(1) -> "zítra "; else -> "${Ui.dayShort(next.start.toLocalDate())} "
            }
            val time = if (next.allDay) "celý den" else if (next.start.isBefore(now)) "probíhá" else Ui.time(next.start)
            top += " · $day$time ${next.title}"
        }
        header.textSize = h * 0.045f
        while (header.measureText(top) > w * 0.92f && top.length > 6) top = top.dropLast(2).trimEnd() + "…"
        c.drawText(top, w / 2, h * 0.085f, header)

        // --- velký čas uprostřed; velikost podle šířky i výšky
        val gTop = h * 0.48f
        if (w != fitW || h != fitH) {
            var s = h * 0.37f
            fun widthAt(): Float { hours.textSize = s; minutes.textSize = s; return hours.measureText("00") + minutes.measureText(":00") }
            while ((widthAt() > w * 0.9f || s * 0.711f > h * 0.27f) && s > 10) s *= 0.98f
            fitSize = s; fitW = w; fitH = h
        }
        hours.textSize = fitSize; minutes.textSize = fitSize
        val cap = fitSize * 0.711f
        val base = h * 0.115f + (gTop - h * 0.06f - h * 0.115f + cap) / 2
        val hh = "${now.hour}"
        val mm = ":%02d".format(now.minute)
        val tw = hours.measureText(hh) + minutes.measureText(mm)
        c.drawText(hh, w / 2 - tw / 2, base, hours)
        c.drawText(mm, w / 2 - tw / 2 + hours.measureText(hh), base, minutes)

        // --- proužek vzduchu mezi časem a grafem
        val barW = w * 0.36f
        val by = (base + gTop) / 2 - h * 0.012f
        place(w / 2 - barW / 2, by, w / 2 + barW / 2, by + h * 0.022f)

        // --- graf (z mezipaměti)
        val gx = u * 3f // okraj karty grafu
        graphRect.set(gx, gTop, w - gx, h - bottomInset)
        val wx = app.weather.data
        if (wx == null) {
            small.textSize = h * 0.045f
            c.drawText(app.weather.error ?: "Načítám počasí…", w / 2, (graphRect.top + graphRect.bottom) / 2, small)
            return
        }
        val key = "${wx.fetchedAt}|${now.hour}|${now.toLocalDate()}|${graphRect.width().toInt()}x${graphRect.height().toInt()}"
        var bmp = cache
        if (bmp == null || key != cacheKey) {
            if (bmp == null || bmp.width != graphRect.width().toInt() || bmp.height != graphRect.height().toInt()) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(graphRect.width().toInt().coerceAtLeast(1), graphRect.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            }
            bmp.eraseColor(0)
            val thisHour = now.withMinute(0).withSecond(0).withNano(0)
            val cutoff = now.toLocalDate().plusDays(2).atStartOfDay()
            val list = wx.hours.filter { !it.time.isBefore(thisHour) && it.time.isBefore(cutoff) }
            if (list.size >= 2) drawGraph(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), h, list)
            cache = bmp; cacheKey = key
        }
        c.drawBitmap(bmp, graphRect.left, graphRect.top, null)
    }

    /**
     * Rozměry písma a ikon jsou dané podílem výšky obrazovky [S] (hodiny mají jen 800 × 480 bodů
     * na 4 palcích — menší písmo by nešlo přečíst), rozložení podílem výšky karty.
     */
    private fun drawGraph(c: Canvas, w: Float, h: Float, S: Float, hs: List<Hour>) {
        val n = hs.size
        rect.set(0f, 0f, w, h)
        fill.shader = null; fill.color = Ui.CARD
        c.drawRoundRect(rect, S * 0.03f, S * 0.03f, fill)
        val pad = S * 0.06f
        val gx0 = pad; val gx1 = w - pad
        val sx = (gx1 - gx0) / (n - 1)
        fun x(i: Int) = gx0 + i * sx
        val iconY = h * 0.14f; val windY = h * 0.31f
        val tTop = h * 0.47f; val tBot = h * 0.67f
        val rTop = h * 0.72f; val rBot = h * 0.85f
        val hourY = h * 0.965f
        val tMin = hs.minOf { it.temp }; val tMax = hs.maxOf { it.temp }
        val span = (tMax - tMin).coerceAtLeast(4.0); val mid = (tMax + tMin) / 2
        fun ty(t: Double) = (tBot - ((t - (mid - span / 2)) / span).toFloat() * (tBot - tTop))
        // popisky aspoň ~0,17 výšky obrazovky od sebe (jinak se slijí)
        val step = listOf(2, 3, 4, 6, 8).firstOrNull { it * sx >= S * 0.17f } ?: 8

        // půlnoc
        line.pathEffect = DashPathEffect(floatArrayOf(S * 0.008f, S * 0.008f), 0f)
        hs.forEachIndexed { i, hr ->
            if (hr.time.hour == 0 && i > 0) {
                line.color = Ui.FAINT; line.strokeWidth = S * 0.003f
                c.drawLine(x(i), tTop - S * 0.04f, x(i), rBot, line)
            }
        }
        line.pathEffect = null

        // srážky: světlé = pravděpodobnost, sytě modré = mm
        val maxMm = hs.maxOf { it.precip }.coerceAtLeast(2.0)
        val bw = sx * 0.66f
        hs.forEachIndexed { i, hr ->
            if (hr.precipProb > 0) {
                fill.color = 0x404FA3FF
                val ph = (rBot - rTop) * hr.precipProb / 100f
                rect.set(x(i) - bw / 2, rBot - ph, x(i) + bw / 2, rBot); c.drawRoundRect(rect, bw / 4, bw / 4, fill)
            }
            if (hr.precip > 0.05) {
                fill.color = Ui.BLUE
                val mh = ((hr.precip / maxMm).toFloat() * (rBot - rTop)).coerceAtLeast(S * 0.006f)
                rect.set(x(i) - bw / 2, rBot - mh, x(i) + bw / 2, rBot); c.drawRoundRect(rect, bw / 4, bw / 4, fill)
            }
        }

        // teplota: hladká křivka s výplní
        path.reset()
        hs.forEachIndexed { i, hr ->
            val px = x(i); val py = ty(hr.temp)
            if (i == 0) path.moveTo(px, py) else {
                val ppx = x(i - 1); val ppy = ty(hs[i - 1].temp)
                path.cubicTo((ppx + px) / 2, ppy, (ppx + px) / 2, py, px, py)
            }
        }
        val area = Path(path).apply { lineTo(x(n - 1), tBot + h * 0.04f); lineTo(x(0), tBot + h * 0.04f); close() }
        fill.shader = LinearGradient(0f, tTop, 0f, tBot + h * 0.04f, 0x55FFC94A, 0x00FFC94A, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        fill.shader = null
        line.color = Ui.ACCENT; line.strokeWidth = S * 0.008f
        c.drawPath(path, line)

        // „nyní“
        line.color = 0x59A8C7FA; line.strokeWidth = S * 0.004f
        c.drawLine(x(0), tTop - S * 0.04f, x(0), rBot, line)

        // popisky
        val ws = S * 0.042f
        for (i in hs.indices step step) {
            val hr = hs[i]
            WeatherIcon.draw(c, hr.code, hr.isDay, x(i), iconY, S * 0.15f)
            // vítr: šipka (kam fouká) a km/h pod ikonou, barva podle nárazů
            val col = windColor(hr.gusts)
            val wt = "${Math.round(hr.wind)}"
            windTxt.textSize = ws; windTxt.color = col
            val tw = windTxt.measureText(wt)
            arrow(c, x(i) - tw / 2 - ws * 0.35f, windY - ws * 0.33f, hr.windDir, ws * 0.8f, col)
            c.drawText(wt, x(i) + ws * 0.3f, windY, windTxt)
            // teplota s tmavým obrysem, ať se neztrácí ve žluté výplni
            fill.color = Ui.ACCENT
            c.drawCircle(x(i), ty(hr.temp), S * 0.009f, fill)
            haloText(c, Ui.deg(hr.temp), x(i), ty(hr.temp) - S * 0.02f, S * 0.052f, Ui.TEXT, S)
            if (hr.precipProb >= 20) haloText(c, "${hr.precipProb}%", x(i), rTop - S * 0.006f, S * 0.033f, Ui.BLUE, S)
            small.textSize = S * 0.042f
            small.color = if (i == 0) Ui.TEXT else Ui.DIM
            small.typeface = if (i == 0) Ui.MEDIUM else Ui.REGULAR
            c.drawText(if (i == 0) "nyní" else "${hr.time.hour}", x(i), hourY, small)
        }
        small.color = Ui.DIM; small.typeface = Ui.REGULAR
    }

    private val halo = Ui.paint(Ui.CARD, 1f, Ui.MEDIUM, Paint.Align.CENTER).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }

    private fun haloText(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, S: Float) {
        halo.textSize = size; halo.strokeWidth = S * 0.014f
        c.drawText(t, x, y, halo)
        label.textSize = size; label.color = color
        c.drawText(t, x, y, label)
    }

    /** Šipka ukazuje, KAM vítr fouká (předpověď udává, odkud). */
    private fun arrow(c: Canvas, cx: Float, cy: Float, fromDeg: Double, s: Float, col: Int) {
        val a = Math.toRadians(fromDeg + 180 - 90)
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        val x2 = cx + dx * s * 0.5f; val y2 = cy + dy * s * 0.5f
        line.color = col; line.strokeWidth = s * 0.16f
        c.drawLine(cx - dx * s * 0.5f, cy - dy * s * 0.5f, x2 - dx * s * 0.2f, y2 - dy * s * 0.2f, line)
        val hx = -dy; val hy = dx
        path.reset()
        path.moveTo(x2, y2)
        path.lineTo(x2 - dx * s * 0.42f + hx * s * 0.28f, y2 - dy * s * 0.42f + hy * s * 0.28f)
        path.lineTo(x2 - dx * s * 0.42f - hx * s * 0.28f, y2 - dy * s * 0.42f - hy * s * 0.28f)
        path.close()
        fill.color = col
        c.drawPath(path, fill)
    }

    /** Barva větru podle nárazů: klid šedě, od 30 km/h oranžově, od 45 km/h červeně. */
    private fun windColor(gusts: Double) = when {
        gusts >= 45 -> 0xFFFF6B6B.toInt()
        gusts >= 30 -> 0xFFFFB04D.toInt()
        else -> 0xFFBFC6CD.toInt() // světlejší než běžný šedý text, ať jde přečíst
    }
}
