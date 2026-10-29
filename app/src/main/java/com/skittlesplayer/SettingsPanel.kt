package com.skittlesplayer

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible

/**
 * Right-hand settings panel. The main list and every option list are rendered inside the same
 * panel; Back steps from an option list to the main list, and from the main list closes it.
 */
class SettingsPanel(
    private val container: View,
    private val header: TextView,
    private val scroll: ScrollView,
    private val list: LinearLayout,
) {

    class Row(
        val label: String,
        @DrawableRes val icon: Int = 0,
        val value: String? = null,
        val checked: Boolean = false,
        val onClick: (() -> Unit)? = null,
    )

    var isOpen = false
        private set

    /** Title of the option list being shown, or null while the main list is shown. */
    var submenu: String? = null
        private set

    var onClosed: (() -> Unit)? = null

    private val inflater = LayoutInflater.from(container.context)
    private var mainRows: () -> List<Row> = { emptyList() }
    private var mainFocus = 0

    fun open(rows: () -> List<Row>) {
        mainRows = rows
        mainFocus = 0
        isOpen = true
        container.isVisible = true
        showMain()
    }

    fun showMain() {
        submenu = null
        header.isVisible = true
        render(mainRows(), isMain = true)
        focus(mainFocus)
    }

    fun showSubmenu(title: String, options: List<Row>) {
        submenu = title
        header.isVisible = false
        val back = Row(title, R.drawable.ic_back, onClick = ::showMain)
        render(listOf(back) + options, isMain = false)
        val checked = options.indexOfFirst { it.checked }
        focus(if (checked >= 0) checked + 1 else if (options.isNotEmpty()) 1 else 0)
    }

    fun back() {
        if (submenu != null) showMain() else close()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        submenu = null
        container.isVisible = false
        list.removeAllViews()
        onClosed?.invoke()
    }

    /** Updates the value text of option [index] (0 = first row below the back row). */
    fun setValue(index: Int, value: String) {
        val row = list.getChildAt(index + 1) ?: return
        row.findViewById<TextView>(R.id.row_value).apply {
            text = value
            isVisible = true
        }
    }

    private fun render(rows: List<Row>, isMain: Boolean) {
        list.removeAllViews()
        rows.forEachIndexed { index, row ->
            val view = inflater.inflate(R.layout.item_setting_row, list, false)
            view.id = View.generateViewId()
            view.findViewById<ImageView>(R.id.row_icon).apply {
                if (row.icon != 0) {
                    setImageResource(row.icon)
                } else {
                    // Option rows keep the icon slot so their labels line up with the title.
                    visibility = if (isMain) View.GONE else View.INVISIBLE
                }
            }
            view.findViewById<TextView>(R.id.row_label).text = row.label
            view.findViewById<TextView>(R.id.row_value).apply {
                text = row.value
                isVisible = !row.value.isNullOrEmpty()
            }
            view.findViewById<View>(R.id.row_check).isVisible = row.checked
            view.setOnClickListener {
                if (isMain) mainFocus = index
                row.onClick?.invoke()
            }
            list.addView(view)
        }
        // Keep D-pad focus inside the panel.
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            child.nextFocusLeftId = child.id
            child.nextFocusRightId = child.id
        }
        list.getChildAt(0)?.let { it.nextFocusUpId = it.id }
        list.getChildAt(list.childCount - 1)?.let { it.nextFocusDownId = it.id }
        scroll.scrollTo(0, 0)
    }

    private fun focus(index: Int) {
        val target = list.getChildAt(index.coerceIn(0, maxOf(0, list.childCount - 1))) ?: return
        target.requestFocus()
    }
}
