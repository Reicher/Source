package com.source.self

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

private const val DESKTOP_DRAG_MIME = "application/vnd.com.source.self.desktop-item"

/** Densely packs desktop cards and owns their native drag/drop interaction. */
class DesktopFlowLayout(context: Context) : ViewGroup(context) {
    private val gap = (6 * resources.displayMetrics.density + 0.5f).toInt()
    private var placements = emptyList<DesktopPackPlacement>()
    private var onMove: ((DesktopObjectRef, Int) -> Unit)? = null
    private var onUnpin: ((DesktopObjectRef) -> Unit)? = null
    private var dragInside = true
    private var droppedInDesktop = false
    private var highlighted: View? = null
    private var currentDropIndex = 0

    init {
        setOnDragListener { _, event -> handleDrag(event) }
    }

    fun setDragActions(
        onMove: (DesktopObjectRef, Int) -> Unit,
        onUnpin: (DesktopObjectRef) -> Unit,
    ) {
        this.onMove = onMove
        this.onUnpin = onUnpin
    }

    fun startItemDrag(source: View, ref: DesktopObjectRef): Boolean {
        source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return source.startDragAndDrop(
            ClipData(
                ClipDescription("Desktop item", arrayOf(DESKTOP_DRAG_MIME)),
                ClipData.Item(ref.key),
            ),
            View.DragShadowBuilder(source),
            DesktopDragState(ref, source),
            0,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val sizes = ArrayList<DesktopPackSize>(childCount)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            sizes += DesktopPackSize(child.measuredWidth, child.measuredHeight)
        }
        placements = packDesktopItems(sizes, available, gap)
        val usedHeight = placements.maxOfOrNull { it.bottom } ?: 0
        setMeasuredDimension(
            resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
            resolveSize(usedHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val placement = placements.getOrNull(index) ?: continue
            val x = paddingLeft + placement.x
            val y = paddingTop + placement.y
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private fun handleDrag(event: DragEvent): Boolean {
        val state = event.localState as? DesktopDragState ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                dragInside = true
                droppedInDesktop = false
                currentDropIndex = indexOfChild(state.source).coerceAtLeast(0)
                state.source.alpha = 0.32f
            }
            DragEvent.ACTION_DRAG_ENTERED -> dragInside = true
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragInside = event.x >= 0f && event.x <= width && event.y >= 0f && event.y <= height
                if (dragInside) {
                    autoScroll(event.y)
                    val target = dropTarget(
                        event.x - paddingLeft,
                        event.y - paddingTop,
                        state.source,
                    )
                    currentDropIndex = target.index
                    highlight(target.view)
                } else {
                    highlight(null)
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                dragInside = false
                highlight(null)
            }
            DragEvent.ACTION_DROP -> {
                droppedInDesktop = true
                val target = dropTarget(
                    event.x - paddingLeft,
                    event.y - paddingTop,
                    state.source,
                )
                currentDropIndex = target.index
                val dropIndex = currentDropIndex
                restoreDragVisuals(state)
                post { onMove?.invoke(state.ref, dropIndex) }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val removeFromDesktop = !droppedInDesktop && !dragInside
                restoreDragVisuals(state)
                if (removeFromDesktop) post { onUnpin?.invoke(state.ref) }
            }
        }
        return true
    }

    private fun dropTarget(x: Float, y: Float, source: View): DropTarget {
        val sourceIndex = indexOfChild(source)
        placements.getOrNull(sourceIndex)?.let { sourcePlacement ->
            if (x >= sourcePlacement.x && x <= sourcePlacement.right &&
                y >= sourcePlacement.y && y <= sourcePlacement.bottom
            ) return DropTarget(sourceIndex.coerceAtLeast(0), null)
        }
        val others = (0 until childCount).map(::getChildAt).filterNot { it === source }
        if (others.isEmpty()) return DropTarget(0, null)
        val anchor = others.minBy { child ->
            val placement = placements[indexOfChild(child)]
            val dx = x - (placement.x + placement.width / 2f)
            val dy = y - (placement.y + placement.height / 2f)
            dx * dx + dy * dy
        }
        val placement = placements[indexOfChild(anchor)]
        val verticalDifference = abs(y - (placement.y + placement.height / 2f))
        val after = if (verticalDifference > placement.height / 3f) {
            y > placement.y + placement.height / 2f
        } else {
            x > placement.x + placement.width / 2f
        }
        val anchorIndex = others.indexOf(anchor)
        return DropTarget(anchorIndex + if (after) 1 else 0, anchor)
    }

    private fun autoScroll(dragY: Float) {
        val scroll = parent as? ScrollView ?: return
        val edge = dp(52)
        val visibleTop = scroll.scrollY
        val visibleBottom = visibleTop + scroll.height
        when {
            dragY < visibleTop + edge -> scroll.scrollBy(0, -dp(14))
            dragY > visibleBottom - edge -> scroll.scrollBy(0, dp(14))
        }
    }

    private fun highlight(view: View?) {
        if (highlighted === view) return
        highlighted?.let {
            it.scaleX = 1f
            it.scaleY = 1f
        }
        highlighted = view
        view?.let {
            it.scaleX = 0.96f
            it.scaleY = 0.96f
        }
    }

    private fun restoreDragVisuals(state: DesktopDragState) {
        state.source.alpha = 1f
        highlight(null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private data class DesktopDragState(val ref: DesktopObjectRef, val source: View)
    private data class DropTarget(val index: Int, val view: View?)
}
