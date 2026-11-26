package edu.dlsu.esca_3_2526_c3

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Supervisor screen building blocks (fleet-app style): a status banner, coloured number tiles, driver cards
 * with an initials circle and a status pill, section headers, a facts grid, behaviour tiles and alert rows.
 * Colours follow the risk colours of Ui; everything works in night mode.
 */
object Sui {
    private fun dp(c: Context, n: Int) = Ui.dp(c, n)

    /** The colour mixed into the card colour (alpha 0..1): soft tinted backgrounds. */
    fun tint(color: Int, alpha: Float): Int {
        val base = Ui.surface
        fun mix(a: Int, b: Int) = (a * alpha + b * (1 - alpha)).toInt()
        return Color.rgb(mix(Color.red(color), Color.red(base)), mix(Color.green(color), Color.green(base)), mix(Color.blue(color), Color.blue(base)))
    }

    private fun shape(color: Int, c: Context, radius: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radius).toFloat()
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    /** Text colour that reads on this background (dark on yellow / light colours, white otherwise). */
    private fun onColor(color: Int): Int {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return if (lum > 170) Color.rgb(30, 30, 30) else Color.WHITE
    }

    private val textMain get() = if (Ui.nightMode) Color.WHITE else Ui.navy
    private val textMuted get() = if (Ui.nightMode) Color.rgb(203, 213, 225) else Ui.muted

    fun label(c: Context, value: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    /** Big coloured banner: small top line, large title, subtitle (and an optional live "updated" label). */
    fun hero(c: Context, top: String, title: String, color: Int, subtitle: String, extra: TextView? = null) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(color, c, 20)
        elevation = dp(c, 3).toFloat()
        setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 18))
        val fg = onColor(color)
        addView(label(c, top, 12f, fg, true).apply { alpha = 0.85f; letterSpacing = 0.08f })
        addView(label(c, title, 28f, fg, true).apply { setPadding(0, dp(c, 4), 0, dp(c, 2)) })
        addView(label(c, subtitle, 14f, fg).apply { alpha = 0.92f })
        if (extra != null) { extra.setTextColor(fg); extra.alpha = 0.8f; extra.textSize = 12f; addView(extra) }
    }

    /** One number tile: tinted background, big coloured number, small label. */
    fun statTile(c: Context, value: String, name: String, color: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = shape(tint(color, if (Ui.nightMode) 0.28f else 0.13f), c, 16)
        setPadding(dp(c, 6), dp(c, 12), dp(c, 6), dp(c, 12))
        addView(label(c, value, 26f, if (Ui.nightMode) Color.WHITE else color, true).apply { gravity = Gravity.CENTER })
        addView(label(c, name, 12f, textMuted, true).apply { gravity = Gravity.CENTER })
        contentDescription = "$value $name"
    }

    fun row(c: Context, views: List<View>) = LinearLayout(c).apply {
        for (v in views) addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(c, 3), 0, dp(c, 3), 0) })
    }

    /** "● NEEDS ATTENTION · 2" */
    fun sectionHeader(c: Context, title: String, count: Int, color: Int) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 2), dp(c, 14), 0, dp(c, 6))
        addView(View(c).apply { background = shape(color, c, 5) }, LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)).apply { rightMargin = dp(c, 8) })
        addView(label(c, "$title · $count", 12f, textMuted, true).apply { letterSpacing = 0.06f })
    }

    /** Filled pill, e.g. HIGH RISK in red. */
    fun pill(c: Context, value: String, color: Int) = label(c, value, 11f, onColor(color), true).apply {
        background = shape(color, c, 12)
        setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3))
    }

    private fun initials(name: String): String {
        val parts = name.substringBefore("@").split(' ', '.', '_', '-').filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0][0]}${parts[1][0]}"
            parts.isNotEmpty() -> parts[0].take(2)
            else -> "?"
        }.uppercase()
    }

    /** Initials in a coloured circle. */
    fun avatar(c: Context, name: String, color: Int) = label(c, initials(name), 15f, onColor(color), true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    /**
     * A driver in the list: coloured edge, initials, name, status pill, what they are doing, "updated" label.
     * Drivers that need attention get a tinted card.
     */
    fun driverCard(c: Context, name: String, state: String, color: Int, detail: String, age: TextView, alarm: Boolean): LinearLayout {
        val attention = state == "HIGH" || state == "MEDIUM"
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(if (attention) tint(color, if (Ui.nightMode) 0.25f else 0.10f) else Ui.surface, c, 16,
                stroke = if (attention) tint(color, 0.45f) else tint(Ui.muted, 0.18f))
            elevation = dp(c, 1).toFloat()
            setPadding(0, 0, dp(c, 12), 0)
            minimumHeight = dp(c, 76)
            addView(View(c).apply { background = shape(color, c, 3) }, LinearLayout.LayoutParams(dp(c, 6), -1).apply { setMargins(0, dp(c, 10), 0, dp(c, 10)) })
            addView(avatar(c, name, color), LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)).apply { setMargins(dp(c, 10), 0, dp(c, 12), 0) })
            val middle = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(c, 10), 0, dp(c, 10))
                val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(label(c, name, 16f, textMain, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                if (alarm) top.addView(label(c, "🔔", 14f, textMain).apply { setPadding(dp(c, 4), 0, dp(c, 4), 0) })
                top.addView(pill(c, when (state) { "HIGH", "MEDIUM", "LOW" -> state; else -> state.lowercase().replaceFirstChar { it.uppercase() } }, color))
                addView(top)
                addView(label(c, detail, 13f, textMuted).apply { setPadding(0, dp(c, 3), 0, 0) })
                age.textSize = 11f
                age.setTextColor(textMuted)
                age.setPadding(0, dp(c, 2), 0, 0)
                addView(age)
            }
            addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(c, "›", 24f, textMuted).apply { setPadding(dp(c, 8), 0, 0, 0) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 10) }
        }
    }

    /** 2 x N grid of small fact tiles (label above, value below). */
    fun factGrid(c: Context, facts: List<Pair<String, String>>) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        for (pair in facts.chunked(2)) {
            val r = LinearLayout(c)
            for ((name, value) in pair) {
                r.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(Ui.surface, c, 16, stroke = tint(Ui.muted, 0.18f))
                    elevation = dp(c, 1).toFloat()
                    setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
                    addView(label(c, name.uppercase(), 11f, textMuted, true).apply { letterSpacing = 0.05f })
                    addView(label(c, value, 18f, textMain, true).apply { setPadding(0, dp(c, 3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4)) })
            }
            if (pair.size == 1) r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(r)
        }
    }

    /** Each behaviour as a small tinted tile with its level (Normal / Warning / High). */
    fun behaviourGrid(c: Context, statuses: List<Pair<String, String>>) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        for (pair in statuses.chunked(2)) {
            val r = LinearLayout(c)
            for ((name, level) in pair) {
                val color = Ui.riskColor(level)
                val calm = Ui.riskLevel(level) !in setOf("LOW", "MEDIUM", "HIGH")
                r.addView(LinearLayout(c).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(if (calm) tint(Ui.green, if (Ui.nightMode) 0.2f else 0.08f) else tint(color, if (Ui.nightMode) 0.3f else 0.14f), c, 14)
                    setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
                    addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (calm) Ui.green else color) } },
                        LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)).apply { rightMargin = dp(c, 8) })
                    val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(label(c, name, 13f, textMain, true))
                    col.addView(label(c, if (calm) "Normal" else Ui.riskLevel(level).lowercase().replaceFirstChar { it.uppercase() }, 12f, textMuted))
                    addView(col)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4)) })
            }
            if (pair.size == 1) r.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(r)
        }
    }

    /** "TODAY", "YESTERDAY", "NOV 25" */
    fun dayHeader(c: Context, text: String) = label(c, text.uppercase(), 12f, textMuted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(c, 2), dp(c, 14), 0, dp(c, 4))
    }

    /** An alert / trip row: coloured edge, title, subtitle, time and a level pill on the right. */
    fun alertItem(c: Context, title: String, subtitle: String, time: String, level: String, color: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(Ui.surface, c, 14, stroke = tint(Ui.muted, 0.15f))
        setPadding(0, 0, dp(c, 12), 0)
        minimumHeight = dp(c, 58)
        addView(View(c).apply { background = shape(color, c, 3) }, LinearLayout.LayoutParams(dp(c, 5), -1).apply { setMargins(0, dp(c, 8), dp(c, 12), dp(c, 8)) })
        val mid = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(c, 8), 0, dp(c, 8))
            addView(label(c, title, 14f, textMain, true))
            if (subtitle.isNotEmpty()) addView(label(c, subtitle, 12f, textMuted))
        }
        addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            if (time.isNotEmpty()) addView(label(c, time, 11f, textMuted).apply { gravity = Gravity.END; setPadding(0, 0, 0, dp(c, 3)) })
            addView(pill(c, level, color))
        }
        addView(right)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) }
    }
}
