package com.antourage.weaverlib.ui.fab

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Property
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import com.antourage.weaverlib.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.stateful.ExtendableSavedState
import android.graphics.RectF


private val STATE_KEY = BadgeFab::class.java.name + ".STATE"
private const val TEXT_STATE = "TEXT"

private const val NORMAL_MAX_COUNT_TEXT = "live"

private const val MINI_MAX_COUNT_TEXT = "9+"

private const val TEXT_SIZE_DP = 11
private const val TEXT_PADDING_DP = 2
private val MASK_COLOR = Color.parseColor("#33000000") // Translucent black as mask color
private val ANIMATION_INTERPOLATOR = OvershootInterpolator()
private const val RIGHT_TOP_POSITION = 0
private const val LEFT_BOTTOM_POSITION = 1
private const val LEFT_TOP_POSITION = 2
private const val RIGHT_BOTTOM_POSITION = 3

/**
 * A [FloatingActionButton] subclass that shows a badge on right top corner.
 */
class BadgeFab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.floatingActionButtonStyle
) : FloatingActionButton(context, attrs, defStyleAttr) {

    private val animationProperty = object : Property<BadgeFab, Float>(Float::class.java, "animation") {

        override fun set(badgeFab: BadgeFab, value: Float) {
            animationFactor = value
            postInvalidateOnAnimation()
        }

        override fun get(badgeFab: BadgeFab): Float {
            return 0f
        }
    }

    private val textSize = TEXT_SIZE_DP * resources.displayMetrics.density
    private val textPadding = TEXT_PADDING_DP * resources.displayMetrics.density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MASK_COLOR
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Style.FILL_AND_STROKE
        textSize = this@BadgeFab.textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }
    private val textBounds: Rect = run {
        val maxCountText = NORMAL_MAX_COUNT_TEXT
        val textBounds = Rect()
        textPaint.getTextBounds(maxCountText, 0, maxCountText.length, textBounds)
        textBounds
    }
    private val circleBounds = Rect()
    private val contentBounds = Rect()

    private val animationDuration = resources.getInteger(android.R.integer.config_shortAnimTime)
    private var animationFactor = 1f
    private var animator = ObjectAnimator()
    private val isAnimating: Boolean
        get() = animator.isRunning
    private val isSizeMini: Boolean
        get() = size == SIZE_MINI
    private val badgePosition: Int
    private var countText: String = ""

    var textBadge: String = ""
        set( value) {
            if (value == field) return
            field = value

            updateCountText()
            if (ViewCompat.isLaidOut(this)) {
                startAnimation()
            }
        }

    init {
        val styledAttributes = context.theme.obtainStyledAttributes(
            attrs, R.styleable.BadgeFab, 0, 0
        )
        textPaint.color = styledAttributes.getColor(R.styleable.BadgeFab_badgeTextColor, Color.WHITE)
        circlePaint.color = styledAttributes.getColor(R.styleable.BadgeFab_badgeBackgroundColor, getDefaultBadgeColor())
        badgePosition = styledAttributes.getInt(R.styleable.BadgeFab_badgePosition, RIGHT_TOP_POSITION)
        styledAttributes.recycle()

        updateCountText()
    }

    private fun updateCountText() {
        countText = textBadge
    }

    fun setTextToBadge(text:String){
        textBadge = text
    }

    private fun getDefaultBadgeColor(): Int = run {
        val colorStateList = backgroundTintList
        if (colorStateList != null) {
            colorStateList.defaultColor
        } else {
            val background = background
            if (background is ColorDrawable) {
                background.color
            } else {
                circlePaint.color
            }
        }
    }


    private fun startAnimation() {
        val start = 0f
        val end = 1f

        if (isAnimating) animator.cancel()

        animator = ObjectAnimator.ofObject(
            this, animationProperty, null, start, end
        ).apply {
            interpolator = ANIMATION_INTERPOLATOR
            duration = animationDuration.toLong()
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        calculateCircleBounds()
    }

    private fun calculateCircleBounds() {
        val circleRadius = Math.max(textBounds.width(), textBounds.height()) / 2f + textPadding
        val circleEnd = (circleRadius * 2).toInt()
        if (isSizeMini) {
            val circleStart = (circleRadius / 2).toInt()
            circleBounds.set(circleStart, circleStart, circleEnd, circleEnd)
        } else {
            val circleStart = 0
            circleBounds.set(circleStart, circleStart, (circleRadius * 2).toInt(), (circleRadius * 2).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (textBadge != "" || isAnimating) {
            if (getContentRect(contentBounds)) {
                val newLeft: Int
                val newTop: Int
                when (badgePosition) {
                    LEFT_BOTTOM_POSITION -> {
                        newLeft = contentBounds.left
                        newTop = contentBounds.bottom - circleBounds.height()
                    }
                    LEFT_TOP_POSITION -> {
                        newLeft = contentBounds.left
                        newTop = contentBounds.top
                    }
                    RIGHT_BOTTOM_POSITION -> {
                        newLeft = contentBounds.left + contentBounds.width() - circleBounds.width()
                        newTop = contentBounds.bottom - circleBounds.height()
                    }
                    RIGHT_TOP_POSITION -> {
                        newLeft = contentBounds.left + contentBounds.width() - circleBounds.width()
                        newTop = contentBounds.top
                    }
                    else -> {
                        newLeft = contentBounds.left + contentBounds.width() - circleBounds.width()
                        newTop = contentBounds.top
                    }
                }
                circleBounds.offsetTo(newLeft, newTop)
            }
            val cx = circleBounds.centerX().toFloat()
            val cy = circleBounds.centerY().toFloat()
            val radius = circleBounds.width() / 2f * animationFactor
            if(textBadge != "") {
                // Solid rectangle with rounded corners
                val rect = RectF(cx-radius-10f, cy+radius, cx+radius+10, cy-radius)
                canvas.drawRoundRect(rect, 8f, 8f, circlePaint)
//                canvas.drawCircle(cx, cy, radius+10, circlePaint)
                // Mask circle
                //may be needed in future
                //canvas.drawCircle(cx, cy, radius, maskPaint)
                // Count text
                textPaint.textSize = textSize * animationFactor
                canvas.drawText(countText, cx, cy + textBounds.height() / 2f, textPaint)
            }
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        if (superState is ExtendableSavedState) {
         //   superState.extendableStates.put(STATE_KEY, bundleOf(COUNT_STATE to count))
        }
        return superState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState(state)
        if (state !is ExtendableSavedState) return

        val bundle = state.extendableStates.get(STATE_KEY)
        //count = bundle?.getInt(COUNT_STATE) ?: 0

        requestLayout()
    }
}