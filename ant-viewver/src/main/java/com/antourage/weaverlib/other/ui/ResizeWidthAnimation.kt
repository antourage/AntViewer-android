package com.antourage.weaverlib.other.ui

import android.view.View
import android.view.animation.Animation
import android.view.animation.Transformation

internal class ResizeWidthAnimation(private val mView: View, private val mWidth: Int) :
    Animation() {
    private val mStartWidth: Int = mView.width

    override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
        val newWidth = mStartWidth + ((mWidth - mStartWidth) * interpolatedTime).toInt()

        mView.layoutParams.width = newWidth
        mView.requestLayout()
    }

    override fun willChangeBounds(): Boolean {
        return true
    }
}