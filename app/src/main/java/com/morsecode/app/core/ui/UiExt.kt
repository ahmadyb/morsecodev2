package com.morsecode.app.core.ui

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.morsecode.app.core.util.D
import com.morsecode.app.util.ThemeColors

/**
 * Listener helpers.
 *
 * This build compiles with `-Xsam-conversions=class`, which means Kotlin will not silently turn
 * a lambda into a Java functional interface (that shorthand needs `invokedynamic`, and the dx
 * dexer in the offline toolchain only accepts API 26+ bytecode). These inline helpers give the
 * terseness back without ever emitting an invokedynamic call site.
 */

inline fun View.onClick(crossinline body: () -> Unit) {
    setOnClickListener(object : View.OnClickListener {
        override fun onClick(v: View?) {
            body()
        }
    })
}

inline fun View.onLongClick(crossinline body: () -> Boolean) {
    setOnLongClickListener(object : View.OnLongClickListener {
        override fun onLongClick(v: View?): Boolean = body()
    })
}

inline fun CompoundButton.onToggle(crossinline body: (Boolean) -> Unit) {
    setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
            body(isChecked)
        }
    })
}

inline fun SeekBar.onSeek(crossinline body: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) body(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
}

inline fun AdapterView<*>.onItemClick(crossinline body: (Int) -> Unit) {
    setOnItemClickListener(object : AdapterView.OnItemClickListener {
        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            body(position)
        }
    })
}

inline fun AdapterView<*>.onItemLongClick(crossinline body: (Int) -> Boolean) {
    setOnItemLongClickListener(object : AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long): Boolean =
            body(position)
    })
}

inline fun EditText.onTextChanged(crossinline body: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            body(s?.toString() ?: "")
        }

        override fun afterTextChanged(s: Editable?) {}
    })
}

inline fun <T> comparatorOf(crossinline body: (T, T) -> Int): Comparator<T> =
    object : Comparator<T> {
        override fun compare(a: T, b: T): Int = body(a, b)
    }

fun runnable(body: () -> Unit): Runnable = object : Runnable {
    override fun run() {
        body()
    }
}

/** Main-thread trampoline without a Handler reference held by the caller. */
fun post(view: View, body: () -> Unit) {
    view.post(runnable(body))
}

fun postDelayed(view: View, delayMs: Long, body: () -> Unit) {
    view.postDelayed(runnable(body), delayMs)
}

// --------------------------------------------------------------------------
// tiny view factory helpers (keeps the UI code readable without XML for every row)
// --------------------------------------------------------------------------

fun Context.linear(vertical: Boolean = true): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    return l
}

fun Context.text(value: CharSequence, sizeSp: Float = 14f, color: Int = Color.WHITE): TextView {
    val t = TextView(this)
    t.text = value
    t.setTextSize(sizeSp)
    t.setTextColor(color)
    return t
}

fun ViewGroup.lp(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    margins: Int = 0
): ViewGroup.MarginLayoutParams {
    val p = ViewGroup.MarginLayoutParams(width, height)
    if (margins != 0) {
        val m = D.dp(context, margins.toFloat())
        p.setMargins(m, m, m, m)
    }
    return p
}

fun ViewGroup.addFull(view: View, marginsDp: Int = 0, heightDp: Int = -2) {
    val p = ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (heightDp == -2) ViewGroup.LayoutParams.WRAP_CONTENT else D.dp(context, heightDp.toFloat())
    )
    val m = D.dp(context, marginsDp.toFloat())
    p.setMargins(m, m, m, m)
    addView(view, p)
}

fun View.pad(hDp: Int, vDp: Int) {
    setPadding(D.dp(context, hDp.toFloat()), D.dp(context, vDp.toFloat()),
        D.dp(context, hDp.toFloat()), D.dp(context, vDp.toFloat()))
}

fun View.pad(l: Int, t: Int, r: Int, b: Int) {
    setPadding(D.dp(context, l.toFloat()), D.dp(context, t.toFloat()),
        D.dp(context, r.toFloat()), D.dp(context, b.toFloat()))
}

fun View.backgroundRound(color: Int, radiusDp: Float = 14f) {
    val d = GradientDrawable()
    d.setShape(GradientDrawable.RECTANGLE)
    d.setCornerRadius(D.dp(context, radiusDp).toFloat())
    d.setColor(color)
    background = d
}

fun View.backgroundAccent(ctx: Context, radiusDp: Float = 999f) {
    val d = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(ThemeColors.accentStart(ctx), ThemeColors.accentEnd(ctx)))
    d.setShape(GradientDrawable.RECTANGLE)
    d.setCornerRadius(D.dp(ctx, radiusDp).toFloat())
    background = d
}

inline fun Activity.dialog(
    title: String,
    message: String,
    positive: String,
    crossinline onPositive: () -> Unit,
    negative: String? = null,
    crossinline onNegative: () -> Unit = {}
) {
    val builder = android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
    builder.setPositiveButton(positive, object : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface?, which: Int) {
            onPositive()
        }
    })
    if (negative != null) {
        builder.setNegativeButton(negative, object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface?, which: Int) {
                onNegative()
            }
        })
    }
    builder.show()
}
