package com.morsecode.app.core.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.morsecode.app.core.util.D
import com.morsecode.app.util.ThemeColors

/**
 * Dialogs, toasts, one-time tips and the consent popups.
 *
 * The two consent popups are reproduced verbatim from the product spec - title, body,
 * subtitle line and the exact button pair (neutral Reject / green filled Accept).
 */
object Ui {

    fun toast(view: View, text: CharSequence) = toast(view.context, text)

    fun toast(ctx: Context, text: CharSequence) {
        val t = Toast.makeText(ctx, text, Toast.LENGTH_SHORT)
        val v = t.view
        if (v != null) {
            val container = W.card(ctx, 2, 14f, 14)
            container.addView(W.label(ctx, text, 13f, ThemeColors.text(ctx)))
            t.view = container
        }
        t.show()
    }

    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positive: String,
        onPositive: () -> Unit,
        negative: String? = null,
        onNegative: (() -> Unit)? = null,
        destructive: Boolean = false
    ) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(title)
        if (message.isNotBlank()) builder.setMessage(message)
        builder.setPositiveButton(positive, object : DialogInterface.OnClickListener {
            override fun onClick(dialog: DialogInterface?, which: Int) = onPositive()
        })
        if (negative != null) {
            builder.setNegativeButton(negative, object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface?, which: Int) {
                    onNegative?.invoke()
                }
            })
        }
        builder.setCancelable(true)
        val dialog = builder.create()
        dialog.setOnShowListener(object : DialogInterface.OnShowListener {
            override fun onShow(d: DialogInterface?) {
                val accent = ThemeColors.accentStart(activity)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    if (destructive) ThemeColors.Red else accent
                )
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ThemeColors.text2(activity))
            }
        })
        dialog.show()
    }

    fun prompt(
        activity: Activity,
        title: String,
        hint: String,
        initial: String,
        positive: String,
        onOk: (String) -> Unit
    ) {
        val input = EditText(activity)
        input.setText(initial)
        input.hint = hint
        input.setTextColor(ThemeColors.text(activity))
        input.setHintTextColor(ThemeColors.muted(activity))
        input.setPadding(D.dp(activity, 16f), D.dp(activity, 12f), D.dp(activity, 16f), D.dp(activity, 12f))
        val wrap = FrameLayout(activity)
        wrap.setPadding(D.dp(activity, 12f), D.dp(activity, 8f), D.dp(activity, 12f), 0)
        wrap.addView(input)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(wrap)
            .setPositiveButton(positive, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener(object : DialogInterface.OnShowListener {
            override fun onShow(d: DialogInterface?) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    object : View.OnClickListener {
                        override fun onClick(v: View?) {
                            val value = input.text.toString().trim()
                            if (value.isNotEmpty()) {
                                dialog.dismiss()
                                onOk(value)
                            }
                        }
                    }
                )
            }
        })
        dialog.show()
    }

    fun pick(
        activity: Activity,
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selectedIndex, object : DialogInterface.OnClickListener {
                override fun onClick(dialog: DialogInterface?, which: Int) {
                    dialog?.dismiss()
                    onPick(which)
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun info(activity: Activity, title: String, message: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** Bottom sheet built from a LinearLayout - no Material Components needed. */
    class Sheet(val dialog: android.app.Dialog, val root: LinearLayout) {
        fun add(view: View) = root.addView(view)
        fun show() = dialog.show()
        fun dismiss() = dialog.dismiss()
    }

    fun bottomSheet(activity: Activity, title: String, subtitle: String? = null): Sheet {
        val dialog = android.app.Dialog(activity)
        val outer = LinearLayout(activity)
        outer.orientation = LinearLayout.VERTICAL
        val sheet = LinearLayout(activity)
        sheet.orientation = LinearLayout.VERTICAL
        val d = android.graphics.drawable.GradientDrawable()
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE)
        d.setCornerRadii(
            floatArrayOf(
                D.dp(activity, 22f).toFloat(), D.dp(activity, 22f).toFloat(),
                D.dp(activity, 22f).toFloat(), D.dp(activity, 22f).toFloat(),
                0f, 0f, 0f, 0f
            )
        )
        d.setColor(ThemeColors.card(activity))
        sheet.background = d
        sheet.pad(14, 14, 14, 18)

        val handle = View(activity)
        handle.background = ThemeColors.neutralDrawable(activity, 999f, true)
        handle.layoutParams = LinearLayout.LayoutParams(D.dp(activity, 42f), D.dp(activity, 4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        sheet.addView(handle)
        sheet.addView(W.gap(activity, 14))
        sheet.addView(W.label(activity, title, 17f, ThemeColors.text(activity), bold = true))
        if (subtitle != null) sheet.addView(W.label(activity, subtitle, 12f, ThemeColors.text2(activity), mono = true))
        sheet.addView(W.gap(activity, 10))

        val scroll = ScrollView(activity)
        scroll.isFillViewport = false
        scroll.addView(sheet, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        outer.setPadding(0, D.dp(activity, 40f), 0, 0)
        outer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        return Sheet(dialog, sheet)
    }

    /** Small confirmation-style popup anchored in the middle of the screen (tips, hints). */
    fun tip(activity: Activity, text: String, onDismiss: () -> Unit = {}) {
        val dialog = AlertDialog.Builder(activity)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, object : DialogInterface.OnClickListener {
                override fun onClick(d: DialogInterface?, which: Int) = onDismiss()
            })
            .create()
        dialog.show()
    }

    /** Summary card used after a batch or a Broadcast finishes. */
    fun summaryDialog(
        activity: Activity,
        title: String,
        subtitle: String,
        stats: List<Pair<String, String>>,
        lines: List<String> = emptyList(),
        onClose: () -> Unit = {}
    ) {
        val root = W.column(activity, 6, 6)
        val card = W.outlinedCard(activity, true, 18f, 16)
        card.addView(W.label(activity, title, 18f, ThemeColors.text(activity), bold = true))
        card.addView(W.label(activity, subtitle, 12.5f, ThemeColors.accentStart(activity), mono = true))
        if (lines.isNotEmpty()) {
            card.addView(W.gap(activity, 10))
            for (l in lines) {
                card.addView(W.label(activity, l, 13f, ThemeColors.text2(activity)))
                card.addView(W.gap(activity, 4))
            }
        }
        if (stats.isNotEmpty()) {
            card.addView(W.gap(activity, 10))
            val grid = W.row(activity)
            for ((value, caption) in stats) {
                grid.addView(W.statCell(activity, value, 0).apply {
                    removeAllViews()
                    addView(W.label(activity, value, 18f, ThemeColors.accentStart(activity), bold = true, gravity = Gravity.CENTER))
                    addView(W.label(activity, caption, 9f, ThemeColors.muted(activity), bold = true, mono = true, gravity = Gravity.CENTER))
                })
            }
            card.addView(grid)
        }
        root.addView(card)
        AlertDialog.Builder(activity)
            .setView(root)
            .setPositiveButton(android.R.string.ok, object : DialogInterface.OnClickListener {
                override fun onClick(d: DialogInterface?, which: Int) = onClose()
            })
            .show()
    }

    /**
     * "Connection request" - exactly the copy and button pair from the spec.
     * `subtitle` is the "<Device type> · <Transport> · <IP>" line.
     */
    fun consent(
        activity: Activity,
        title: String,
        body: String,
        subtitle: String,
        acceptLabel: String,
        rejectLabel: String,
        onAnswer: (Boolean) -> Unit
    ) {
        val content = W.column(activity, 8, 8)
        val card = W.card(activity, 1, 18f, 16)
        card.addView(W.label(activity, title, 19f, ThemeColors.text(activity), bold = true))
        card.addView(W.gap(activity, 8))
        card.addView(W.label(activity, body, 14.5f, ThemeColors.text(activity)))
        card.addView(W.gap(activity, 4))
        card.addView(W.label(activity, subtitle, 12f, ThemeColors.text2(activity), mono = true))
        content.addView(card)

        // The buttons are part of the same content view. They used to be added in `onShow` with a
        // second setContentView, which *replaces* the view the dialog was built with - so the
        // popup came up as two lone buttons and none of the copy the spec pins down verbatim
        // ("Connection request", "Browser wants access", the device/transport/IP line).
        content.addView(W.gap(activity, 8))
        val row = W.row(activity)
        row.pad(8, 0, 8, 8)
        val reject = W.outlineButton(activity, rejectLabel)
        val accept = W.successButton(activity, acceptLabel)
        val lpReject = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpReject.setMargins(D.dp(activity, 6f), 0, D.dp(activity, 6f), 0)
        val lpAccept = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpAccept.setMargins(D.dp(activity, 6f), 0, D.dp(activity, 6f), 0)
        row.addView(reject, lpReject)
        row.addView(accept, lpAccept)
        content.addView(row)

        val dialog = AlertDialog.Builder(activity).setView(content).setCancelable(false).create()
        dialog.setCanceledOnTouchOutside(false)
        reject.onClick {
            dialog.dismiss()
            onAnswer(false)
        }
        accept.onClick {
            dialog.dismiss()
            onAnswer(true)
        }
        dialog.show()
    }

    /** One-time-per-topic hint, dismissible, stored in Prefs. */
    fun onceTip(activity: Activity, key: String, text: String) {
        val prefs = com.morsecode.app.di.Di.prefs(activity)
        if (prefs.tipShown(key)) return
        prefs.markTip(key)
        tip(activity, text)
    }
}
