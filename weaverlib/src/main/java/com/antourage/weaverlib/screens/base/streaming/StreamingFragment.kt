package com.antourage.weaverlib.screens.base.streaming

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.support.constraint.ConstraintLayout
import android.support.graphics.drawable.Animatable2Compat
import android.support.graphics.drawable.AnimatedVectorDrawableCompat
import android.support.v4.content.ContextCompat
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.antourage.weaverlib.R
import com.antourage.weaverlib.other.calculatePlayerHeight
import com.antourage.weaverlib.other.replaceFragment
import com.antourage.weaverlib.screens.base.BaseFragment
import com.antourage.weaverlib.screens.list.VideoListFragment
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView

/**
 * Handles mostly orientation change andplayer controls
 */
abstract class StreamingFragment<VM : StreamingViewModel> : BaseFragment<VM>() {
    private lateinit var orientationEventListener: OrientationEventListener
    private var loader: AnimatedVectorDrawableCompat? = null
    private var isPortrait: Boolean? = null
    private var isLoaderShowing = false

    private lateinit var ivLoader: ImageView
    private lateinit var constraintLayoutParent: ConstraintLayout
    private lateinit var playerView: PlayerView
    private lateinit var ivScreenSize: ImageView
    protected lateinit var playerControls: PlayerControlView
    private lateinit var controllerHeaderLayout: ConstraintLayout


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        subscribeToObservers()
    }

    abstract fun subscribeToObservers()

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (isInPictureInPictureMode) {

        } else
        //    chatLayout.visibility = View.VISIBLE
        //playerView.useController = !isInPictureInPictureMode
            super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    override fun initUi(view: View?) {
        if (view != null) {
            ivLoader = view.findViewById(R.id.ivLoader)
            playerView = view.findViewById(R.id.playerView)
            constraintLayoutParent = view.findViewById(R.id.constraintLayoutParent)
            ivScreenSize = view.findViewById(R.id.ivScreenSize)
            playerControls = view.findViewById(R.id.controls)
            controllerHeaderLayout = view.findViewById(R.id.controllerHeaderLayout)
            controllerHeaderLayout.visibility = View.GONE
            playerView.setOnClickListener {
                handleControlsVisibility()
            }
            val ivClose: ImageView = view.findViewById(R.id.ivClose)
            ivClose.setOnClickListener {
                fragmentManager?.let { fragmentManager ->
                    if (fragmentManager.backStackEntryCount > 0)
                        fragmentManager.popBackStack()
                    else
                        replaceFragment(VideoListFragment.newInstance(), R.id.mainContent)
                }
            }
            initLoader()
            initOrientationHandling()
            setPlayerSizePortrait()
            ivScreenSize.setOnClickListener {
                val currentOrientation = activity?.resources?.configuration?.orientation
                if (!isRotationOn())
                    orientationEventListener.disable()
                if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    isPortrait = false
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                } else {
                    isPortrait = true
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                }
            }
        }
    }

    protected fun handleControlsVisibility() {
        if (playerControls.isVisible)
            playerControls.hide()
        else {
            onControlsVisible()
            playerControls.show()

        }
    }

    abstract fun onControlsVisible()

    //TODO 31/07/2019 get rid of this method and use ConstraintLayout aspect ratio
    protected fun setPlayerSizePortrait() {
        val params = playerView.layoutParams
        params.height = calculatePlayerHeight(activity!!).toInt()
        playerView.layoutParams = params
    }


    private fun initLoader() {
        if (context != null) {
            loader = AnimatedVectorDrawableCompat.create(context!!, R.drawable.loader_logo)
            ivLoader.setImageDrawable(loader)
        }
        showLoading()
    }

    protected fun showLoading() {
        loader?.let {
            if (!it.isRunning) {
                it.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        ivLoader.post { it.start() }
                    }
                })
                it.start()
                isLoaderShowing = true
                ivLoader.visibility = View.VISIBLE
                playerControls.hide()
            }
        }
    }

    protected fun hideLoading() {
        loader?.let {
            if (ivLoader.visibility == View.VISIBLE && it.isRunning) {
                ivLoader.visibility = View.GONE
                it.clearAnimationCallbacks()
                it.stop()
                isLoaderShowing = false
            }
        }
    }

    private val contentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            orientationEventListener.enable()
        }
    }

    private fun initOrientationHandling() {
        //check HR-92. It is still not fixed
        activity?.contentResolver?.registerContentObserver(
            Settings.System.getUriFor
                (Settings.System.ACCELEROMETER_ROTATION),
            true, contentObserver
        )
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val epsilon = 5
                val leftLandscape = 90
                val rightLandscape = 270
                val topPortrait = 0
                val bottomPortrait = 360
                if (isPortrait != null) {
                    if (!isPortrait!!) {
                        if (epsilonCheck(orientation, leftLandscape, epsilon) ||
                            epsilonCheck(orientation, rightLandscape, epsilon)
                        ) {

                            activity?.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                            Handler(Looper.getMainLooper()).postDelayed({ isPortrait = null }, 100)
                        }
                    } else if (epsilonCheck(orientation, topPortrait, epsilon) ||
                        epsilonCheck(orientation, bottomPortrait, epsilon)
                    ) {
                        activity?.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                        Handler(Looper.getMainLooper()).postDelayed({ isPortrait = null }, 100)
                    }
                } else {

                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
                }
            }
        }
        orientationEventListener.enable()
    }

    private fun isRotationOn(): Boolean {
        return Settings.System.getInt(
            activity?.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun epsilonCheck(a: Int, b: Int, epsilon: Int): Boolean {
        return a > b - epsilon && a < b + epsilon
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newOrientation = newConfig.orientation

        if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            constraintLayoutParent.setState(
                R.id.constraintStateLandscape,
                newConfig.screenWidthDp,
                newConfig.screenHeightDp
            )
            context?.let { context ->
                ivScreenSize.background =
                    ContextCompat.getDrawable(context, R.drawable.ic_fullscreen_exit)
            }
            controllerHeaderLayout.visibility = View.VISIBLE
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        } else if (newOrientation == Configuration.ORIENTATION_PORTRAIT) {
            constraintLayoutParent.setState(
                R.id.constraintStatePortrait,
                newConfig.screenWidthDp,
                newConfig.screenHeightDp
            )
            context?.let { context ->
                ivScreenSize.background =
                    ContextCompat.getDrawable(context, R.drawable.ic_full_screen)
            }
            controllerHeaderLayout.visibility = View.GONE
            setPlayerSizePortrait()
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        playerControls.hide()
        if (isLoaderShowing)
            initLoader()
    }
}