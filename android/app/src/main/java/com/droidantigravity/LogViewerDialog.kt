package com.droidantigravity

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import com.droidantigravity.core.diagnostics.DiagnosticEvent
import com.droidantigravity.core.diagnostics.DiagnosticLogger
import com.droidantigravity.core.diagnostics.LogLevel

/**
 * Diagnostic log viewer dialog supporting search, filtering by level/component,
 * event copying, clear logs, and stream tag differentiation.
 */
class LogViewerDialog(context: Context) : Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    private var activeFilter = "ALL"
    private var searchQuery = ""
    private lateinit var adapter: LogEventAdapter
    private lateinit var listView: ListView
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 24, 24, 24)
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val title = TextView(context).apply {
            text = "Diagnostic Logs"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        countText = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(16, 0, 16, 0)
        }

        val clearButton = Button(context).apply {
            text = "Clear"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                DiagnosticLogger.clear()
                refreshLogs()
            }
        }

        val closeButton = Button(context).apply {
            text = "Close"
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
        }

        header.addView(title)
        header.addView(countText)
        header.addView(clearButton)
        header.addView(closeButton)
        root.addView(header)

        // Search box
        val searchBox = EditText(context).apply {
            hint = "Search logs, events, or operation ID..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 16, 24, 16)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    refreshLogs()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(searchBox)

        // Filter chips bar (All, Errors, Warnings, Antigravity, Runtime, Native, WebView, Network)
        val filterScroll = HorizontalScrollView(context).apply {
            setPadding(0, 16, 0, 16)
        }
        val filterLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val filters = listOf(
            "ALL" to "All",
            "ERRORS" to "Errors",
            "WARNINGS" to "Warnings",
            "ANTIGRAVITY" to "Antigravity",
            "RUNTIME" to "Runtime",
            "NATIVE" to "Native",
            "WEBVIEW" to "WebView",
            "NETWORK" to "Network"
        )

        for ((key, label) in filters) {
            val btn = Button(context).apply {
                text = label
                textSize = 12f
                setTextColor(if (key == activeFilter) Color.BLACK else Color.WHITE)
                setBackgroundColor(if (key == activeFilter) Color.CYAN else Color.parseColor("#2A2A2A"))
                setOnClickListener {
                    activeFilter = key
                    // Update chip highlights
                    for (i in 0 until filterLayout.childCount) {
                        val child = filterLayout.getChildAt(i) as? Button
                        val childKey = filters[i].first
                        child?.setTextColor(if (childKey == activeFilter) Color.BLACK else Color.WHITE)
                        child?.setBackgroundColor(if (childKey == activeFilter) Color.CYAN else Color.parseColor("#2A2A2A"))
                    }
                    refreshLogs()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 12, 0) }
            filterLayout.addView(btn, lp)
        }
        filterScroll.addView(filterLayout)
        root.addView(filterScroll)

        // Log events list view
        listView = ListView(context).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        adapter = LogEventAdapter(context)
        listView.adapter = adapter
        root.addView(listView)

        setContentView(root)
        refreshLogs()
    }

    private fun refreshLogs() {
        val allEvents = DiagnosticLogger.getRecentEvents(500).reversed() // Newest first

        val filtered = allEvents.filter { event ->
            val matchesFilter = when (activeFilter) {
                "ALL" -> true
                "ERRORS" -> event.level == LogLevel.ERROR
                "WARNINGS" -> event.level == LogLevel.WARN || event.level == LogLevel.ERROR
                "ANTIGRAVITY" -> event.component.contains("Antigravity", ignoreCase = true)
                "RUNTIME" -> event.component.contains("Runtime", ignoreCase = true) || event.component.contains("PRoot", ignoreCase = true)
                "NATIVE" -> event.component.contains("Native", ignoreCase = true) || event.stream != null
                "WEBVIEW" -> event.component.contains("Web", ignoreCase = true)
                "NETWORK" -> event.component.contains("Network", ignoreCase = true)
                else -> true
            }

            val matchesQuery = if (searchQuery.isEmpty()) true else {
                event.message.contains(searchQuery, ignoreCase = true) ||
                event.event.contains(searchQuery, ignoreCase = true) ||
                event.operationId?.contains(searchQuery, ignoreCase = true) == true ||
                event.error?.contains(searchQuery, ignoreCase = true) == true
            }

            matchesFilter && matchesQuery
        }

        countText.text = "${filtered.size} events"
        adapter.setEvents(filtered)
    }

    private class LogEventAdapter(private val context: Context) : BaseAdapter() {
        private var items: List<DiagnosticEvent> = emptyList()

        fun setEvents(events: List<DiagnosticEvent>) {
            items = events
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): DiagnosticEvent = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val event = getItem(position)
            val container = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
            }
            container.removeAllViews()

            // Header line: Time + Level + Component + OpId + Stream
            val metaLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val levelColor = when (event.level) {
                LogLevel.ERROR -> Color.parseColor("#FF5252")
                LogLevel.WARN -> Color.parseColor("#FFD740")
                LogLevel.INFO -> Color.parseColor("#40C4FF")
                LogLevel.DEBUG -> Color.parseColor("#B0BEC5")
                LogLevel.VERBOSE -> Color.parseColor("#78909C")
            }

            val metaText = TextView(context).apply {
                val time = DiagnosticEvent.formatTime(event.timestampMs)
                val opStr = event.operationId?.let { " [$it]" } ?: ""
                val streamStr = event.stream?.let { " [$it]" } ?: ""
                val pidStr = event.processId?.let { " pid=$it" } ?: ""
                text = "[$time] [${event.level}] [${event.component}]$opStr$streamStr$pidStr"
                setTextColor(levelColor)
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }
            metaLine.addView(metaText)
            container.addView(metaLine)

            // Message text
            val messageText = TextView(context).apply {
                text = "${event.event}: ${event.message}"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setPadding(0, 4, 0, 0)
            }
            container.addView(messageText)

            // Error details if present
            if (event.error != null) {
                val errorText = TextView(context).apply {
                    text = event.error
                    setTextColor(Color.parseColor("#FF8A80"))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(Color.parseColor("#261111"))
                    setPadding(8, 6, 8, 6)
                }
                container.addView(errorText)
            }

            // Click to copy
            container.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Diagnostic Event", event.toFormattedString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Event copied to clipboard", Toast.LENGTH_SHORT).show()
            }

            return container
        }
    }
}
