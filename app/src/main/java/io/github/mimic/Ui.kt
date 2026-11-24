package io.github.mimic

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small MIUI / HyperOS-looking settings UI built in code (no XML needed). */
class Ui(private val act: Activity) {

    class Screen(val list: LinearLayout, val scroll: ScrollView)

    private val dark = (act.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val bg = if (dark) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
    val cardColor = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
    val textColor = if (dark) 0xFFE6E6E6.toInt() else 0xFF1A1A1A.toInt()
    val subColor = 0xFF8A8A8A.toInt()
    val accent = 0xFF3482FF.toInt()
    private val divider = if (dark) 0x1FFFFFFF else 0x14000000
    private val rippleColor = if (dark) 0x1FFFFFFF else 0x12000000

    fun dp(n: Int) = (n * act.resources.displayMetrics.density + 0.5f).toInt()

    private fun round(view: View, r: Int) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, r.toFloat())
            }
        }
        view.clipToOutline = true
    }

    private fun ripple(content: Drawable?, color: Int) =
        RippleDrawable(ColorStateList.valueOf(color), content, ColorDrawable(Color.WHITE))

    /** Press-feedback background for custom clickable views. */
    fun pressable(): Drawable = ripple(null, rippleColor)

    private fun header(parent: LinearLayout, title: String, onBack: (() -> Unit)?) {
        if (onBack != null) {
            parent.addView(TextView(act).apply {
                text = "‹"
                textSize = 36f
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(16), 0)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        } else {
            parent.addView(View(act), LinearLayout.LayoutParams(1, dp(24)))
        }
        parent.addView(TextView(act).apply {
            text = title
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dp(12), dp(8), dp(12), dp(12))
        })
    }

    private fun applyBars() {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        act.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    }

    /**
     * Scrolling settings-style screen: big title, optional back arrow, optional bottom pill button.
     * Returns the container you add sections/cards into.
     */
    fun screen(
        title: String,
        onBack: (() -> Unit)? = null,
        bottomText: String? = null,
        onBottom: (() -> Unit)? = null
    ): Screen {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }
        val scroll = ScrollView(act).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        header(content, title, onBack)

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (bottomText != null && onBottom != null) {
            val pill = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(26).toFloat()
            }
            val bar = LinearLayout(act).apply {
                setBackgroundColor(bg)
                setPadding(dp(24), dp(10), dp(24), dp(14))
            }
            bar.addView(TextView(act).apply {
                text = bottomText
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ripple(pill, 0x33FFFFFF)
                setOnClickListener { onBottom() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        act.setContentView(root)
        applyBars()
        return Screen(list, scroll)
    }

    /**
     * Non-scrolling screen (header only). Add your own children after the call, e.g. a
     * search box and a GridView with weight 1, which handles its own scrolling.
     */
    fun fixedScreen(title: String, onBack: () -> Unit): LinearLayout {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        header(root, title, onBack)
        act.setContentView(root)
        applyBars()
        return root
    }

    fun searchBox(hint: String, onText: (String) -> Unit): EditText = EditText(act).apply {
        this.hint = hint
        setHintTextColor(subColor)
        setTextColor(textColor)
        textSize = 15f
        isSingleLine = true
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = dp(22).toFloat()
        }
        setPadding(dp(18), dp(12), dp(18), dp(12))
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    fun section(parent: LinearLayout, label: String) {
        parent.addView(TextView(act).apply {
            text = label
            textSize = 13f
            setTextColor(subColor)
            setPadding(dp(12), dp(20), dp(12), dp(8))
        })
    }

    /** Rounded white (or dark grey) group card. Rows are added with [row]. */
    fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
            }
        }
        round(c, dp(16))
        parent.addView(
            c,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return c
    }

    /**
     * One row inside a card. [trailingColor] (if set) overrides the trailing text colour,
     * used for the green / red / amber status dots.
     */
    fun row(
        card: LinearLayout,
        title: String,
        summary: String? = null,
        summaryAccent: Boolean = false,
        icon: Drawable? = null,
        trailing: String? = null,
        trailingAccent: Boolean = false,
        trailingColor: Int? = null,
        onClick: () -> Unit
    ) {
        if (card.childCount > 0) {
            card.addView(
                View(act).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(if (icon != null) 76 else 16)
                }
            )
        }

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = ripple(null, rippleColor)
            setOnClickListener { onClick() }
        }

        if (icon != null) {
            val iv = ImageView(act).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            round(iv, dp(10))
            row.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
        }

        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(act).apply {
            text = title
            textSize = 16f
            setTextColor(textColor)
        })
        if (summary != null) {
            texts.addView(TextView(act).apply {
                text = summary
                textSize = 13f
                setTextColor(if (summaryAccent) accent else subColor)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (trailing != null) {
            row.addView(TextView(act).apply {
                text = trailing
                textSize = 22f
                setTextColor(trailingColor ?: if (trailingAccent) accent else subColor)
                setPadding(dp(8), 0, 0, 0)
            })
        }
        card.addView(row)
    }
}
