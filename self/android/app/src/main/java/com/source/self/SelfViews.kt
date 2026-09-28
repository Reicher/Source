package com.source.self

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
private val destructiveColor = Color.rgb(176, 63, 55)
private val destructiveSurface = Color.rgb(252, 235, 232)
private val bronzeColor = Color.rgb(166, 94, 50)
private val bronzeSurface = Color.rgb(245, 229, 218)
private val silverColor = Color.rgb(102, 114, 126)
private val silverSurface = Color.rgb(232, 237, 241)
private val goldColor = Color.rgb(176, 133, 30)
private val goldSurface = Color.rgb(248, 235, 189)
private val warningColor = Color.rgb(143, 99, 28)
private val warningSurface = Color.rgb(249, 237, 211)

enum class AppSection(val label: String) {
    DESKTOP("Desktop"), SELF("Self"), SOURCE("Source"),
}

class SelfViews(private val activity: Activity, private val bronze: BronzeStore) {
    private val thumbnails = ThumbnailLoader(bronze, dp(220), dp(300))
    private val scrollPositions = mutableMapOf<String, Int>()
    private val localDisplayTitles = mutableMapOf<String, String>()
    private var localStorageList: ListView? = null
    private var localStorageListSort: LocalStorageSort? = null

    fun close() {
        localStorageList = null
        scrollPositions.clear()
        localDisplayTitles.clear()
        thumbnails.close()
    }

    fun app(
        content: View,
        selected: AppSection,
        connected: Boolean,
        detailTitle: String?,
        detailTier: OmniResultTier?,
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
        onBack: () -> Unit,
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
            val main = FrameLayout(activity).apply {
                clipChildren = false
                clipToPadding = false
            }
            val chrome = if (detailTitle == null) {
                omniBox(
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
                )
            } else {
                detailHeader(detailTitle, detailTier, onBack)
            }
            main.addView(content, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(64) })
            main.addView(chrome, FrameLayout.LayoutParams(-1, -2))
            addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomNavigation(selected, connected, onSection), LinearLayout.LayoutParams(-1, -2))
            post { windowInsetsController?.show(WindowInsets.Type.systemBars()) }
        }
    }

    fun pairing(status: String, message: String?): View = FrameLayout(activity).apply {
        setBackgroundColor(backgroundColor)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), borderColor)
            }
            addView(label("Self", 30f, true).apply {
                setTextColor(accentColor)
                gravity = Gravity.CENTER
            })
            addView(label(status, 20f, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (status.contains("Connecting")) {
                addView(ProgressBar(activity).apply {
                    indeterminateTintList = ColorStateList.valueOf(accentColor)
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(12)
                })
            }
            message?.let {
                addView(label(it, 15f).apply {
                    setTextColor(disconnectedColor)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            marginStart = dp(28)
            marginEnd = dp(28)
        })
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
        elevation = dp(8).toFloat()
        val results = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(2))
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = dp(10).toFloat()
            }
        }
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
        val clear = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_clear)
            imageTintList = ColorStateList.valueOf(secondaryColor)
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
            contentDescription = "Clear search"
            visibility = View.GONE
            isEnabled = enabled
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { editor.setText("") }
        }
        fun showResults(query: String) {
            results.removeAllViews()
            if (!enabled) {
                results.visibility = View.GONE
                return
            }
            val matches = searchOmniBox(query, candidates, limit = 5)
            results.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
            matches.forEachIndexed { index, result ->
                results.addView(omniResult(result, onOpenResult), LinearLayout.LayoutParams(-1, -2).apply {
                    if (index > 0) topMargin = dp(2)
                })
            }
        }
        fun update(value: String) {
            add.visibility = if (value.isNotEmpty() || attachmentCount > 0) View.VISIBLE else View.GONE
            clear.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE
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
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_attach_file)
                imageTintList = ColorStateList.valueOf(secondaryColor)
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
                contentDescription = "Attach files"
                isClickable = true
                isFocusable = enabled
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.55f
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener { onAttach() }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(editor, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(clear, LinearLayout.LayoutParams(dp(42), dp(42)))
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
        minimumHeight = dp(48)
        setPadding(dp(14), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            setColor(tierSurface(result.tier))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), tierAccent(result.tier))
        }
        addView(label(result.title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tierBadge(result.tier), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        contentDescription = "${result.title}, ${result.tier.displayName}"
        isClickable = true
        isFocusable = true
        setOnClickListener { onOpen(result) }
    }

    private fun detailHeader(title: String, tier: OmniResultTier?, onBack: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), borderColor)
            }
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(primaryColor)
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
                contentDescription = "Back"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) })
            addView(label(title, 19f, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(8)
            })
            tier?.let {
                addView(tierBadge(it), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
            }
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
        onOpen: (String) -> Unit,
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
                val selected = option == sort
                val sortLabel = when {
                    !selected -> option.label
                    option == LocalStorageSort.NAME -> "Name ↑"
                    else -> "Modified ↓"
                }
                addView(label(sortLabel, 14f, selected).apply {
                    gravity = Gravity.CENTER
                    minimumWidth = dp(88)
                    minimumHeight = dp(48)
                    setTextColor(if (selected) accentColor else secondaryColor)
                    background = GradientDrawable().apply {
                        setColor(if (selected) selectedColor else Color.TRANSPARENT)
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
            setOnItemClickListener { adapter, _, position, _ ->
                onOpen((adapter.getItemAtPosition(position) as BronzeItem).id)
            }
            setSelectionFromTop(previousPosition, previousTop)
        }
        localStorageList = list
        localStorageListSort = sort
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun source(
        connected: Boolean,
        disconnectedAt: Long?,
        silver: SilverSnapshot,
        onBronze: (String) -> Unit,
        onEntity: (String) -> Unit,
    ): View = screen { body ->
        val now = System.currentTimeMillis()
        val localBronze = bronze.all()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            silver.statusError?.let { problem ->
                addView(infoBanner(problem, destructiveColor, destructiveSurface),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            addView(label("Queue (${silver.jobs.queuedCount})", 19f, true), sectionMargin())
            if (silver.jobs.queued.isEmpty()) {
                addView(label("No jobs waiting", 14f).apply { setTextColor(secondaryColor) })
            } else {
                silver.jobs.queued.forEach { job ->
                    addView(jobRow(job, resolveJobBronzeSourceId(job, localBronze), onBronze))
                }
            }
            if (silver.entities.isNotEmpty()) {
                addView(label("Entities", 19f, true), sectionMargin())
                silver.entities
                    .map { entity -> entity to silver.activeClaimCount(entity.id) }
                    .sortedWith(
                        compareByDescending<Pair<SilverEntity, Int>> { it.second }
                            .thenBy { silver.label(it.first).lowercase(Locale.getDefault()) }
                            .thenBy { it.first.id },
                    )
                    .forEach { (entity, claimCount) ->
                    addView(entityRow(entity, silver, claimCount) { onEntity(entity.id) },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                }
            }
        }
        body.addView(FrameLayout(activity).apply {
            addView(ScrollView(activity).apply {
                alpha = if (connected) 1f else 0.24f
                addView(content)
                preserveScroll("source")
            }, FrameLayout.LayoutParams(-1, -1))
            if (!connected) {
                addView(View(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setBackgroundColor(Color.argb(72, 224, 224, 224))
                    isClickable = true
                }, FrameLayout.LayoutParams(-1, -1))
                val duration = disconnectedAt?.let {
                    connectionDurationPresentation(now - it)
                }
                addView(label(
                    if (duration == null) "Disconnected" else "Disconnected · $duration",
                    14f,
                    true,
                ).apply {
                    gravity = Gravity.CENTER
                    setTextColor(disconnectedColor)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        setColor(surfaceColor)
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), disconnectedColor)
                    }
                    elevation = dp(4).toFloat()
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun jobRow(job: SourceJob, sourceId: String?, onBronze: (String) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(9))
        val failed = job.state.equals("failed", ignoreCase = true)
        val running = job.state.equals("running", ignoreCase = true)
        val stateColor = when {
            failed -> destructiveColor
            running -> accentColor
            else -> secondaryColor
        }
        val stateSurface = when {
            failed -> destructiveSurface
            running -> selectedColor
            else -> surfaceColor
        }
        val cardBackground = GradientDrawable().apply {
            setColor(stateSurface)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), stateColor)
        }
        background = cardBackground
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(job.title, 15f, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(statusBadge(humanize(job.state), stateColor),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })
        val kind = if (job.kind == "silver_extraction") {
            "Silver extraction"
        } else {
            "Sync ${if (job.direction == "to_source") "to Source" else "to Self"}"
        }
        addView(label(kind, 13f).apply { setTextColor(secondaryColor) })
        sourceId?.let { id ->
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), cardBackground, null)
            contentDescription = "${job.title}, $kind, open Bronze file"
            isClickable = true
            isFocusable = true
            setOnClickListener { onBronze(id) }
        }
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
        val metadata = overlayButton("Metadata") { showMetadata(item) }
        var previewImage: ImageView? = null
        val content = FrameLayout(activity).apply {
            when {
                item.mime.startsWith("image/") -> {
                    val image = imageView(item, displayMaxDimension())
                    if (image == null) {
                        addView(unavailablePreview(), FrameLayout.LayoutParams(-1, -1))
                    } else {
                        image.scaleType = ImageView.ScaleType.MATRIX
                        image.contentDescription = "Open image full screen"
                        image.isClickable = true
                        image.isFocusable = true
                        image.setOnClickListener { onFullScreen() }
                        addView(image, FrameLayout.LayoutParams(-1, -1))
                        previewImage = image
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
            addView(metadata,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(8), dp(8), 0)
                })
        }
        previewImage?.addOnLayoutChangeListener { image, _, _, _, _, _, _, _, _ ->
            positionTopCenteredPreview(content, image as ImageView, metadata)
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val knowledgeLabel = when {
            knowledge.source != null -> "Claims · ${knowledge.activeClaimCount}"
            knowledge.processing != null -> "Claims · …"
            else -> "Claims · 0"
        }
        val hasClaims = knowledge.activeClaimCount > 0
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(compactActionButton(
                knowledgeLabel,
                onKnowledge,
                enabled = hasClaims,
            ), LinearLayout.LayoutParams(0, dp(48), 1.35f))
            addView(compactActionButton(
                if (isPinned) "Unpin" else "Pin",
                onPinToggle,
            ),
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
            addView(compactActionButton(
                "Delete",
                onDelete,
                destructive = true,
            ),
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    fun fullScreenImage(item: BronzeItem, onExit: () -> Unit): View = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        contentDescription = "Close full-screen image"
        isClickable = true
        isFocusable = true
        setOnClickListener { onExit() }
        activity.window.statusBarColor = Color.BLACK
        activity.window.navigationBarColor = Color.BLACK
        activity.window.decorView.systemUiVisibility = 0
        post {
            windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
        imageView(item, displayMaxDimension())?.let { image ->
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.contentDescription = "Full-screen image"
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 0, 0, 0))
            }
            contentDescription = "Close full-screen image"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onExit() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(16), dp(16), 0, 0)
        })
    }

    fun silverDetail(
        item: BronzeItem,
        knowledge: SilverKnowledge,
        silver: SilverSnapshot,
        onEntity: (String) -> Unit,
    ): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label("Claims", 23f, true))
        root.addView(label("From ${item.title}", 13f).apply {
            setTextColor(secondaryColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (knowledge.source?.stale == true) {
            val state = knowledge.processing?.presentation("Updating") ?: "Updating"
            body.addView(label(state, 13f).apply {
                setTextColor(secondaryColor)
            })
        }
        if (knowledge.source == null) {
            val state = knowledge.processing?.presentation() ?: "No knowledge"
            body.addView(label(state, 15f).apply { setTextColor(secondaryColor) })
        }
        knowledge.source?.coveragePresentation()?.let { coverage ->
            body.addView(label(coverage, 13f).apply { setTextColor(secondaryColor) })
        }
        val activeClaims = knowledge.activeClaims.sortedWith(
            compareBy<SilverClaim> { silver.describe(it) }.thenBy { it.id },
        )
        if (activeClaims.isNotEmpty()) {
            body.addView(label(
                "${activeClaims.size} current ${if (activeClaims.size == 1) "claim" else "claims"}",
                13f,
            ).apply { setTextColor(secondaryColor) })
            activeClaims.forEach { claim ->
                body.addView(sourceClaimRow(claim, silver, onEntity), LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(3)
                })
            }
        } else if (knowledge.source != null) {
            body.addView(label("No current claims", 15f).apply { setTextColor(secondaryColor) })
        }
        val findings = knowledge.unresolvedFindings()
        if (findings.isNotEmpty()) {
            body.addView(label("Possible findings", 17f, true), sectionMargin())
        }
        findings.forEach { finding ->
            body.addView(findingRow(finding), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(3)
            })
        }
        root.addView(ScrollView(activity).apply {
            addView(body)
            preserveScroll("knowledge:${item.id}")
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun sourceClaimRow(
        claim: SilverClaim,
        silver: SilverSnapshot,
        onEntity: (String) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(silverSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), silverColor)
            },
            null,
        )
        val subject = silver.entities.firstOrNull { it.id == claim.subjectEntityId }
        val description = if (claim.objectEntityId == null && subject != null) {
            "${silver.label(subject)} — ${silver.describe(claim)}"
        } else {
            silver.describe(claim)
        }
        addView(label(description, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        silver.confidence(claim)?.let { confidence ->
            addView(valueBadge(confidenceText(confidence), silverColor, surfaceColor),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        contentDescription = description
        if (subject != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onEntity(subject.id) }
        }
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
            body.addView(label("Bronze sources · ${sources.size}", 17f, true).apply {
                setTextColor(bronzeColor)
            }, sectionMargin())
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
            addView(compactActionButton(
                if (isPinned) "Unpin from Desktop" else "Pin to Desktop",
                onPinToggle,
            ), LinearLayout.LayoutParams(-1, dp(48)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return root
    }

    private fun claimGroup(group: SilverClaimGroup, onEntity: (String) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val confidence = group.confidence?.let(::confidenceText)
            background = GradientDrawable().apply {
                setColor(silverSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), silverColor)
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
                    addView(valueBadge(value, silverColor, surfaceColor),
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
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
                setColor(silverSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), silverColor)
            },
            null,
        )
        addView(label(silver.label(entity), 15f, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(valueBadge(
            "$claimCount ${if (claimCount == 1) "claim" else "claims"}",
            silverColor,
            surfaceColor,
        ), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        contentDescription = "${silver.label(entity)}, $claimCount ${if (claimCount == 1) "claim" else "claims"}"
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun findingRow(finding: SilverFinding): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(silverSurface)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), silverColor)
        }
        val summary = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val statement = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(finding.predicate, 12f, true).apply { setTextColor(secondaryColor) })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(finding.value, 15f))
                    if (finding.count > 1) addView(label("×${finding.count}", 13f, true).apply {
                        setTextColor(accentColor)
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                })
            }
            addView(statement, LinearLayout.LayoutParams(0, -2, 1f))
            finding.confidence?.let { confidence ->
                addView(valueBadge(confidenceText(confidence), silverColor, surfaceColor),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            }
        }
        addView(summary)
        finding.sourceExcerpt?.let { excerpt ->
            addView(label(excerpt, 13f).apply {
                setTextColor(secondaryColor)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        contentDescription = buildString {
            append(finding.predicate).append(", ").append(finding.value)
            finding.confidence?.let { append(", ").append(confidenceText(it)) }
            if (finding.count > 1) append(", ").append(finding.count).append(" findings")
        }
    }

    private fun sourceRow(title: String?, action: () -> Unit): View = label(
        title?.let { "$it  ›" } ?: "Source  ›",
        13f,
        true,
    ).apply {
        setTextColor(bronzeColor)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(bronzeSurface)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), bronzeColor)
            },
            null,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
    }

    private fun unavailablePreview(): TextView = label("Preview unavailable", 15f).apply {
        setTextColor(secondaryColor)
        gravity = Gravity.CENTER
    }

    private fun overlayButton(value: String, action: () -> Unit): TextView = label(value, 12f, true).apply {
        setPadding(dp(9), dp(6), dp(9), dp(6))
        minHeight = dp(44)
        minWidth = dp(48)
        gravity = Gravity.CENTER
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

    private fun positionTopCenteredPreview(container: FrameLayout, image: ImageView, overlay: View) {
        val drawable = image.drawable ?: return
        val viewportWidth = image.width - image.paddingLeft - image.paddingRight
        val viewportHeight = image.height - image.paddingTop - image.paddingBottom
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0 ||
            viewportWidth <= 0 || viewportHeight <= 0
        ) return
        val layout = topCenteredImageLayout(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            viewportWidth,
            viewportHeight,
        )
        image.imageMatrix = Matrix().apply {
            setScale(layout.scale, layout.scale)
            postTranslate(image.paddingLeft + layout.left, image.paddingTop + layout.top)
        }
        val actualRight = image.left + image.paddingLeft + layout.right
        (overlay.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.topMargin = image.top + image.paddingTop + layout.top.roundToInt() + dp(8)
            params.marginEnd = (container.width - actualRight).roundToInt() + dp(8)
            overlay.layoutParams = params
        }
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
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Metadata")
            .setView(content)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    fun confirmDelete(item: BronzeItem, onDelete: () -> Unit) {
        val dialog = AlertDialog.Builder(activity)
            .setMessage("Delete ${item.title}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> onDelete() }
            .create()
        dialog.setOnShowListener { styleDialog(dialog, destructive = true) }
        dialog.show()
    }

    private fun styleDialog(dialog: AlertDialog, destructive: Boolean = false) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(18).toFloat()
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            setTextColor(if (destructive) destructiveColor else accentColor)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            isAllCaps = false
            setTextColor(secondaryColor)
        }
    }

    private fun metadataRow(name: String, value: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(label(name, 12f, true).apply { setTextColor(secondaryColor) })
        addView(label(value, 15f).apply { setTextIsSelectable(true) })
    }

    private fun syncStatus(item: BronzeItem): String =
        if (item.ackedRevision == item.revision) "Synced" else "Sync pending"

    private fun tierAccent(tier: OmniResultTier): Int = when (tier) {
        OmniResultTier.BRONZE -> bronzeColor
        OmniResultTier.SILVER -> silverColor
        OmniResultTier.GOLD -> goldColor
    }

    private fun tierSurface(tier: OmniResultTier): Int = when (tier) {
        OmniResultTier.BRONZE -> bronzeSurface
        OmniResultTier.SILVER -> silverSurface
        OmniResultTier.GOLD -> goldSurface
    }

    private fun tierBadge(tier: OmniResultTier): TextView =
        valueBadge(tier.displayName, tierAccent(tier), surfaceColor)

    private fun valueBadge(value: String, color: Int, surface: Int): TextView =
        label(value, 12f, true).apply {
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), color)
            }
        }

    private fun statusBadge(value: String, color: Int): TextView =
        valueBadge(value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }, color, surfaceColor)

    private fun infoBanner(value: String, color: Int, surface: Int): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), color)
            }
            addView(valueBadge("!", color, Color.TRANSPARENT))
            addView(label(value, 14f).apply { setTextColor(color) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        }

    private fun localDisplayTitle(item: BronzeItem): String = localDisplayTitles.getOrPut(item.id) {
        if (item.mime == "text/plain") {
            runCatching {
                textPreview(item).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            }.getOrNull()?.take(120)?.ifBlank { item.title } ?: item.title
        } else item.title
    }

    private fun fileKindBadge(item: BronzeItem): TextView {
        val kind = when {
            item.mime.startsWith("image/") -> "IMG"
            item.mime == "text/plain" && item.title.startsWith("note-") -> "NOTE"
            item.mime.contains("csv") || item.title.endsWith(".csv", ignoreCase = true) -> "CSV"
            item.mime.startsWith("text/") -> "TXT"
            item.mime.contains("pdf") -> "PDF"
            else -> "FILE"
        }
        return valueBadge(kind, bronzeColor, bronzeSurface)
    }

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

    private fun confidenceText(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100)

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

    private fun compactActionButton(
        value: String,
        action: () -> Unit,
        destructive: Boolean = false,
        enabled: Boolean = true,
    ): Button = Button(activity).apply {
        val buttonFill = when {
            !enabled -> elevatedColor
            destructive -> destructiveSurface
            else -> surfaceColor
        }
        val buttonBorder = when {
            !enabled -> borderColor
            destructive -> destructiveColor
            else -> borderColor
        }
        text = value
        textSize = 13f
        isAllCaps = false
        setTextColor(when {
            !enabled -> secondaryColor
            destructive -> destructiveColor
            else -> primaryColor
        })
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.72f
        minimumWidth = 0
        minimumHeight = 0
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            GradientDrawable().apply {
                setColor(buttonFill)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), buttonBorder)
            },
            null,
        )
        if (enabled) setOnClickListener { action() }
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
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            elevation = dp(6).toFloat()
            addView(View(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(borderColor)
            }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                AppSection.entries.forEach { section ->
                    val tab = FrameLayout(activity).apply {
                        background = GradientDrawable().apply {
                            setColor(if (section == selected) selectedColor else surfaceColor)
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
            }, LinearLayout.LayoutParams(-1, dp(54)))
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
                minimumHeight = dp(64)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, null)
                contentDescription = "Open ${item.title}"
                addView(fileKindBadge(item), LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(10)
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(localDisplayTitle(item), 15f, true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    val subtitle = if (item.mime == "text/plain" && item.title.startsWith("note-")) {
                        "${item.title}  ·  ${date.format(Date(item.modified))}"
                    } else {
                        "${fileSize(item.size)}  ·  ${date.format(Date(item.modified))}"
                    }
                    addView(label(subtitle, 12f).apply {
                        setTextColor(secondaryColor)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
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
            val cardBackground = GradientDrawable().apply {
                setColor(desktopTileColor(ref))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), desktopTileBorderColor(ref))
            }
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), cardBackground, null)
            contentDescription = item?.title ?: entity?.let(silver::label) ?: "Item"
            when {
                entity != null -> addView(label(silver.label(entity), 16f, true).apply {
                    setTextColor(silverColor)
                })
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
                addView(statusBadge("Syncing", warningColor),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
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

    private fun desktopTileColor(ref: DesktopObjectRef): Int = when (ref.objectType) {
        DESKTOP_OBJECT_SILVER -> silverSurface
        else -> bronzeSurface
    }

    private fun desktopTileBorderColor(ref: DesktopObjectRef): Int = when (ref.objectType) {
        DESKTOP_OBJECT_SILVER -> silverColor
        else -> bronzeColor
    }

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
                val sample = imageSampleSize(info.size.width, info.size.height, maxDimension)
                decoder.setTargetSampleSize(sample)
            }
        }.getOrNull() ?: return null
        return ImageView(activity).apply { setImageBitmap(bitmap); adjustViewBounds = true }
    }

    private fun displayMaxDimension(): Int = activity.resources.displayMetrics.run {
        maxOf(widthPixels, heightPixels).coerceAtLeast(1)
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
