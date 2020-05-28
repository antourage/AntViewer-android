package com.antourage.weaverlib.screens.vod

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.antourage.weaverlib.R
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.*
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.ui.CustomDrawerLayout
import com.antourage.weaverlib.screens.base.chat.ChatFragment
import com.antourage.weaverlib.screens.weaver.PlayerFragment
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.DefaultTimeBar
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.fragment_player_vod_portrait.*
import kotlinx.android.synthetic.main.player_custom_controls_vod.*
import kotlinx.android.synthetic.main.player_header.*
import java.util.*
import kotlin.math.roundToInt

internal class VodPlayerFragment : ChatFragment<VideoViewModel>(),
    CustomDrawerLayout.DrawerDoubleTapListener {

    companion object {
        const val ARGS_STREAM = "args_stream"
        const val MIN_PROGRESS_UPDATE_MILLIS = 50L
        const val MAX_PROGRESS_UPDATE_MILLIS = 500L

        fun newInstance(stream: StreamResponse): VodPlayerFragment {
            val fragment = VodPlayerFragment()
            val bundle = Bundle()
            bundle.putParcelable(ARGS_STREAM, stream)
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun getLayoutId() = R.layout.fragment_player_vod_portrait
    private var skipForwardVDrawable: AnimatedVectorDrawableCompat? = null
    private var skipBackwardVDrawable: AnimatedVectorDrawableCompat? = null
    private val progressHandler = Handler()
    private val updateProgressAction = Runnable { updateProgressBar() }
    private var playerOnTouchListener : View.OnTouchListener? = null
    private var autoPlayCountDownTimer : CountDownTimer? = null

    private val streamStateObserver: Observer<Int> = Observer { state ->
        if (ivLoader != null)
            when (state) {
                Player.STATE_BUFFERING -> showLoading()
                Player.STATE_READY -> {
                    hideLoading()
                    vod_player_progress.max = viewModel.getVideoDuration()?.toInt() ?: 1
                    if (!playerControls.isVisible) {
                        progressHandler.postDelayed(
                            updateProgressAction,
                            MIN_PROGRESS_UPDATE_MILLIS
                        )
                    }
                    viewModel.currentVideo.value?.apply {
                        if (!broadcasterPicUrl.isNullOrEmpty()) {
                            Picasso.get().load(broadcasterPicUrl)
                                .placeholder(R.drawable.antourage_ic_default_user)
                                .error(R.drawable.antourage_ic_default_user)
                                .into(play_header_iv_photo)

                            Picasso.get().load(broadcasterPicUrl)
                                .placeholder(R.drawable.antourage_ic_default_user)
                                .error(R.drawable.antourage_ic_default_user)
                                .into(
                                    player_control_header
                                        .findViewById(R.id.play_header_iv_photo) as ImageView
                                )
                        }

                    }
                    ivFirstFrame.visibility = View.INVISIBLE
                    updatePrevNextVisibility()
                    if (viewModel.isPlaybackPaused()) {
                        playerControls.show()
                        viewModel.onVideoPausedOrStopped()
                    } else {
                        arguments?.getParcelable<StreamResponse>(ARGS_STREAM)
                            ?.id?.let { id -> viewModel.onVideoStarted() }
                    }
                }
                Player.STATE_IDLE -> hideLoading()
                Player.STATE_ENDED -> {
                    viewModel.removeStatisticsListeners()
                    viewModel.onVideoPausedOrStopped()
                    hideLoading()
                }
            }
    }

    private val viewersChangeObserver: Observer<Pair<Int,Int>> = Observer { viewInfo ->
        viewInfo?.apply {
            if (first == viewModel.streamId){
                txtNumberOfViewers.text = second.toString()
            }
        }
    }

    private val videoChangeObserver: Observer<StreamResponse> = Observer { video ->
        video?.apply {
            viewModel.getVideoDuration()?.let{duration ->
                if (duration > 0 ) { vod_player_progress.max = duration.toInt() }
            }
            tvStreamName.text = videoName
            tvBroadcastedBy.text = creatorNickname
            player_control_header.findViewById<TextView>(R.id.tvStreamName).text = videoName
            player_control_header.findViewById<TextView>(R.id.tvBroadcastedBy).text =
                creatorNickname
            txtNumberOfViewers.text = viewsCount.toString()
            context?.let { context ->
                updateWasLiveValueOnUI(startTime, duration)
                id?.let { UserCache.getInstance(context)?.saveVideoToSeen(it) }
            }
            if (!broadcasterPicUrl.isNullOrEmpty()) {
                Picasso.get().load(broadcasterPicUrl)
                    .placeholder(R.drawable.antourage_ic_default_user)
                    .error(R.drawable.antourage_ic_default_user)
                    .into(play_header_iv_photo)
                Picasso.get().load(broadcasterPicUrl)
                    .placeholder(R.drawable.antourage_ic_default_user)
                    .error(R.drawable.antourage_ic_default_user)
                    .into(
                        player_control_header
                            .findViewById(R.id.play_header_iv_photo) as ImageView
                    )
            }
            viewModel.seekToLastWatchingTime()
        }
    }

    private fun updatePrevNextVisibility() {
        vod_control_prev.visibility = if (viewModel.hasPrevTrack()) View.VISIBLE else View.INVISIBLE
        vod_control_next.visibility = if (viewModel.hasNextTrack()) View.VISIBLE else View.INVISIBLE
    }

    private val autoPlayStateObserver: Observer<VideoViewModel.AutoPlayState> = Observer { state ->

        when (state) {
            VideoViewModel.AutoPlayState.START_AUTO_PLAY -> {
                if (!viewModel.hasNextTrack()){ //STATE END OF LAST VIDEO IN PLAYLIST
                    viewModel.startReplayState()
                } else if (vod_next_auto_layout.visibility != View.VISIBLE) {
                    //STATE END OF NOT LAST VIDEO IN PLAYLIST
                    autoPlayCountDownTimer = object : CountDownTimer(5000, 20) {
                        override fun onTick(millisUntilFinished: Long) {
                            vod_progress_bar?.progress = millisUntilFinished.toInt()
                        }

                        override fun onFinish() {
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                && vod_next_auto_layout?.visibility == View.VISIBLE) {
                                controls.hide()
                                viewModel.nextVideoPlay()
                            } else {
                                viewModel.startReplayState()
                            }
                        }
                    }
                    vod_progress_bar?.progress = 5000
                    playerView.setOnTouchListener(null) //blocks from clicking
                    drawerLayout.touchListener = null
                    controls.showTimeoutMs = 4800 //not a 5000 due to hiding animation duration
                    autoPlayCountDownTimer?.start()
                    vod_buttons_layout.visibility = View.INVISIBLE
                    vod_next_auto_layout.visibility = View.VISIBLE
                    setProgressClickListenerForAutoPlay()
                }
            }
            VideoViewModel.AutoPlayState.START_REPLAY -> {
                drawerLayout.touchListener = this
                autoPlayCountDownTimer?.cancel()
                vod_play_pause_layout.visibility = View.INVISIBLE
                vod_buttons_layout.visibility = View.VISIBLE
                vod_next_auto_layout.visibility = View.INVISIBLE
                vod_rewind.visibility = View.VISIBLE
                playerView.setOnTouchListener(playerOnTouchListener)
                setProgressClickListenerForAutoPlay()
            }
            VideoViewModel.AutoPlayState.STOP_ALL_STATES -> {
                if (vod_rewind.visibility == View.VISIBLE ||
                    vod_next_auto_layout.visibility == View.VISIBLE ){
                    autoPlayCountDownTimer?.cancel()
                    vod_next_auto_layout.visibility = View.INVISIBLE
                    vod_rewind.visibility = View.INVISIBLE
                    vod_play_pause_layout.postDelayed(
                        {vod_play_pause_layout?.visibility = View.VISIBLE},500)
                    vod_buttons_layout.postDelayed(
                        {vod_buttons_layout?.visibility = View.VISIBLE},500)

                    playerView.setOnTouchListener(playerOnTouchListener)
                    controls.showTimeoutMs = 2000
                    drawerLayout.touchListener = this
                }
            }
            null -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setProgressClickListenerForAutoPlay(){
        controls.findViewById<DefaultTimeBar>(R.id.exo_progress).setOnTouchListener { v, _ ->
            viewModel.stopAutoPlayState()
            v.setOnTouchListener { _, _ -> false }
            return@setOnTouchListener false
        }
    }

    //should be used for showing/hiding joined users view
    private val chatStateObserver: Observer<Boolean> = Observer { isEmpty ->
        if (isEmpty == true) {
            if (orientation() == Configuration.ORIENTATION_PORTRAIT) {
                //improvements todo: start showing new users joined view
            }
        } else {
            //improvements todo: stop showing new users joined view
        }
    }
    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(VideoViewModel::class.java)
    }

    override fun subscribeToObservers() {
        super.subscribeToObservers()
        viewModel.getPlaybackState().observe(this.viewLifecycleOwner, streamStateObserver)
        viewModel.getCurrentVideo().observe(this.viewLifecycleOwner, videoChangeObserver)
        viewModel.getCurrentViewersLD().observe(this.viewLifecycleOwner, viewersChangeObserver)
        viewModel.getAutoPlayStateLD().observe(this.viewLifecycleOwner, autoPlayStateObserver)
        viewModel.getChatStateLiveData().observe(this.viewLifecycleOwner, chatStateObserver)
    }

    override fun initUi(view: View?) {
        super.initUi(view)
        initSkipAnimations()
        drawerLayout.touchListener = this
        constraintLayoutParent.loadLayoutDescription(R.xml.cl_states_player_vod)
        startPlayingStream()
        val streamResponse = arguments?.getParcelable<StreamResponse>(PlayerFragment.ARGS_STREAM)
        streamResponse?.apply {
            updateWasLiveValueOnUI(startTime, duration)
            viewModel.initUi(id, startTime)
            id?.let { viewModel.setStreamId(it) }

            thumbnailUrl?.let {
                Picasso.get()
                    .load(thumbnailUrl)
                    .networkPolicy(NetworkPolicy.OFFLINE)
                    .into(ivFirstFrame)
            }
        }
        initSkipControls()
        player_control_header.findViewById<ImageView>(R.id.play_header_iv_close)
            .setOnClickListener {
                onCloseClicked()
            }
        initControlsVisibilityListener()
        initPlayerClickListeners()
        initPortraitProgressListener()
    }

    private fun initPortraitProgressListener() {
        controls.setProgressUpdateListener { pos, _ ->
            vod_player_progress.progress = pos.toInt()
            vod_position.text = pos.formatTimeMillisToTimer()
            val duration = viewModel.getVideoDuration() ?: -1
            if (duration > 0){ vod_duration.text = duration.formatTimeMillisToTimer() }
        }
    }

    private fun updateProgressBar() {
        val duration = viewModel.getVideoDuration()?.toInt() ?: -1
        val position = viewModel.getVideoPosition()?.toInt() ?: -1
        if (duration > 0  && position > 0 && position <= duration) {
            vod_player_progress?.max = duration
            vod_player_progress?.progress = position
        }
        progressHandler.removeCallbacks(updateProgressAction)
        progressHandler.postDelayed(
            updateProgressAction,
            if (!viewModel.isPlaybackPaused()) MIN_PROGRESS_UPDATE_MILLIS else MAX_PROGRESS_UPDATE_MILLIS
        )
    }

    private fun initPlayerClickListeners() {
        vod_control_prev.setOnClickListener { viewModel.prevVideoPlay() }
        vod_control_next.setOnClickListener { viewModel.nextVideoPlay() }
        vod_rewind.setOnClickListener {
            controls.hide()
            viewModel.rewindVideoPlay()
        }
        vod_auto_next_cancel.setOnClickListener { viewModel.startReplayState() }
        vod_controls_auto_next.setOnClickListener {
            controls.hide()
            autoPlayCountDownTimer?.cancel()
            viewModel.nextVideoPlay()
        }
        updatePrevNextVisibility()
    }

    private fun initControlsVisibilityListener() {
        playerControls.setVisibilityListener { visibility ->
            if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
                if (visibility == View.VISIBLE) {
                    txtNumberOfViewers.marginDp(12f, 62f)
                } else {
                    txtNumberOfViewers.marginDp(12f, 12f)
                }
            } else if (visibility == View.VISIBLE) {
                //stops progress bar updates
                progressHandler.removeCallbacks(updateProgressAction)

            } else if (visibility != View.VISIBLE) {
                //starts progress bar updates when controls are invisible
                progressHandler.postDelayed(updateProgressAction, MIN_PROGRESS_UPDATE_MILLIS)
            }
        }
    }

    private fun initSkipControls() {
        playerOnTouchListener = object : View.OnTouchListener {
            private val gestureDetector =
                GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                        toggleControlsVisibility()
                        return super.onSingleTapConfirmed(e)
                    }

                    override fun onDoubleTap(e: MotionEvent?): Boolean {
                        controls.hide()
                        e?.x?.let {
                            if (it > playerView.width / 2) {
                                handleSkipForward()
                            } else {
                                handleSkipBackward()
                            }
                        }
                        return super.onDoubleTap(e)
                    }
                })

            override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
                gestureDetector.onTouchEvent(p1)
                return true
            }
        }

        playerView.setOnTouchListener(playerOnTouchListener)
        drawerLayout.doubleTapListener = this
    }

    private fun handleSkipForward() {
        dimNextPrevButtons()
        showSkipAnim(skipForwardVDrawable, skipForward, skipForwardTV)
        viewModel.skipForward()
    }

    private fun handleSkipBackward() {
        dimNextPrevButtons()
        showSkipAnim(skipBackwardVDrawable, skipBackward, skipBackwardTV)
        viewModel.skipBackward()
    }

    private fun dimNextPrevButtons() {
        vod_control_next.alpha = 0.3f
        vod_control_prev.alpha = 0.3f
    }

    private fun brightenNextPrevButtons() {
        vod_control_next.alpha = 1.0f
        vod_control_prev.alpha = 1.0f
        controls?.hide()
    }

    private fun initSkipAnimations() {
        skipForwardVDrawable =
            context?.let {
                AnimatedVectorDrawableCompat.create(
                    it,
                    R.drawable.antourage_skip_forward
                )
            }
        skipBackwardVDrawable =
            context?.let { AnimatedVectorDrawableCompat.create(it, R.drawable.antourage_skip_back) }
        skipForward.setImageDrawable(skipForwardVDrawable)
        skipBackward.setImageDrawable(skipBackwardVDrawable)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        if (viewModel.isPlaybackPaused()) {
            playerControls.show()
        } else if (playerControls.visibility == View.INVISIBLE && orientation() == Configuration.ORIENTATION_PORTRAIT) {
            progressHandler.postDelayed(updateProgressAction, MIN_PROGRESS_UPDATE_MILLIS)
        }
        playerView.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
        progressHandler.removeCallbacks(updateProgressAction)
        playerView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releasePlayer()
    }

    override fun onControlsVisible() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newOrientation = newConfig.orientation
        chatUiToLandscape(newOrientation == Configuration.ORIENTATION_LANDSCAPE)

        when (newOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                //improvements todo: stop showing new users joined view
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                if (viewModel.getChatStateLiveData().value == true) {
                    //improvements todo: start showing new users joined view
                }
            }
        }

        initSkipControls()
        viewModel.getPlaybackState().reObserve(this.viewLifecycleOwner, streamStateObserver)
    }

    override fun onDrawerDoubleClick() {
        controls.hide()
        handleSkipBackward()
    }
    //region chatUI helper func

    private fun chatUiToLandscape(landscape: Boolean) {
        changeControlsView(landscape)
    }

    /**
     * Used to change control buttons size on landscape/portrait.
     * I couldn't use simple dimensions change due to specific orientation handling in project.
     */
    private fun changeButtonsSize(isEnlarge: Boolean) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(vod_buttons_layout)
        updateIconSize(
            R.id.vod_rewind, constraintSet,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )
        updateIconSize(
            R.id.vod_control_next, constraintSet,
            if (isEnlarge) R.dimen.large_next_prev_size else R.dimen.small_next_prev_size
        )
        updateIconSize(
            R.id.vod_control_prev, constraintSet,
            if (isEnlarge) R.dimen.large_next_prev_size else R.dimen.small_next_prev_size
        )
        constraintSet.applyTo(vod_buttons_layout)

        val constraintSet2 = ConstraintSet()
        constraintSet2.clone(vod_play_pause_layout)
        updateIconSize(
            R.id.exo_play, constraintSet2,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )
        updateIconSize(
            R.id.exo_pause, constraintSet2,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )
        constraintSet2.applyTo(vod_play_pause_layout)

        val constraintSet3 = ConstraintSet()
        constraintSet3.clone(vod_next_auto_layout)
        updateIconSize(
            R.id.vod_controls_auto_next, constraintSet3,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )
        constraintSet3.applyTo(vod_next_auto_layout)
    }

    private fun updateIconSize(iconId: Int, constraintSet: ConstraintSet, dimenId: Int) {
        val iconSize = resources.getDimension(dimenId).toInt()
        constraintSet.constrainWidth(iconId, iconSize)
        constraintSet.constrainHeight(iconId, iconSize)
    }

    private fun orientation() = resources.configuration.orientation

    private fun startPlayingStream() {
        val streamResponse = arguments?.getParcelable<StreamResponse>(ARGS_STREAM)
        streamResponse?.apply {
            id?.let { viewModel.setCurrentPlayerPosition(it) }
            playerView.player = videoURL?.let { viewModel.getExoPlayer(it) }
        }
        playerControls.player = playerView.player
    }
    //endregion

    private fun changeControlsView(isLandscape: Boolean) {
        context?.apply {
            rvMessages.setPadding(
                dp2px(this, 0f).roundToInt(),
                dp2px(this, 0f).roundToInt(),
                dp2px(this, 0f).roundToInt(),
                dp2px(this, if (isLandscape) 12f else 0f).roundToInt()
            )
        }
        val progressMarginPx = if (isLandscape) {
            resources.getDimension(R.dimen.ant_margin_seekbar_landscape).toInt()
        } else {
            resources.getDimension(R.dimen.ant_margin_seekbar_portrait).toInt()
        }
        controls.findViewById<DefaultTimeBar>(R.id.exo_progress).setMargins(
            progressMarginPx, 0, progressMarginPx, 0
        )
        vod_shadow.marginDp(bottom = if (isLandscape) 0f else 15f)
        changeButtonsSize(isEnlarge = isLandscape)
    }

    private fun showSkipAnim(vDrawable: AnimatedVectorDrawableCompat?, iv: View?, descView: TextView?) {
        vDrawable?.apply {
            if (!isRunning) {
                registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                    override fun onAnimationEnd(drawable: Drawable?) {
                        descView?.visibility = View.INVISIBLE
                        iv?.visibility = View.INVISIBLE
                        brightenNextPrevButtons()
                        clearAnimationCallbacks()
                    }
                })
                start()
                iv?.visibility = View.VISIBLE
                descView?.visibility = View.VISIBLE
            }
        }
    }

    override fun onMinuteChanged() {
        viewModel.currentVideo.value?.let {
            if (it.startTime != null && it.duration != null) {
                updateWasLiveValueOnUI(it.startTime, it.duration)
            }
        }
    }

    private fun updateWasLiveValueOnUI(startTime: String?, duration: String?) {
        context?.apply {
            val formattedStartTime =
                duration?.parseToMills()?.plus((startTime?.parseToDate()?.time ?: 0))?.let {
                    Date(it).parseToDisplayAgoTimeLong(this)
                }
            play_header_tv_ago.text = formattedStartTime
            play_header_tv_ago.gone(formattedStartTime.isNullOrEmpty())
            val tvAgoLandscape = player_control_header
                .findViewById<TextView>(R.id.play_header_tv_ago)
            tvAgoLandscape.text = formattedStartTime
            tvAgoLandscape.gone(formattedStartTime.isNullOrEmpty())
        }
    }

    //not in use for this fragment
    override fun showMessageInputVisibleIfRequired(shouldShow: Boolean) {}
}
