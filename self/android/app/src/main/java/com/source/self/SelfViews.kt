package com.source.self

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_TEXT_PREVIEW_CHARS = 64 * 1024
private val backgroundColor = Color.rgb(248, 244, 237)
private val surfaceColor = Color.rgb(255, 251, 247)
private val elevatedColor = Color.rgb(241, 237, 247)
private val inputColor = Color.rgb(235, 243, 239)
private val selectedColor = Color.rgb(228, 240, 233)
private val borderColor = Color.rgb(218, 210, 201)
private val primaryColor = Color.rgb(54, 49, 44)
private val accentColor = Color.rgb(61, 124, 91)
private val disconnectedColor = Color.rgb(169, 91, 91)
private val secondaryColor = Color.rgb(112, 103, 94)
private val rippleColor = Color.argb(36, 61, 124, 91)
private val desktopPastels = intArrayOf(
    Color.rgb(255, 240, 232),
    Color.rgb(240, 236, 250),
    Color.rgb(234, 244, 244),
    Color.rgb(248, 241, 217),
)

enum class AppSection(val label: String) {
    DESKTOP("Desktop"), SELF("Self"), SOURCE("Source"),
}

class SelfViews(private val activity: Activity, private val bronze: BronzeStore) {
    private val thumbnails = ThumbnailLoader(bronze, dp(220), dp(300))
    private val scrollPositions = mutableMapOf<String, Int>()
    private var localStorageList: ListView? = null
    private var localStorageListSort: LocalStorageSort? = null

    fun close() {
        localStorageList = null
        scrollPositions.clear()
        thumbnails.close()
    }

    fun app(
        content: View,
        selected: AppSection,
        connected: Boolean,
        omniText: String,
        attachmentCount: Int,
        omniEnabled: Boolean,
        restoreOmniFocus: Boolean,
        omniCandidates: List<OmniSearchCandidate>,
        onOmniTextChanged: (String) -> Unit,
        onAttach: () -> Unit,
        onClearAttachments: () -> Unit,
        onAdd: () -> Unit,
        onOpenResult: (OmniSearchCandidate) -> Unit,
        onSection: (AppSection) -> Unit,
    ): View {
        activity.window.insetsController?.show(WindowInsets.Type.systemBars())
        activity.window.statusBarColor = backgroundColor
        activity.window.navigationBarColor = backgroundColor
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(12), dp(10), dp(12), dp(6))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(6) + bars.bottom)
                insets
            }
            addView(omniBox(
                omniText,
                attachmentCount,
                omniEnabled,
                restoreOmniFocus,
                omniCandidates,
                onOmniTextChanged,
                onAttach,
                onClearAttachments,
                onAdd,
                onOpenResult,
            ), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomNavigation(selected, connected, onSection), LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun pairing(status: String, message: String?): View = FrameLayout(activity).apply {
        setBackgroundColor(backgroundColor)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label(status, 25f, true).apply { gravity = Gravity.CENTER })
            message?.let { addView(label(it, 15f).apply { setTextColor(secondaryColor); gravity = Gravity.CENTER }) }
        }
        addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun desktop(
        refs: List<DesktopObjectRef>,
        silver: SilverSnapshot,
        onOpen: (DesktopObjectRef) -> Unit,
        onItemMenu: (DesktopObjectRef) -> Unit,
        onMove: (DesktopObjectRef, Int) -> Unit,
        onUnpin: (DesktopObjectRef) -> Unit,
    ): View = FrameLayout(activity).apply {
        if (refs.isNotEmpty()) {
            val flow = DesktopFlowLayout(activity).apply {
                setPadding(0, dp(2), 0, dp(8))
                setDragActions(onMove, onUnpin)
                refs.forEach { ref ->
                    addView(desktopTile(ref, silver, onOpen, onItemMenu, ::startItemDrag))
                }
            }
            val scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(flow, FrameLayout.LayoutParams(-1, -2))
                preserveScroll("desktop")
            }
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            addView(label("Desktop is empty", 17f).apply {
                setTextColor(secondaryColor); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
            }, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun omniBox(
        initialText: String,
        attachmentCount: Int,
        enabled: Boolean,
        restoreFocus: Boolean,
        candidates: List<OmniSearchCandidate>,
        onTextChanged: (String) -> Unit,
        onAttach: () -> Unit,
        onClearAttachments: () -> Unit,
        onAdd: () -> Unit,
        onOpenResult: (OmniSearchCandidate) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val add = label("Add", 14f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = dp(10).toFloat()
            }
            contentDescription = "Add current input"
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
            setOnClickListener { onAdd() }
        }
        val editor = EditText(activity).apply {
            setText(initialText)
            setSelection(text.length)
            hint = "Search or add something..."
            setHintTextColor(secondaryColor)
            setTextColor(primaryColor)
            textSize = 16f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            isEnabled = enabled
            setPadding(dp(4), 0, dp(6), 0)
            setOnEditorActionListener { _, actionId, event ->
                val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (enter && enabled) {
                    onAdd()
                    true
                } else false
            }
        }
        fun showResults(query: String) {
            results.removeAllViews()
            if (!enabled) return
            searchOmniBox(query, candidates).forEachIndexed { index, result ->
                results.addView(omniResult(result, onOpenResult), LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(2)
                })
            }
        }
        fun update(value: String) {
            add.visibility = if (value.isNotEmpty() || attachmentCount > 0) View.VISIBLE else View.GONE
            showResults(value)
        }
        val input = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(inputColor)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), borderColor)
            }
            addView(label("📎", 20f).apply {
                gravity = Gravity.CENTER
                contentDescription = "Attach files"
                isClickable = true
                isFocusable = enabled
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.55f
                setOnClickListener { onAttach() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(editor, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(add, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
        }
        addView(input, LinearLayout.LayoutParams(-1, -2))
        if (attachmentCount > 0) addView(label(
            "$attachmentCount ${if (attachmentCount == 1) "attachment" else "attachments"}  ×",
            13f,
            true,
        ).apply {
            setTextColor(accentColor)
            setPadding(dp(10), dp(7), dp(10), dp(5))
            contentDescription = "Clear attachments"
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
            setOnClickListener { onClearAttachments() }
        })
        addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                val next = value?.toString().orEmpty()
                onTextChanged(next)
                update(next)
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })
        update(initialText)
        if (restoreFocus && enabled) editor.post {
            if (editor.requestFocus()) {
                editor.setSelection(editor.text.length)
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun omniResult(
        result: OmniSearchCandidate,
        onOpen: (OmniSearchCandidate) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            setColor(elevatedColor)
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), borderColor)
        }
        addView(label(result.title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(result.tier.displayName, 12f, true).apply { setTextColor(secondaryColor) })
        contentDescription = "${result.title}, ${result.tier.displayName}"
        isClickable = true
        isFocusable = false
        setOnClickListener { onOpen(result) }
    }

    fun self(localCount: Int, onLocalStorage: () -> Unit): View = screen { body ->
        body.addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            addView(label("Local storage", 17f, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("$localCount ${if (localCount == 1) "item" else "items"}", 14f).apply {
                setTextColor(secondaryColor)
            })
            contentDescription = "Local storage, $localCount ${if (localCount == 1) "item" else "items"}"
            isClickable = true
            isFocusable = true
            setOnClickListener { onLocalStorage() }
        })
    }

    fun localStorage(
        items: List<BronzeItem>,
        sort: LocalStorageSort,
        onSort: (LocalStorageSort) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val previousPosition = if (localStorageListSort == sort) {
            localStorageList?.firstVisiblePosition ?: 0
        } else 0
        val previousTop = if (localStorageListSort == sort) {
            localStorageList?.getChildAt(0)?.top ?: 0
        } else 0
        val sorting = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            LocalStorageSort.entries.forEach { option ->
                addView(label(option.label, 14f, option == sort).apply {
                    gravity = Gravity.CENTER
                    minimumWidth = dp(88)
                    minimumHeight = dp(48)
                    setTextColor(if (option == sort) accentColor else secondaryColor)
                    background = GradientDrawable().apply {
                        setColor(if (option == sort) selectedColor else Color.TRANSPARENT)
                        cornerRadius = dp(10).toFloat()
                    }
                    contentDescription = "Sort by ${option.label.lowercase(Locale.ROOT)}"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onSort(option) }
                })
            }
        }
        addView(sorting, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        val list = ListView(activity).apply {
            divider = ColorDrawable(borderColor)
            dividerHeight = dp(1)
            isVerticalScrollBarEnabled = false
            adapter = LocalStorageAdapter(sortLocalStorageItems(items, sort))
            setSelectionFromTop(previousPosition, previousTop)
        }
        localStorageList = list
        localStorageListSort = sort
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun source(
        connected: Boolean,
        disconnectedAt: Long?,
        message: String?,
        silver: SilverSnapshot,
        onEntity: (String) -> Unit,
    ): View = screen { body ->
        val status = when {
            connected -> "Connected"
            disconnectedAt != null -> "Disconnected since ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(disconnectedAt))}"
            else -> "Disconnected"
        }
        body.addView(card().apply {
            addView(label(status, 18f, true).apply { setTextColor(if (connected) accentColor else primaryColor) })
            message?.let { addView(label(it, 14f).apply { setTextColor(secondaryColor) }) }
        })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (silver.sources.any { it.stale }) {
                addView(label("Some knowledge is being updated; previous results remain visible.", 14f).apply {
                    setTextColor(secondaryColor)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            addView(label("Queue (${silver.jobs.queuedCount})", 19f, true), sectionMargin())
            if (silver.jobs.queued.isEmpty()) {
                addView(label("No jobs waiting", 14f).apply { setTextColor(secondaryColor) })
            } else {
                silver.jobs.queued.forEach { addView(jobRow(it, false)) }
            }
            addView(label("Completed (${silver.jobs.completedCount})", 19f, true), sectionMargin())
            if (silver.jobs.completed.isEmpty()) {
                addView(label("No completed jobs", 14f).apply { setTextColor(secondaryColor) })
            } else {
                val visibleCompleted = silver.jobs.completed.take(5)
                visibleCompleted.forEach { addView(jobRow(it, true)) }
                if (silver.jobs.completedCount > visibleCompleted.size) addView(label(
                    "+ ${silver.jobs.completedCount - visibleCompleted.size} earlier", 13f
                ).apply { setTextColor(secondaryColor) })
            }
            if (silver.entities.isNotEmpty()) {
                addView(label("Entities", 19f, true), sectionMargin())
                silver.entities.sortedBy(silver::label).forEach { entity ->
                    addView(entityRow(entity, silver) { onEntity(entity.id) })
                }
            }
        }
        body.addView(ScrollView(activity).apply {
            addView(content)
            preserveScroll("source")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun jobRow(job: SourceJob, completed: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), borderColor)
        }
        addView(label(job.title, 15f, true))
        val kind = if (job.kind == "silver_extraction") {
            "Silver extraction · ${job.state.replace('_', ' ')}"
        } else {
            "Sync · ${if (job.direction == "to_source") "to Source" else "to Self"}"
        }
        val time = if (completed && job.completedAt != null) {
            " · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(job.completedAt))}"
        } else ""
        addView(label(kind + time, 13f).apply { setTextColor(secondaryColor) })
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    }

    fun bronzeDetail(
        item: BronzeItem,
        knowledge: SilverKnowledge,
        onKnowledge: () -> Unit,
        isPinned: Boolean,
        onPinToggle: () -> Unit,
        onDelete: () -> Unit,
        onFullScreen: () -> Unit,
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val content = FrameLayout(activity).apply {
            when {
                item.mime.startsWith("image/") -> {
                    val image = imageView(item, 2400)
                    if (image == null) {
                        addView(unavailablePreview(), FrameLayout.LayoutParams(-1, -1))
                    } else {
                        image.scaleType = ImageView.ScaleType.FIT_CENTER
                        image.contentDescription = "Open image full screen"
                        image.isClickable = true
                        image.isFocusable = true
                        image.setOnClickListener { onFullScreen() }
                        addView(image, FrameLayout.LayoutParams(-1, -1))
                    }
                }
                item.mime == "text/plain" -> {
                    val preview = runCatching { textPreview(item) }.getOrElse { "Preview unavailable" }
                    addView(ScrollView(activity).apply {
                        isFillViewport = true
                        isVerticalScrollBarEnabled = false
                        addView(label(preview).apply {
                            setLineSpacing(dp(3).toFloat(), 1f)
                            setPadding(dp(8), dp(52), dp(8), dp(16))
                        }, FrameLayout.LayoutParams(-1, -2))
                        preserveScroll("bronze:${item.id}")
                    }, FrameLayout.LayoutParams(-1, -1))
                }
                else -> addView(unavailablePreview(), FrameLayout.LayoutParams(-1, -1))
            }
            addView(syncIndicator(item), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(8), dp(8), 0, 0)
            })
            addView(overlayButton("Metadata") { showMetadata(item) },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(8), dp(8), 0)
                })
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val knowledgeLabel = when {
            knowledge.source != null -> "Knowledge · ${knowledge.itemCount}"
            knowledge.processing != null -> "Knowledge · …"
            else -> "Knowledge · 0"
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(compactActionButton(knowledgeLabel, onKnowledge), LinearLayout.LayoutParams(0, dp(44), 1.35f))
            addView(compactActionButton(if (isPinned) "Unpin" else "Pin", onPinToggle),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
            addView(compactActionButton("Delete", onDelete),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    fun fullScreenImage(item: BronzeItem): View = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        activity.window.statusBarColor = Color.BLACK
        activity.window.navigationBarColor = Color.BLACK
        activity.window.decorView.systemUiVisibility = 0
        activity.window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
        imageView(item, 4096)?.let { image ->
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.contentDescription = "Full-screen image"
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
    }

    fun silverDetail(
        item: BronzeItem,
        knowledge: SilverKnowledge,
        silver: SilverSnapshot,
        onEntity: (String) -> Unit,
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label("Knowledge", 23f, true))
        root.addView(label("Source: ${item.title}", 13f).apply {
            setTextColor(secondaryColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (knowledge.source?.stale == true) {
            val progress = knowledge.processing?.let {
                if (it.totalBatches > 0) " · ${it.completedBatches}/${it.totalBatches}" else ""
            } ?: ""
            body.addView(label("Updating$progress", 13f).apply {
                setTextColor(secondaryColor)
            })
        }
        if (knowledge.source == null) {
            val state = knowledge.processing?.let {
                val progress = if (it.totalBatches > 0) " · ${it.completedBatches}/${it.totalBatches}" else ""
                "Processing$progress"
            } ?: "No knowledge"
            body.addView(label(state, 15f).apply { setTextColor(secondaryColor) })
        }
        if (knowledge.entities.isNotEmpty()) {
            knowledge.entities.sortedBy(silver::label).forEach { entity ->
                val sourceClaimCount = knowledge.claims.count {
                    it.state == "active" && (it.subjectEntityId == entity.id || it.objectEntityId == entity.id)
                }
                body.addView(entityRow(entity, silver, sourceClaimCount) { onEntity(entity.id) })
            }
        }
        root.addView(ScrollView(activity).apply {
            addView(body)
            preserveScroll("knowledge:${item.id}")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    fun entityDetail(
        entity: SilverEntity,
        silver: SilverSnapshot,
        isPinned: Boolean,
        onPinToggle: () -> Unit,
        onBronze: (String) -> Unit,
        onEntity: (String) -> Unit,
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label(silver.label(entity), 23f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        silver.claimGroupsFor(entity.id).forEach { group ->
            body.addView(claimGroup(group) { linkedId -> onEntity(linkedId) }, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(3)
            })
        }
        val sources = silver.supportingBronze(entity.id)
        if (sources.isNotEmpty()) {
            sources.forEach { sourceId ->
                val title = silver.sources.firstOrNull { it.bronzeSourceId == sourceId }?.title
                    ?: bronze.get(sourceId)?.title
                body.addView(sourceRow(title) { onBronze(sourceId) })
            }
        }
        root.addView(ScrollView(activity).apply {
            addView(body)
            preserveScroll("entity:${entity.id}")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(compactActionButton(if (isPinned) "Unpin" else "Pin", onPinToggle),
                LinearLayout.LayoutParams(-2, dp(44)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return root
    }

    private fun claimGroup(group: SilverClaimGroup, onEntity: (String) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val confidence = group.confidence?.let(::confidenceText)
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(8).toFloat()
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val statement = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(humanize(group.predicate), 12f, true).apply { setTextColor(secondaryColor) })
                    val valueRow = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(group.value, 15f))
                        if (group.claims.size > 1) addView(label("×${group.claims.size}", 13f, true).apply {
                            setTextColor(accentColor)
                        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    }
                    addView(valueRow)
                }
                addView(statement, LinearLayout.LayoutParams(0, -2, 1f))
                confidence?.let { value ->
                    addView(label(value, 14f, true).apply {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(dp(64), -1).apply { marginStart = dp(12) })
                }
            })
            contentDescription = buildString {
                append(humanize(group.predicate)).append(", ").append(group.value)
                confidence?.let { append(", ").append(it) }
                if (group.claims.size > 1) append(", ").append(group.claims.size).append(" claims")
            }
            group.linkedEntityId?.let { entityId ->
                isClickable = true
                isFocusable = true
                foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
                setOnClickListener { onEntity(entityId) }
            }
        }

    private fun entityRow(
        entity: SilverEntity,
        silver: SilverSnapshot,
        claimCount: Int = silver.activeClaimCount(entity.id),
        action: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
            },
            null,
        )
        addView(label(silver.label(entity), 15f, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(claimCount.toString(), 13f, true).apply {
            setTextColor(secondaryColor)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        contentDescription = "${silver.label(entity)}, $claimCount ${if (claimCount == 1) "claim" else "claims"}"
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun sourceRow(title: String?, action: () -> Unit): View = label(
        title?.let { "Source: $it" } ?: "Source",
        13f,
        true,
    ).apply {
        setTextColor(accentColor)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun unavailablePreview(): TextView = label("Preview unavailable", 15f).apply {
        setTextColor(secondaryColor)
        gravity = Gravity.CENTER
    }

    private fun syncIndicator(item: BronzeItem): TextView = label(syncStatus(item), 12f, true).apply {
        setTextColor(secondaryColor)
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = overlayBackground()
    }

    private fun overlayButton(value: String, action: () -> Unit): TextView = label(value, 12f, true).apply {
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = RippleDrawable(ColorStateList.valueOf(rippleColor), overlayBackground(), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun overlayBackground() = GradientDrawable().apply {
        setColor(Color.argb(224, 255, 251, 247))
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), Color.argb(150, 218, 210, 201))
    }

    private fun showMetadata(item: BronzeItem) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
            addView(metadataRow("Filename", item.title))
            addView(metadataRow("Type", item.mime))
            addView(metadataRow("Size", fileSize(item.size)))
            addView(metadataRow("Added", date.format(Date(item.created))))
            addView(metadataRow("Modified", date.format(Date(item.modified))))
            addView(metadataRow("Sync status", syncStatus(item)))
        }
        AlertDialog.Builder(activity)
            .setTitle("Metadata")
            .setView(content)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun metadataRow(name: String, value: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(label(name, 12f, true).apply { setTextColor(secondaryColor) })
        addView(label(value, 15f).apply { setTextIsSelectable(true) })
    }

    private fun syncStatus(item: BronzeItem): String =
        if (item.ackedRevision == item.revision) "Synced" else "Sync pending"

    private fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    private fun confidenceText(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun humanize(value: String): String = value.replace('_', ' ').replace('-', ' ')

    private fun sectionMargin() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(4) }


    fun label(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(activity).apply {
        text = value; textSize = size; setTextColor(primaryColor)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun actionButton(value: String, action: () -> Unit): Button = Button(activity).apply {
        text = value
        isAllCaps = false
        setTextColor(primaryColor)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), borderColor)
            },
            null,
        )
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        setOnClickListener { action() }
    }

    private fun compactActionButton(value: String, action: () -> Unit): Button = Button(activity).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        setTextColor(primaryColor)
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), borderColor)
            },
            null,
        )
        setOnClickListener { action() }
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun ScrollView.preserveScroll(key: String) {
        setOnScrollChangeListener { _, _, scrollY, _, _ -> scrollPositions[key] = scrollY }
        val saved = scrollPositions[key] ?: 0
        post { scrollTo(0, saved) }
    }

    private fun screen(content: (LinearLayout) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        content(this)
    }

    private fun bottomNavigation(selected: AppSection, connected: Boolean, onSection: (AppSection) -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            AppSection.entries.forEach { section ->
                val tab = FrameLayout(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (section == selected) selectedColor else backgroundColor)
                        cornerRadius = dp(10).toFloat()
                    }
                    contentDescription = if (section == AppSection.SOURCE) {
                        "Source, ${if (connected) "connected" else "disconnected"}"
                    } else section.label
                    setOnClickListener { onSection(section) }
                }
                val contents = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(section.label, 14f, true).apply {
                        setTextColor(if (section == selected) accentColor else secondaryColor)
                    })
                    if (section == AppSection.SOURCE) addView(View(activity).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (connected) accentColor else disconnectedColor)
                        }
                    }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        marginStart = dp(5)
                        topMargin = dp(1)
                        gravity = Gravity.TOP
                    })
                }
                tab.addView(contents, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                addView(tab, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                })
            }
        }

    private inner class LocalStorageAdapter(private val items: List<BronzeItem>) : BaseAdapter() {
        private val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): BronzeItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                addView(label(item.title, 15f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(date.format(Date(item.modified)), 12f).apply {
                    setTextColor(secondaryColor)
                    gravity = Gravity.END
                    maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }
        }
    }

    private fun desktopTile(
        ref: DesktopObjectRef,
        silver: SilverSnapshot,
        onOpen: (DesktopObjectRef) -> Unit,
        onMenu: (DesktopObjectRef) -> Unit,
        onStartDrag: (View, DesktopObjectRef) -> Boolean,
    ): View {
        val item = if (ref.objectType == DESKTOP_OBJECT_BRONZE) bronze.get(ref.objectId) else null
        val entity = if (ref.objectType == DESKTOP_OBJECT_SILVER) {
            silver.entities.firstOrNull { it.id == ref.objectId }
        } else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(desktopTileColor(ref))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderColor)
            }
            contentDescription = item?.title ?: entity?.let(silver::label) ?: "Item"
            when {
                entity != null -> addView(label(silver.label(entity), 16f, true))
                item == null -> addView(label("Item", 16f, true))
                item.mime.startsWith("image/") -> {
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    addView(image)
                    thumbnails.bind(item, image) { showCompactFilename(this, item.title) }
                }
                item.mime == "text/plain" -> {
                    val preview = runCatching { excerptWords(textPreview(item)) }.getOrDefault("")
                    if (preview.isBlank()) showCompactFilename(this, item.title)
                    else addView(label(preview, 15f).apply { maxWidth = dp(190) })
                }
                else -> showCompactFilename(this, item.title)
            }
            if (item != null && item.ackedRevision != item.revision) {
                addView(label("•", 16f).apply { setTextColor(secondaryColor); gravity = Gravity.END })
            }
            setOnClickListener { onOpen(ref) }
            setOnLongClickListener {
                val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                if (accessibility.isTouchExplorationEnabled) {
                    onMenu(ref)
                    true
                } else {
                    onStartDrag(this, ref).also { started -> if (!started) onMenu(ref) }
                }
            }
            setOnContextClickListener { onMenu(ref); true }
        }
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), borderColor)
        }
    }

    private fun desktopTileColor(ref: DesktopObjectRef): Int =
        desktopPastels[(ref.key.hashCode() and Int.MAX_VALUE) % desktopPastels.size]

    private fun showCompactFilename(container: LinearLayout, title: String) {
        container.removeAllViews()
        container.addView(label(title, 15f, true).apply {
            maxWidth = dp(190)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun imageView(item: BronzeItem, maxDimension: Int): ImageView? {
        val file = bronze.content(item)
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val sample = maxOf(1, maxOf(info.size.width, info.size.height) / maxDimension)
                decoder.setTargetSampleSize(sample)
            }
        }.getOrNull() ?: return null
        return ImageView(activity).apply { setImageBitmap(bitmap); adjustViewBounds = true }
    }

    private fun textPreview(item: BronzeItem): String {
        bronze.content(item).bufferedReader(Charsets.UTF_8).use { reader ->
            val value = CharArray(MAX_TEXT_PREVIEW_CHARS + 1)
            var count = 0
            while (count < value.size) {
                val read = reader.read(value, count, value.size - count)
                if (read < 0) break
                count += read
            }
            val shown = String(value, 0, minOf(count, MAX_TEXT_PREVIEW_CHARS))
            return if (count > MAX_TEXT_PREVIEW_CHARS) "$shown\n…" else shown
        }
    }
}
