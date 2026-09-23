package com.source.self

import android.app.Activity
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
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
private val backgroundColor = Color.rgb(14, 20, 21)
private val surfaceColor = Color.rgb(29, 39, 39)
private val elevatedColor = Color.rgb(37, 49, 48)
private val accentColor = Color.rgb(116, 220, 167)
private val disconnectedColor = Color.rgb(220, 108, 104)
private val secondaryColor = Color.rgb(189, 201, 195)

enum class AppSection(val label: String) {
    DESKTOP("Desktop"), SELF("Self"), SOURCE("Source"),
}

class SelfViews(private val activity: Activity, private val bronze: BronzeStore) {
    private val thumbnails = ThumbnailLoader(bronze, dp(220), dp(300))
    private var desktopList: ListView? = null

    fun close() = thumbnails.close()

    fun app(
        content: View,
        selected: AppSection,
        connected: Boolean,
        onSection: (AppSection) -> Unit,
    ): View {
        activity.window.statusBarColor = backgroundColor
        activity.window.navigationBarColor = backgroundColor
        activity.window.decorView.systemUiVisibility = 0
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(16), dp(14), dp(16), dp(8))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(16) + bars.left, dp(14) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom)
                insets
            }
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
        onAdd: () -> Unit,
        onOpen: (DesktopObjectRef) -> Unit,
        onItemMenu: (DesktopObjectRef) -> Unit,
    ): View = FrameLayout(activity).apply {
        if (refs.isNotEmpty()) {
            val previousPosition = desktopList?.firstVisiblePosition ?: 0
            val previousTop = desktopList?.getChildAt(0)?.top ?: 0
            val list = ListView(activity).apply {
                divider = null
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, dp(4), 0, dp(88))
                adapter = DesktopAdapter(refs, onOpen, onItemMenu)
                setSelectionFromTop(previousPosition, previousTop)
            }
            desktopList = list
            addView(list, FrameLayout.LayoutParams(-1, -1))
        } else {
            desktopList = null
            addView(label("Desktop is empty", 17f).apply {
                setTextColor(secondaryColor); gravity = Gravity.CENTER; setPadding(0, dp(64), 0, 0)
            }, FrameLayout.LayoutParams(-1, -2))
        }
        addView(label("+", 36f).apply {
            gravity = Gravity.CENTER
            setTextColor(backgroundColor)
            contentDescription = "Add"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            elevation = dp(6).toFloat()
            setOnClickListener { onAdd() }
        }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(12)
            bottomMargin = dp(16)
        })
    }

    fun self(localCount: Int): View = screen("Self") { body ->
        body.addView(card().apply {
            addView(label("Local storage", 17f, true))
            addView(label("$localCount ${if (localCount == 1) "item" else "items"}", 14f).apply {
                setTextColor(secondaryColor)
            })
        })
    }

    fun source(
        connected: Boolean,
        disconnectedAt: Long?,
        message: String?,
        silver: SilverSnapshot,
        onEntity: (String) -> Unit,
    ): View = screen("Source") { body ->
        val status = when {
            connected -> "Connected"
            disconnectedAt != null -> "Disconnected since ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(disconnectedAt))}"
            else -> "Disconnected"
        }
        body.addView(card().apply {
            addView(label(status, 18f, true).apply { setTextColor(if (connected) accentColor else Color.WHITE) })
            message?.let { addView(label(it, 14f).apply { setTextColor(secondaryColor) }) }
        })
        if (silver.entities.isNotEmpty()) {
            body.addView(label("Entities", 19f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            body.addView(ScrollView(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    silver.entities.sortedBy(silver::label).forEach { entity ->
                        addView(actionButton(silver.label(entity)) { onEntity(entity.id) })
                    }
                })
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    fun bronzeDetail(
        item: BronzeItem,
        knowledge: SilverKnowledge,
        onKnowledge: () -> Unit,
        onArchive: () -> Unit,
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
        root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val knowledgeLabel = when {
            knowledge.source != null -> "Knowledge · ${knowledge.itemCount} extracted items"
            knowledge.processing != null -> "Knowledge · ${knowledge.processing.state.replaceFirstChar(Char::uppercase)}"
            else -> "Knowledge · No extracted items"
        }
        root.addView(actionButton(knowledgeLabel, onKnowledge))
        root.addView(actionButton("Archive", onArchive))
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
                    addView(label("${observation.processorId} v${observation.processorVersion}", 12f).apply {
                        setTextColor(secondaryColor)
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    fun entityDetail(entity: SilverEntity, silver: SilverSnapshot, onBronze: (String) -> Unit): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(label(silver.label(entity), 25f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val claims = silver.claimsFor(entity.id)
        if (claims.isNotEmpty()) {
            body.addView(label("Knowledge", 18f, true))
            claims.forEach { claim ->
                val display = claim.value?.toString() ?: claim.objectEntityId?.let { objectId ->
                    silver.entities.firstOrNull { it.id == objectId }?.let(silver::label)
                } ?: "Unknown"
                body.addView(label("${claim.predicate.replace('-', ' ')} · $display", 15f))
            }
        }
        val sources = silver.supportingBronze(entity.id)
        if (sources.isNotEmpty()) {
            body.addView(label("Supporting Bronze", 18f, true), sectionMargin())
            sources.forEach { sourceId ->
                val title = silver.sources.firstOrNull { it.bronzeSourceId == sourceId }?.title ?: sourceId
                body.addView(actionButton(title) { onBronze(sourceId) })
            }
        }
        root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun sectionMargin() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(6) }

    private fun observationTitle(kind: String) = kind.replace('-', ' ').replaceFirstChar(Char::uppercase)

    private fun observationText(observation: SilverObservation): String {
        val payload = observation.payload
        if (payload is org.json.JSONObject) {
            for (key in listOf("statement", "label", "text")) {
                payload.optString(key).takeIf(String::isNotBlank)?.let { return it }
            }
            if (payload.has("path") && payload.has("value")) return "${payload.optString("path")}: ${payload.opt("value")}"
        }
        return payload.toString()
    }

    fun label(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun actionButton(value: String, action: () -> Unit): Button = Button(activity).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun screen(
        title: String,
        content: (LinearLayout) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 27f, true), LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content(this)
    }

    private fun bottomNavigation(selected: AppSection, connected: Boolean, onSection: (AppSection) -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            AppSection.entries.forEach { section ->
                val tab = FrameLayout(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (section == selected) elevatedColor else backgroundColor)
                        cornerRadius = dp(12).toFloat()
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
                        setTextColor(if (section == selected) accentColor else Color.WHITE)
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
                addView(tab, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                })
            }
        }

    private inner class DesktopAdapter(
        private val refs: List<DesktopObjectRef>,
        private val onOpen: (DesktopObjectRef) -> Unit,
        private val onMenu: (DesktopObjectRef) -> Unit,
    ) : BaseAdapter() {
        private val columns = maxOf(2, activity.resources.configuration.smallestScreenWidthDp / 220)

        override fun getCount(): Int = (refs.size + columns - 1) / columns

        override fun getItem(position: Int): Any = refs[position * columns]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? DesktopFlowLayout) ?: DesktopFlowLayout(activity)
            row.removeAllViews()
            val first = position * columns
            refs.subList(first, minOf(first + columns, refs.size)).forEach { ref ->
                row.addView(desktopTile(ref, onOpen, onMenu))
            }
            return row
        }
    }

    private fun desktopTile(
        ref: DesktopObjectRef,
        onOpen: (DesktopObjectRef) -> Unit,
        onMenu: (DesktopObjectRef) -> Unit,
    ): View {
        val item = if (ref.objectType == DESKTOP_OBJECT_BRONZE) bronze.get(ref.objectId) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(surfaceColor); cornerRadius = dp(12).toFloat() }
            contentDescription = item?.title ?: "Item"
            when {
                item == null -> addView(label("Item", 16f, true))
                item.mime.startsWith("image/") -> {
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    addView(image)
                    thumbnails.bind(item, image)
                }
                item.mime == "text/plain" -> {
                    val preview = runCatching { excerptWords(textPreview(item)) }.getOrDefault("")
                    addView(label(if (preview.isBlank()) "Empty note" else preview, 15f).apply {
                        maxWidth = dp(220)
                    })
                }
                else -> {
                    addView(label(fileIcon(item.mime), 30f))
                    addView(label(fileType(item.mime), 12f).apply { setTextColor(secondaryColor) })
                }
            }
            if (item != null && item.ackedRevision != item.revision) {
                addView(label("•", 16f).apply { setTextColor(secondaryColor); gravity = Gravity.END })
            }
            setOnClickListener { onOpen(ref) }
            setOnLongClickListener { onMenu(ref); true }
        }
    }

    private fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(surfaceColor); cornerRadius = dp(12).toFloat() }
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

    private fun fileIcon(mime: String) = when {
        mime == "application/pdf" -> "▤"
        mime.startsWith("audio/") -> "♪"
        mime.startsWith("video/") -> "▶"
        else -> "◇"
    }

    private fun fileType(mime: String) = mime.substringAfterLast('/').uppercase(Locale.getDefault()).take(12)
}
