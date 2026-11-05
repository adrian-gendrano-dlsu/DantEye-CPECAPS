package edu.dlsu.danteye

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Presentation-only widgets. All information on screen is static example data. */
object Ui {
    val navy = Color.rgb(245, 232, 235)
    val background = Color.rgb(22, 12, 16)
    val white = Color.rgb(39, 21, 28)
    val muted = Color.rgb(184, 150, 159)
    val blue = Color.rgb(228, 91, 112)
    val green = Color.rgb(112, 216, 166)
    val amber = Color.rgb(255, 185, 99)
    val red = Color.rgb(255, 99, 120)

    private val headerColor = Color.rgb(58, 23, 34)
    private val selectedColor = Color.rgb(86, 35, 49)
    private val placeholderColor = Color.rgb(49, 26, 35)

    fun dp(c: Context, n: Int): Int =
        (n * c.resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(
        color: Int,
        c: Context,
        radius: Int = 16
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
        }

    private fun text(c: Context, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(c).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    fun header(c: Context, title: String) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(c, 20), dp(c, 22),
                dp(c, 20), dp(c, 22)
            )
            setBackgroundColor(headerColor)
            addView(space(c, 8))
            addView(text(c, title, 23f, navy, true))
        }

    fun blockParams(c: Context) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(c, 14)
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(white, c)
        elevation = dp(c, 2).toFloat()
        setPadding(dp(c, 17), dp(c, 16), dp(c, 17), dp(c, 17))
    }

    fun title(c: Context, value: String) = text(c, value, 18f, navy, true).apply {
        setPadding(0, 0, 0, dp(c, 12))
    }

    fun eyebrow(c: Context, value: String) = text(c, value, 11f, muted, true).apply {
        setPadding(0, 0, 0, dp(c, 8))
        letterSpacing = 0.09f
    }

    fun note(c: Context, value: String) = text(c, value, 12f, muted).apply {
        setPadding(0, dp(c, 6), 0, dp(c, 10))
    }

    fun chip(c: Context, value: String, color: Int) =
        text(c, value, 12f, color, true).apply {
            setPadding(
                dp(c, 10), dp(c, 7),
                dp(c, 10), dp(c, 7)
            )
            background = shape(
                Color.argb(
                    35,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ),
                c, 8
            )
        }

    fun bigChip(c: Context, value: String, color: Int) =
        text(c, value, 18f, color, true).apply {
            setPadding(
                dp(c, 10), dp(c, 7),
                dp(c, 10), dp(c, 7)
            )
            background = shape(
                Color.argb(
                    35,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ),
                c, 8
            )
        }

    fun space(c: Context, h: Int) =
        View(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                1, dp(c, h)
            )
        }

    fun placeholder(
        c: Context,
        heading: String,
        detail: String,
        height: Int
    ) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = shape(placeholderColor, c, 12)
        addView(text(c, heading, 15f, navy, true))
        addView(text(c, detail, 12f, muted))
        layoutParams = LinearLayout.LayoutParams(
            -1, dp(c, height)
        )
    }

    fun pair(c: Context, label: String, value: String) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 8), 0, dp(c, 8))
        addView(text(c, label, 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(c, value, 14f, navy, true))
    }

    fun alert(
        c: Context,
        label: String,
        time: String,
        level: String,
        color: Int
    ) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 10), 0, dp(c, 10))
        val names = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, label, 14f, navy, true))
            addView(text(c, time, 12f, muted))
        }
        addView(
            names,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        addView(chip(c, level, color))
    }

    fun session(
        c: Context,
        date: String,
        duration: String,
        alerts: String
    ) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 14), 0, dp(c, 14))
        val left = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, date, 15f, navy, true))
            addView(text(c, duration, 12f, muted))
        }
        addView(
            left,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        addView(chip(c, alerts, blue))
    }

    fun tab(
        c: Context,
        label: String,
        onTap: () -> Unit
    ) = TextView(c).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(muted)
        setOnClickListener { onTap() }
        isClickable = true
    }

    fun activeTab(view: TextView, active: Boolean) {
        view.setTextColor(if (active) blue else muted)
        view.setTypeface(
            null,
            if (active) Typeface.BOLD else Typeface.NORMAL
        )
        view.background = shape(
            if (active) selectedColor else white,
            view.context, 12
        )
    }

    fun monitorRow(
        c: Context, name: String, risk: String, event: String, color: Int, selected: Boolean
    ) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            dp(c, 12), dp(c, 12),
            dp(c, 12), dp(c, 12)
        )
        background = shape(
            if (selected) selectedColor else Ui.background,
            c, 10
        )
        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(c, name, 15f, navy, true))
            addView(text(c, event, 12f, muted))
        }
        addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip(c, risk.uppercase(), color))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) }
    }


    fun button(c: Context, label: String, onTap: () -> Unit) =
        android.widget.Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.rgb(255, 240, 243))
            background = shape(Color.rgb(130, 40, 60), c, 12)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(130, 40, 60)
                )
            setPadding(
                dp(c, 16), dp(c, 12),
                dp(c, 16), dp(c, 12)
            )
            minimumHeight = dp(c, 48)
            setOnClickListener { onTap() }
        }

    fun field(c: Context, hintText: String, initial: String) =
        android.widget.EditText(c).apply {
            hint = hintText
            setSingleLine(true)
            setText(initial)
            textSize = 15f
            setSelectAllOnFocus(true)

            setTextColor(Color.rgb(245, 232, 235))
            setHintTextColor(Color.rgb(184, 150, 159))
            setHighlightColor(Color.rgb(130, 40, 60))

            background = shape(Color.rgb(49, 26, 35), c, 10)
            backgroundTintList = null

            setPadding(
                dp(c, 12), dp(c, 12),
                dp(c, 12), dp(c, 12)
            )
            minimumHeight = dp(c, 48)
        }

}