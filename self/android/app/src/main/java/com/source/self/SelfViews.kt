package com.source.self

import android.app.Activity
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
                    addView(actionButton(silver.label(entity)) { onEntity(entity.id) })
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
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label(item.title, 25f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        when {
            item.mime == "text/plain" -> body.addView(label(textPreview(item)))
            item.mime.startsWith("image/") -> imageView(item, 1200)?.let(body::addView)
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        body.addView(label(
            "Size: ${item.size} bytes\nAdded: ${date.format(Date(item.created))}\nModified: ${date.format(Date(item.modified))}\nType: ${item.mime}",
            14f,
        ).apply { setTextColor(secondaryColor) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        root.addView(ScrollView(activity).apply {
            addView(body)
            preserveScroll("bronze:${item.id}")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val knowledgeLabel = when {
            knowledge.source?.stale == true -> "Knowledge · Updating · ${knowledge.itemCount} previous items"
            knowledge.source != null -> "Knowledge · ${knowledge.itemCount} extracted items"
            knowledge.processing != null -> "Knowledge · ${knowledge.processing.state.replaceFirstChar(Char::uppercase)}"
            else -> "Knowledge · No extracted items"
        }
        root.addView(actionButton(knowledgeLabel, onKnowledge))
        root.addView(actionButton(if (isPinned) "Unpin" else "Pin", onPinToggle))
        root.addView(actionButton("Delete", onDelete))
        return root
    }

    fun silverDetail(
        item: BronzeItem,
        knowledge: SilverKnowledge,
        onEntity: (String) -> Unit,
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label("Knowledge", 25f, true))
        root.addView(label("Derived from ${item.title}", 14f).apply { setTextColor(secondaryColor) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (knowledge.source?.stale == true) {
            val progress = knowledge.processing?.let {
                if (it.totalBatches > 0) " · ${it.completedBatches}/${it.totalBatches}" else ""
            } ?: ""
            body.addView(label("Showing previous knowledge while Source updates it$progress", 15f).apply {
                setTextColor(secondaryColor)
            })
        }
        if (knowledge.source == null) {
            val state = knowledge.processing?.let {
                val progress = if (it.totalBatches > 0) " · ${it.completedBatches}/${it.totalBatches}" else ""
                "Source is ${it.state}$progress"
            } ?: "Source has not published Silver for this item."
            body.addView(label(state, 15f).apply { setTextColor(secondaryColor) })
        }
        if (knowledge.entities.isNotEmpty()) {
            body.addView(label("Entities", 18f, true), sectionMargin())
            val snapshot = SilverSnapshot(0, emptyList(), knowledge.evidence, knowledge.observations,
                knowledge.entities, knowledge.claims, emptyList())
            knowledge.entities.forEach { entity ->
                body.addView(actionButton(snapshot.label(entity)) { onEntity(entity.id) })
            }
        }
        if (knowledge.observations.isNotEmpty()) {
            body.addView(label("Observations", 18f, true), sectionMargin())
            knowledge.observations.forEach { observation ->
                val evidence = knowledge.evidence.firstOrNull { it.id in observation.evidenceIds }
                body.addView(card().apply {
                    addView(label(observationTitle(observation.kind), 15f, true))
                    addView(label(observationText(observation), 14f))
                    evidence?.excerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
                        addView(label("Evidence: $excerpt", 13f).apply { setTextColor(secondaryColor) })
                    }
                    val confidence = observation.confidence?.let { " · ${(it * 100).toInt()}%" } ?: ""
                    val model = observation.modelId?.let { " · $it@${observation.modelRevision ?: "unknown"}" } ?: ""
                    addView(label("${observation.processorId} v${observation.processorVersion}$model$confidence", 12f).apply {
                        setTextColor(secondaryColor)
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
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
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label(silver.label(entity), 25f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        silver.claimGroupsFor(entity.id).forEach { group ->
            body.addView(claimGroup(group, silver), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
        }
        val sources = silver.supportingBronze(entity.id)
        if (sources.isNotEmpty()) {
            body.addView(label("Supporting Bronze", 18f, true), sectionMargin())
            sources.forEach { sourceId ->
                val title = silver.sources.firstOrNull { it.bronzeSourceId == sourceId }?.title ?: sourceId
                body.addView(actionButton(title) { onBronze(sourceId) })
            }
        }
        root.addView(ScrollView(activity).apply {
            addView(body)
            preserveScroll("entity:${entity.id}")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(actionButton(if (isPinned) "Unpin" else "Pin", onPinToggle))
        return root
    }

    private fun claimGroup(group: SilverClaimGroup, silver: SilverSnapshot): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val confidence = group.confidence?.let(::confidenceText) ?: "—"
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), borderColor)
            }
            val summary = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val statement = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(group.predicate, 12f, true).apply { setTextColor(secondaryColor) })
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
                addView(label(confidence, 14f, true).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(dp(64), -1).apply { marginStart = dp(12) })
            }
            val details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(10), 0, dp(10), dp(4))
                group.claims.forEachIndexed { index, claim ->
                    addView(claimDetail(claim, index, silver), LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
            addView(summary)
            addView(details)
            isClickable = true
            isFocusable = true
            contentDescription = buildString {
                append(group.predicate).append(", ").append(group.value).append(", ").append(confidence)
                if (group.claims.size > 1) append(", ").append(group.claims.size).append(" claims")
            }
            setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

    private fun claimDetail(claim: SilverClaim, index: Int, silver: SilverSnapshot): View = card().apply {
        addView(label("Claim ${index + 1}", 14f, true))
        addView(label("ID: ${claim.id}\nState: ${claim.state}\nConfidence: ${silver.confidence(claim)?.let(::confidenceText) ?: "Unavailable"}", 12f).apply {
            setTextColor(secondaryColor)
        })
        addView(label("Producer: ${producerText(claim.processorId, claim.processorVersion, claim.modelId, claim.modelRevision)}", 12f).apply {
            setTextColor(secondaryColor)
        })
        claim.supportingObservationIds.forEach { observationId ->
            val observation = silver.observations.firstOrNull { it.id == observationId }
            if (observation == null) {
                addView(label("Observation: $observationId", 13f, true))
            } else {
                addView(label("Observation: ${observationTitle(observation.kind)}", 13f, true), sectionMargin())
                addView(label(observationText(observation), 13f))
                val observationConfidence = observation.confidence?.let(::confidenceText) ?: "Unavailable"
                addView(label("Confidence: $observationConfidence\nProducer: ${producerText(observation.processorId, observation.processorVersion, observation.modelId, observation.modelRevision)}", 12f).apply {
                    setTextColor(secondaryColor)
                })
                observation.evidenceIds.forEach { evidenceId ->
                    val evidence = silver.evidence.firstOrNull { it.id == evidenceId }
                    val evidenceText = evidence?.excerpt?.takeIf(String::isNotBlank) ?: "No excerpt"
                    val source = evidence?.bronzeSourceId?.let { sourceId ->
                        silver.sources.firstOrNull { it.bronzeSourceId == sourceId }?.title ?: sourceId
                    } ?: evidenceId
                    addView(label("Evidence ($source): $evidenceText", 12f).apply {
                        setTextColor(secondaryColor)
                    })
                }
            }
        }
    }

    private fun confidenceText(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun producerText(
        processorId: String?,
        processorVersion: String?,
        modelId: String?,
        modelRevision: String?,
    ): String {
        val processor = processorId?.let { "$it v${processorVersion ?: "unknown"}" } ?: "Unknown"
        return modelId?.let { "$processor · $it@${modelRevision ?: "unknown"}" } ?: processor
    }

    private fun sectionMargin() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(4) }

    private fun observationTitle(kind: String) = kind.replace('-', ' ').replaceFirstChar(Char::uppercase)

    private fun observationText(observation: SilverObservation): String {
        val payload = observation.payload
        if (payload is org.json.JSONObject) {
            for (key in listOf("statement", "label", "text")) {
                payload.optString(key).takeIf(String::isNotBlank)?.let { return it }
            }
            if (payload.has("subject_ref") && payload.has("predicate") && payload.has("object_ref")) {
                return "${payload.optString("subject_ref")} ${payload.optString("predicate")} ${payload.optString("object_ref")}"
            }
            if (payload.has("subject_ref") && payload.has("predicate") && payload.has("value")) {
                return "${payload.optString("subject_ref")} ${payload.optString("predicate")}: ${payload.opt("value")}"
            }
            if (payload.has("path") && payload.has("value")) return "${payload.optString("path")}: ${payload.opt("value")}"
        }
        return payload.toString()
    }

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
