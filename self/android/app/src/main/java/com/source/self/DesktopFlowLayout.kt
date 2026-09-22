package com.source.self

import android.content.Context
import android.view.ViewGroup
import kotlin.math.max

/** Wraps desktop cards at their own measured width and height. */
class DesktopFlowLayout(context: Context) : ViewGroup(context) {
    private val gap = (8 * resources.displayMetrics.density + 0.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var rowWidth = 0
        var rowHeight = 0
        var usedHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val childWidth = child.measuredWidth
            if (rowWidth > 0 && rowWidth + gap + childWidth > available) {
                usedHeight += rowHeight + gap
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += if (rowWidth == 0) childWidth else gap + childWidth
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        if (childCount > 0) usedHeight += rowHeight
        setMeasuredDimension(
            resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
            resolveSize(usedHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val limit = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > paddingLeft && x + child.measuredWidth > limit) {
                x = paddingLeft
                y += rowHeight + gap
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
