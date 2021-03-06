package com.antourage.weaverlib.screens.weaver

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import android.view.animation.Animation
import android.view.animation.Transformation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.R
import com.antourage.weaverlib.other.*
import com.antourage.weaverlib.other.models.Message
import com.antourage.weaverlib.other.models.MessageType
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.models.User
import com.antourage.weaverlib.other.ui.keyboard.KeyboardEventListener
import com.antourage.weaverlib.screens.base.AntourageActivity
import com.antourage.weaverlib.screens.base.chat.ChatFragment
import com.antourage.weaverlib.screens.poll.PollDetailsFragment
import com.google.android.exoplayer2.Player
import com.google.firebase.Timestamp
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.fragment_player_live_video_portrait.*
import kotlinx.android.synthetic.main.player_custom_controls_live_video.*
import kotlinx.android.synthetic.main.player_header.*

/**
 * Be careful not to create multiple instances of player
 * That way the sound will continue to go on after user exits fragment
 * Especially check method onNetworkGained
 */
internal class PlayerFragment : ChatFragment<PlayerViewModel>() {

    private var wasDrawerClosed = false
    private var userDialog: Dialog? = null
    private var shouldDisconnectSocket: Boolean = true

    companion object {
        const val ARGS_STREAM = "args_stream"
        const val ARGS_USER_ID = "args_user_id"
        const val ARGS_START_CHAT = "args_required_to_start_chat"

        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100

        fun newInstance(
            stream: StreamResponse,
            userId: Int,
            isRequiredToStartChat: Boolean = false
        ): PlayerFragment {
            val fragment = PlayerFragment()
            val bundle = Bundle()
            bundle.putParcelable(ARGS_STREAM, stream)
            bundle.putInt(ARGS_USER_ID, userId)
            bundle.putBoolean(ARGS_START_CHAT, isRequiredToStartChat)
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun getLayoutId() = R.layout.fragment_player_live_video_portrait

    //region Observers
    private val streamStateObserver: Observer<Int> = Observer { state ->
        if (ivLoader != null)
            when (state) {
                Player.STATE_BUFFERING -> showLoading()
                Player.STATE_READY -> {
                    hideLoading()
                    if (viewModel.isPlaybackPaused()) {
                        playerControls.show()
                    }
                    ivFirstFrame.visibility = View.INVISIBLE
                }
                Player.STATE_IDLE -> {
                    if (!viewModel.wasStreamInitialized)
                        viewModel.onNetworkGained()
                    else
                        hideLoading()
                }
                Player.STATE_ENDED -> {
                    viewModel.removeStatisticsListeners()
                    hideLoading()
                }
            }
    }

    private val chatStateObserver: Observer<ChatStatus> = Observer { state ->
        if (state != null)
            when (state) {
                is ChatStatus.ChatTurnedOff -> {
                    enableMessageInput(false, ivThanksForWatching?.visibility == View.VISIBLE)
                    //improvements todo: start showing new users joined view
                    // if (orientation() != Configuration.ORIENTATION_LANDSCAPE) else -> hide
                }
                is ChatStatus.ChatMessages -> {
                    if (ivThanksForWatching?.visibility != View.VISIBLE) enableChatUI()
                    //improvements todo: stop showing new users joined view
                }
                is ChatStatus.ChatNoMessages -> {
                    if (ivThanksForWatching?.visibility != View.VISIBLE) enableChatUI()
                    //improvements todo: start showing new users joined view
                    // if (orientation() != Configuration.ORIENTATION_LANDSCAPE) else -> hide
                }
            }
    }

    private val userInfoObserver: Observer<User> = Observer { user ->
        user?.apply {
            if (!viewModel.noDisplayNameSet()) {
                etMessage.isFocusable = true
                etMessage.isFocusableInTouchMode = true
            }
        }
        startUserInputIfRequesting()
    }

    private val currentStreamInfoObserver: Observer<Boolean> = Observer { isStreamStillLive ->
        if (!isStreamStillLive) {
            showEndStreamUI()
        }
    }

    private val currentStreamViewersObserver: Observer<Long> = Observer { currentViewersCount ->
        updateViewersCountUI(currentViewersCount)
    }

    private fun updateViewersCountUI(currentViewersCount: Long?) {
        currentViewersCount?.let {
            txtNumberOfViewers.text = it.formatQuantity()
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun showEndStreamUI() {
        ivFirstFrame?.visibility = View.VISIBLE
        ivThanksForWatching?.visibility = View.VISIBLE
        txtLabelLive.visibility = View.GONE
        //hides controls appearance
        controls.hide()
        live_control_timer.visibility = View.INVISIBLE
        live_buttons_layout.visibility = View.INVISIBLE

        txtNumberOfViewers.marginDp(6f, 6f)
        tv_live_end_time.text = viewModel.getDuration()?.formatTimeMillisToTimer() ?: "0:00"
        tv_live_end_time.visibility = View.VISIBLE
        enableMessageInput(false, disableButtons = true)
        if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
            drawerLayout.closeDrawer(navView)
            drawerLayout.visibility = View.INVISIBLE
        } else {
            drawerLayout.openDrawer(navView)
            drawerLayout.visibility = View.VISIBLE
        }
    }

    private fun unlockDrawer() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun lockDrawer() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private val pollStateObserver: Observer<PollStatus> = Observer { state ->
        if (state != null) {
            when (state) {
                is PollStatus.NoPoll -> {
                    unlockDrawer()
                    hidePollStatusLayout()
                    if (bottomLayout.visibility == View.VISIBLE) bottomLayout.visibility =
                        View.INVISIBLE
                }
                is PollStatus.ActivePoll -> {
                    unlockDrawer()
                    poll_name?.text = state.poll.question
                    showPollStatusLayout(state.poll.id)
                }
                is PollStatus.ActivePollDismissed -> {
                    unlockDrawer()
                    if (bottomLayout.visibility == View.INVISIBLE) showPollStatusLayout()
                }
                is PollStatus.PollDetails -> {
                    wasDrawerClosed = !drawerLayout.isOpened()
                    (activity as AntourageActivity).hideSoftKeyboard()
                    hidePollStatusLayout()
                    bottomLayout.visibility = View.VISIBLE
                    if (orientation() == Configuration.ORIENTATION_PORTRAIT) {
                        removeMessageInput()
                    } else {
                        lockDrawer()
                        if (drawerLayout.isDrawerOpen(navView)) {
                            drawerLayout.closeDrawer(navView)
                        }
                    }

                    val videoId = arguments?.getParcelable<StreamResponse>(ARGS_STREAM)?.id
                    val userId = arguments?.getInt(ARGS_USER_ID)
                    if (videoId != null && userId != null) {
                        replaceChildFragment(
                            PollDetailsFragment.newInstance(
                                videoId,
                                state.pollId,
                                userId,
                                viewModel.getBanner()
                            ), R.id.bottomLayout, true
                        )
                    }
                    childFragmentManager.addOnBackStackChangedListener {
                        if ((childFragmentManager.findFragmentById(R.id.bottomLayout) !is PollDetailsFragment)) {
                            bottomLayout.visibility = View.INVISIBLE
                            if (orientation() == Configuration.ORIENTATION_PORTRAIT) {
                                ll_wrapper.visibility = View.VISIBLE
                            }
                            if (viewModel.currentPoll != null) {
                                showPollStatusLayout()
                                viewModel.startNewPollCountdown()
                            }
                            if (!wasDrawerClosed)
                                drawerLayout.openDrawer(navView)
                        }
                    }
                }
            }
        }
    }
//endregion

    private val onBtnSendClicked = View.OnClickListener {
        val message = Message(
            viewModel.getUser()?.imageUrl ?: "",
            null,
            viewModel.getUser()?.displayName,
            etMessage.text.toString(),
            MessageType.USER,
            Timestamp.now()
        )
        /**
        User id fot firebase synchronized with back end user id with messages;
         */
        message.userID = viewModel.getUser()?.id.toString()
        arguments?.getParcelable<StreamResponse>(ARGS_STREAM)?.id?.let { id ->
            viewModel.addMessage(message, id)
        }
        etMessage.setText("")
        if (btnUserSettings.visibility == View.VISIBLE) {
            btnSend.visibility = View.INVISIBLE
        }
        rvMessages?.apply {
            adapter?.itemCount?.minus(0)?.let { adapterPosition ->
                post {
                    Handler().postDelayed({
                        layoutManager?.scrollToPosition(adapterPosition)
                    }, 300)
                }
            }
        }
    }

    private val onUserSettingsClicked = View.OnClickListener {
        showUserNameDialog()
        if (!etMessage.isFocused)
            hideKeyboard()
    }

    private val onMessageETClicked = View.OnClickListener {
        checkIfRequireDisplayNameSetting()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(PlayerViewModel::class.java)
        activity?.onBackPressedDispatcher?.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                shouldDisconnectSocket = false
                isEnabled = false
                activity?.onBackPressed()
            }
        })
    }

    override fun subscribeToObservers() {
        super.subscribeToObservers()
        viewModel.getPlaybackState().observe(this.viewLifecycleOwner, streamStateObserver)
        viewModel.getPollStatusLiveData().observe(this.viewLifecycleOwner, pollStateObserver)
        viewModel.getChatStatusLiveData().observe(this.viewLifecycleOwner, chatStateObserver)
        viewModel.getUserInfoLiveData().observe(this.viewLifecycleOwner, userInfoObserver)
        viewModel.getCurrentLiveStreamInfo()
            .observe(this.viewLifecycleOwner, currentStreamInfoObserver)
        viewModel.currentStreamViewsLiveData
            .observe(this.viewLifecycleOwner, currentStreamViewersObserver)
    }

    override fun initUi(view: View?) {
        super.initUi(view)
        setupUIForHidingKeyboardOnOutsideTouch(constraintLayoutParent)
        initUser()
        hidePollStatusLayout()
        constraintLayoutParent.loadLayoutDescription(R.xml.cl_states_player_live_video)
        startPlayingStream()

        /** needed to force live player to force reset playing is there was bad connectivity on broadcaster*/
        playerControls.initOnPlayClickListener {
            if (viewModel.shouldForceResetLiveStream) {
                playerControls.showTimeoutMs = 2000
                viewModel.forceResetPlaying()
                if(getSnackBarErrorText() == getString(R.string.ant_live_error)){
                    hideErrorSnackBar()
                }
            }
        }

        playerView.setOnTouchListener(object : View.OnTouchListener {
            private val gestureDetector =
                GestureDetectorCompat(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                            toggleControlsVisibility()
                            return super.onSingleTapConfirmed(e)
                        }

                        override fun onFling(
                            e1: MotionEvent,
                            e2: MotionEvent,
                            velocityX: Float,
                            velocityY: Float
                        ): Boolean {
                            var result = false
                            try {
                                val diffY = e2.y - e1.y
                                if (kotlin.math.abs(diffY) > SWIPE_THRESHOLD && kotlin.math.abs(
                                        velocityY
                                    ) > SWIPE_VELOCITY_THRESHOLD
                                ) {
                                    result = if (diffY > 0) {
                                        if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
                                            onFullScreenImgClicked()
                                        } else {
                                            onCloseClicked()
                                        }
                                        true
                                    } else {
                                        toggleControlsVisibility()
                                        false
                                    }
                                } else {
                                    toggleControlsVisibility()
                                    result = false
                                }
                            } catch (exception: Exception) {
                                exception.printStackTrace()
                            }
                            return result
                        }
                    })

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(p0: View?, p1: MotionEvent?): Boolean {
                gestureDetector.onTouchEvent(p1)
                return true
            }
        })

        initStreamInfo(arguments?.getParcelable(ARGS_STREAM))
        val streamResponse = arguments?.getParcelable<StreamResponse>(ARGS_STREAM)
        streamResponse?.apply {
            thumbnailUrl?.let {
                if (it.isNotEmpty() && it.isNotBlank()) {
                    Picasso.get()
                        .load(thumbnailUrl)
                        .networkPolicy(NetworkPolicy.OFFLINE)
                        .into(ivFirstFrame)
                }
            }
        }
        initClickListeners()
        initKeyboardListener()
        initControlsVisibilityListener()
        initProgressListener()
    }

    private fun startUserInputIfRequesting() {
        val isStartChat = arguments?.getBoolean(ARGS_START_CHAT) ?: false
        if (!isStartChat) {
            return
        } else if (viewModel.noDisplayNameSet()) {
            showUserNameDialog()
        } else {
            etMessage.isFocusable = true
            etMessage.isFocusableInTouchMode = true
            showKeyboard(etMessage)
        }
    }

    private fun initProgressListener() {
        controls.setProgressUpdateListener { pos, _ ->
            live_control_timer.text = pos.formatTimeMillisToTimer()
        }
    }

    private fun initControlsVisibilityListener() {
        playerControls.addVisibilityListener { visibility ->
            if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
                if (visibility == View.VISIBLE) {
                    txtNumberOfViewers.marginDp(4f, 62f)
                    txtLabelLive.marginDp(12f, 62f)
                } else {
                    txtLabelLive.marginDp(12f, 12f)
                    txtNumberOfViewers.marginDp(4f, 12f)
                }
            }
        }
    }

    //works only in landscape
    private fun initKeyboardListener() {
        KeyboardEventListener(activity as AppCompatActivity) { isOpen ->
            try {
                if (orientation() == Configuration.ORIENTATION_LANDSCAPE) {
                    if (isOpen) {
                        if (viewModel.getPollStatusLiveData().value is PollStatus.ActivePollDismissed) {
                            hidePollStatusLayout()
                        }
                        hideFullScreenIcon()
                    } else {
                        if (viewModel.getPollStatusLiveData().value is PollStatus.ActivePollDismissed) {
                            showPollStatusLayout()
                        }
                        showFullScreenIcon()
                    }
                }
            } catch (ex: IllegalStateException) {
                Log.d("Keyboard", "Fragment not attached to a context.")
            }
        }
    }

    private fun activateCommentInputBar(shouldActivate: Boolean) {
        //btnShare.visibility = if (shouldActivate) View.GONE else View.VISIBLE
        btnUserSettings.visibility = if (shouldActivate) View.GONE else View.VISIBLE
        btnSend.visibility = when {
            shouldActivate -> View.VISIBLE
            etMessage.text.isNullOrBlank() -> View.INVISIBLE
            else -> View.VISIBLE
        }
        if (!shouldActivate) {
            etMessage.clearFocus()
        } else {
            etMessage.requestFocus()
        }
        changeInputBarConstraints(shouldActivate)
        if (orientation() == Configuration.ORIENTATION_PORTRAIT) {
            animatePlayerHeader(!shouldActivate)
        }
    }

    private fun animatePlayerHeader(shouldShow: Boolean) {
        val set = ConstraintSet()
        set.clone(constraintLayoutParent)
        if (shouldShow) {
            set.connect(
                R.id.playerView, ConstraintSet.TOP, R.id.live_header_layout,
                ConstraintSet.BOTTOM, 0
            )
        } else {
            set.connect(
                R.id.playerView, ConstraintSet.TOP, R.id.constraintLayoutParent,
                ConstraintSet.TOP, 0
            )
        }
        set.applyTo(constraintLayoutParent)
    }

    private fun changeInputBarConstraints(isKeyboardOpened: Boolean) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(ll_wrapper)
        if (isKeyboardOpened) {
            constraintSet.connect(
                R.id.etMessage, ConstraintSet.START, R.id.ll_wrapper,
                ConstraintSet.START, dp2px(requireContext(), 12f).toInt()
            )
        } else {
            constraintSet.connect(
                R.id.etMessage, ConstraintSet.START, R.id.btnUserSettings,
                ConstraintSet.END, dp2px(requireContext(), 12f).toInt()
            )
        }
        constraintSet.applyTo(ll_wrapper)
    }

    override fun onControlsVisible() {}

    private fun onPollDetailsClicked() {
        viewModel.seePollDetails()
    }

    private fun startPlayingStream() {
        playerView.player =
            arguments?.getParcelable<StreamResponse>(ARGS_STREAM)?.hlsUrl?.get(
                0
            )?.let {
                viewModel.getExoPlayer(
                    it
                )
            }
        playerControls.player = playerView.player
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        shouldDisconnectSocket = true
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
        viewModel.onPauseSocket(shouldDisconnectSocket)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releasePlayer()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onShowKeyboard(keyboardHeight: Int) {
        super.onShowKeyboard(keyboardHeight)
        if (userDialog == null) {
            activateCommentInputBar(true)
            hideErrorSnackBar()
        }
    }

    override fun onHideKeyboard(keyboardHeight: Int) {
        super.onHideKeyboard(keyboardHeight)
        activateCommentInputBar(false)
        if (!Global.networkAvailable) {
            showErrorSnackBar(getString(R.string.ant_no_connection), false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (viewModel.getCurrentLiveStreamInfo().value == false) {
            showEndStreamUI()
        } else {
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    /* ll_wrapper.background =
                         context?.let {
                             ContextCompat.getDrawable(
                                 it,
                                 R.drawable.antourage_rounded_semitransparent_bg
                             )
                         }*/
                    if (userDialog != null) {
                        val input =
                            userDialog?.findViewById<EditText>(R.id.etDisplayName)?.text.toString()
                        val isKeyboardVisible = keyboardIsVisible
                        userDialog?.dismiss()
                        showUserNameDialog(input, isKeyboardVisible)
                    } else {
                        if (keyboardIsVisible) {
                            etMessage.requestFocus()
                        }
                    }
                    changeButtonsSize(isEnlarge = true)
                }
                Configuration.ORIENTATION_PORTRAIT -> {
                    if (userDialog != null) {
                        val input =
                            userDialog?.findViewById<EditText>(R.id.etDisplayName)?.text.toString()
                        val isKeyboardVisible = keyboardIsVisible
                        userDialog?.dismiss()
                        showUserNameDialog(input, isKeyboardVisible)
                    } else {
                        if (keyboardIsVisible) {
                            etMessage.requestFocus()
                        }
                    }
                    /*context?.let { ContextCompat.getColor(it, R.color.ant_bg_color) }?.let {
                        ll_wrapper.setBackgroundColor(it)
                    }*/
                    changeButtonsSize(isEnlarge = false)
                }
            }
        }

        if (viewModel.getPollStatusLiveData().value is PollStatus.ActivePoll) {
            polls_motion_layout?.transitionToStart()
            viewModel.markActivePollDismissed()
        }
        viewModel.getChatStatusLiveData().reObserve(this.viewLifecycleOwner, chatStateObserver)
        viewModel.getPlaybackState().reObserve(this.viewLifecycleOwner, streamStateObserver)
        viewModel.getPollStatusLiveData().reObserve(this.viewLifecycleOwner, pollStateObserver)
        showFullScreenIcon()
    }

    /**
     * Used to change control buttons size on landscape/portrait.
     * I couldn't use simple dimensions change due to specific orientation handling in project.
     */
    private fun changeButtonsSize(isEnlarge: Boolean) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(live_buttons_layout)
        updateIconSize(
            R.id.exo_play, constraintSet,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )
        updateIconSize(
            R.id.exo_pause, constraintSet,
            if (isEnlarge) R.dimen.large_play_pause_size else R.dimen.small_play_pause_size
        )

        constraintSet.applyTo(live_buttons_layout)
    }

    private fun updateIconSize(iconId: Int, constraintSet: ConstraintSet, dimenId: Int) {
        val iconSize = resources.getDimension(dimenId).toInt()
        constraintSet.constrainWidth(iconId, iconSize)
        constraintSet.constrainHeight(iconId, iconSize)
    }

    //region chatUI helper func
    private fun enableMessageInput(enable: Boolean, disableButtons: Boolean = false) {
        etMessage.isEnabled = enable
        if (enable) {
            //used to fix disabling buttons in landscape and not enabling in portrait
            //btnShare?.isEnabled = enable //temporary
            btnUserSettings?.isEnabled = enable
        }
        //btnShare?.isEnabled = !disableButtons
        btnUserSettings?.isEnabled = !disableButtons

        if (!enable) etMessage.setText("")
        etMessage.hint =
            getString(if (enable) R.string.ant_hint_chat else R.string.ant_hint_disabled)
    }

    private fun showMessageInput() {
        ll_wrapper.visibility = View.VISIBLE
    }

    private fun removeMessageInput() {
        ll_wrapper?.visibility = View.GONE
    }

    private fun orientation() = this.resources.configuration.orientation

    private fun enableChatUI() {
        enableMessageInput(true)
        if (bottomLayout.visibility != View.VISIBLE && drawerLayout.isOpened()) {
            showMessageInput()
        }
    }
    //end of region

    //region poll UI helper func
    private fun showPollStatusLayout(pollIdForAnimation: String? = null) {
        polls_motion_layout.visibility = View.VISIBLE
        if (pollIdForAnimation != null) {
            polls_motion_layout.transitionToEnd()
            //callback to collapse extended poll layout in 6 sec
            Handler().postDelayed(
                {
                    val pollStatus = viewModel.getPollStatusLiveData().value
                    if (polls_motion_layout != null && pollStatus is PollStatus.ActivePoll) {
                        if (pollStatus.poll.id == pollIdForAnimation) {
                            polls_motion_layout?.transitionToStart()
                            viewModel.markActivePollDismissed()
                        }
                    }
                }, PlayerViewModel.CLOSE_EXPANDED_POLL_DELAY_MS
            )
        }
    }

    private fun hidePollStatusLayout() {
        polls_motion_layout.visibility = View.INVISIBLE
        if (polls_motion_layout?.currentState == polls_motion_layout.endState) {
            //to be sure, that it's in proper state
            polls_motion_layout?.postDelayed({ polls_motion_layout?.progress = 0.0f }, 500)
        }
    }
    //end of region

    private fun initStreamInfo(streamResponse: StreamResponse?) {
        streamResponse?.apply {
            viewModel.initUi(id)
            id?.let { viewModel.setStreamId(it) }
            tvStreamName.text = streamTitle
            tvBroadcastedBy.text = creatorNickname
            player_control_header.findViewById<TextView>(R.id.tvStreamName).text = streamTitle
            player_control_header.findViewById<TextView>(R.id.tvBroadcastedBy).text =
                creatorNickname
            if (!broadcasterPicUrl.isNullOrEmpty()) {
                Picasso.get().load(broadcasterPicUrl)
                    .placeholder(R.drawable.antourage_ic_default_user)
                    .error(R.drawable.antourage_ic_default_user)
                    .into(play_header_iv_photo)
                Picasso.get().load(broadcasterPicUrl)
                    .placeholder(R.drawable.antourage_ic_default_user)
                    .error(R.drawable.antourage_ic_default_user)
                    .into(player_control_header.findViewById<ImageView>(R.id.play_header_iv_photo))
            }
            txtNumberOfViewers.text = viewersCount?.formatQuantity() ?: "0"
            setWasLiveText(context?.let { startTime?.parseDateLong(it) })
        }
    }

    private fun initClickListeners() {
        btnSend.setOnClickListener(onBtnSendClicked)
        etMessage.setOnClickListener(onMessageETClicked)
        etMessage?.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                wasEditTextFocused = true
                v.postDelayed({ wasEditTextFocused = false }, 500)
            } else {
                wasEditTextFocused = true
                controls.hide()
            }
        }
        etMessage.setOnLongClickListener {
            //added to make impossible to paste text without opening keyboard
            showKeyboard(etMessage)
            return@setOnLongClickListener false
        }
        btnUserSettings.setOnClickListener(onUserSettingsClicked)

        poll_bg.setOnClickListener {
            playerControls.hide()
            onPollDetailsClicked()
        }

        player_control_header.findViewById<ImageView>(R.id.play_header_iv_close)
            .setOnClickListener {
                it.isEnabled = false
                onCloseClicked()
                shouldDisconnectSocket = false
            }
    }

    private fun showFullScreenIcon() {
        ivScreenSize.visibility = View.VISIBLE
    }

    private fun hideFullScreenIcon() {
        ivScreenSize.visibility = View.GONE
    }

    override fun onMinuteChanged() {
        setWasLiveText(context?.let {
            arguments?.getParcelable<StreamResponse>(ARGS_STREAM)
                ?.startTime?.parseDateLong(it)
        })
    }

    private fun setWasLiveText(text: String?) {
        val tvAgoLandscape = player_control_header
            .findViewById<TextView>(R.id.play_header_tv_ago)
        if (text != null && text.isNotEmpty()) {
            tvAgoLandscape.text = text
            play_header_tv_ago.text = text
        }
        play_header_tv_ago.gone(text.isNullOrBlank())
        tvAgoLandscape.gone(text.isNullOrBlank())
    }

    /**
     * Shows dialog for choosing userName.
     * Both parameters used in case of configuration change in order to save state and
     * restore keyboard(as it hides on dialog dismiss).
     * @inputName: text inputted by user before configuration change. Will be set to ET.
     * @showKeyboard: indicates, whether we should show keyboard in recreated dialog.
     * Basic dialog can be shown without parameters.
     * On dialog shown/closed updates Player fragment field @userDialog so it can be used to check
     * whether dialog is shown on configuration change.
     */
    @SuppressLint("InflateParams") //for dialog can be null
    fun showUserNameDialog(inputName: String? = null, showKeyboard: Boolean = false) {
        with(Dialog(requireContext())) {
            val currentDialogOrientation = resources.configuration.orientation
            setContentView(
                layoutInflater.inflate(
                    if (currentDialogOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        R.layout.dialog_chat_name
                    } else {
                        R.layout.dialog_chat_name_land
                    }, null
                )
            )

            val initUserName = inputName ?: (viewModel.getUser()?.displayName ?: "")

            val eText = findViewById<EditText>(R.id.etDisplayName)
            val saveButton = findViewById<TextView>(R.id.btnConfirm)
            findViewById<TextView>(R.id.btnCancel).setOnClickListener {
                eText.setText(viewModel.getUser()?.displayName)
                dismiss()
            }
            findViewById<ImageButton>(R.id.d_name_close).setOnClickListener {
                eText.setText(viewModel.getUser()?.displayName)
                dismiss()
            }

            with(eText) {
                if (initUserName.isNotBlank()) {
                    setText(initUserName)
                } else {
                    isFocusableInTouchMode = true
                    isFocusable = true
                }

                afterTextChanged { saveButton.isEnabled = validateNewUserName(it) }
                setOnFocusChangeListener { _, _ ->
                    saveButton.isEnabled = validateNewUserName(text.toString())
                }
            }

            saveButton.setOnClickListener {
                viewModel.changeUserDisplayName(eText.text.toString())
                hideKeyboard()
                dismiss()
            }

            setOnDismissListener {
                if (currentDialogOrientation == resources.configuration.orientation) {
                    userDialog = null
                }
            }

            show()
            val inset = resources.getDimension(R.dimen.dialog_user_name_margin).toInt()
            window?.setBackgroundDrawable(InsetDrawable(ColorDrawable(Color.TRANSPARENT), inset))
            window?.setLayout(
                if (currentDialogOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    WindowManager.LayoutParams.MATCH_PARENT
                } else {
                    WindowManager.LayoutParams.WRAP_CONTENT
                },
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            userDialog = this
            if (showKeyboard) {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

                eText.requestFocus()
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(eText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun validateNewUserName(newName: String): Boolean =
        !newName.isEmptyTrimmed() && !viewModel.getUser()?.displayName.equals(newName)


    private fun initUser() {
        viewModel.initUser()
    }

    private fun checkIfRequireDisplayNameSetting() {
        if (viewModel.noDisplayNameSet()) {
            showUserNameDialog()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUIForHidingKeyboardOnOutsideTouch(view: View) {
        // Set up touch listener for non-text box views to hide keyboard.
        if (view !is EditText && view.id != btnSend.id) {
            view.setOnTouchListener { _, _ ->
                hideKeyboard()
                view.requestFocus()
                false
            }
        }

        //If a layout container, iterate over children and seed recursion.
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val innerView = view.getChildAt(i)
                setupUIForHidingKeyboardOnOutsideTouch(innerView)
            }
        }
    }

    //callback from drawer in landscape
    override fun showMessageInputVisibleIfRequired(shouldShow: Boolean) {
        if (!shouldShow && ll_wrapper.visibility == View.VISIBLE) {
            ll_wrapper.visibility = View.GONE
        } else if (shouldShow && ll_wrapper.visibility != View.VISIBLE) {
            ll_wrapper.visibility = View.VISIBLE
        }
    }

    private fun animatePollBadgeIfRequired(marginBottomDp: Float) {
        val params: ViewGroup.MarginLayoutParams? =
            polls_motion_layout?.layoutParams as ViewGroup.MarginLayoutParams
        val initBottomMargin = params?.bottomMargin ?: 0
        val endBottomMargin = polls_motion_layout?.dpToPx(marginBottomDp) ?: 0
        val a = object : Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
                val marginParams: ViewGroup.MarginLayoutParams? =
                    polls_motion_layout?.layoutParams as ViewGroup.MarginLayoutParams
                marginParams?.bottomMargin = initBottomMargin +
                        ((endBottomMargin - initBottomMargin) * interpolatedTime).toInt()
                polls_motion_layout?.layoutParams = params
            }
        }
        a.duration = 400
        polls_motion_layout?.startAnimation(a)
    }
}
