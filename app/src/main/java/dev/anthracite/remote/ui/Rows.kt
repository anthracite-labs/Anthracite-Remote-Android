package dev.anthracite.remote.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import dev.anthracite.remote.R

/** One list row with an optional long mono body (diff, log, description). */
internal data class Row(
    val title: String,
    val subtitle: String = "",
    val body: String = "",
    val tag: Any? = null,
)

internal class RowAdapter(private val activity: Activity) : BaseAdapter() {
    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(activity).inflate(R.layout.item_list_row, parent, false)
        val row = rows[position]
        view.findViewById<TextView>(R.id.row_title).text = row.title
        val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
        subtitle.text = row.subtitle
        subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
        val body = view.findViewById<TextView>(R.id.row_body)
        body.text = row.body
        body.visibility = if (row.body.isBlank()) View.GONE else View.VISIBLE
        return view
    }
}

/** Adds a full-width action button to a vertical container. */
internal fun addActionButton(
    activity: Activity,
    container: LinearLayout,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val button = LayoutInflater.from(activity)
        .inflate(R.layout.item_action_button, container, false) as MaterialButton
    button.setText(label)
    button.contentDescription = description
    button.setOnClickListener { onClick() }
    container.addView(button)
}

internal fun confirm(
    activity: Activity,
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.cancel, null)
        .show()
        .also { dialog ->
            if (destructive) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    activity.getColor(R.color.error),
                )
            }
        }
}

internal fun choose(
    activity: Activity,
    title: String,
    options: List<String>,
    onChoose: (Int) -> Unit,
) {
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setItems(options.toTypedArray()) { _, which -> onChoose(which) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun promptText(
    activity: Activity,
    title: String,
    message: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
) {
    val input = android.widget.EditText(activity).apply {
        setText(initial)
        setHint(title)
        contentDescription = title
    }
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(message)
        .setView(input)
        .setPositiveButton(R.string.continue_action) { _, _ -> onConfirm(input.text.toString()) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
