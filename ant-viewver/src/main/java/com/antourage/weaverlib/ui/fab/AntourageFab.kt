package com.antourage.weaverlib.ui.fab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.Keep
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Observer
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.R
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.*
import com.antourage.weaverlib.other.models.*
import com.antourage.weaverlib.other.networking.ApiClient.BASE_URL
import com.antourage.weaverlib.other.networking.ConnectionStateMonitor.Companion.internetStateLiveData
import com.antourage.weaverlib.other.networking.NetworkConnectionState
import com.antourage.weaverlib.other.networking.Resource
import com.antourage.weaverlib.other.networking.SocketConnector
import com.antourage.weaverlib.other.networking.SocketConnector.disconnectSocket
import com.antourage.weaverlib.other.networking.SocketConnector.newLivesLiveData
import com.antourage.weaverlib.other.networking.SocketConnector.newVodsLiveData
import com.antourage.weaverlib.other.networking.SocketConnector.socketConnection
import com.antourage.weaverlib.other.networking.Status
import com.antourage.weaverlib.screens.base.AntourageActivity
import com.antourage.weaverlib.screens.base.Repository
import com.antourage.weaverlib.screens.list.ReceivingVideosManager
import com.antourage.weaverlib.screens.list.dev_settings.DevSettingsDialog
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.google.android.exoplayer2.Player
import com.google.android.material.internal.ContextUtils.getActivity
import kotlinx.android.synthetic.main.antourage_fab_layout.view.*
import org.jetbrains.anko.sdk27.coroutines.onClick
import java.util.*
import kotlin.math.roundToInt

/**
 * When integrating to React Native need to add also constraint layout library in declaration
 * so it would know that it is subclass of View
 */

@Keep
class AntourageFab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), FabActionHandler {

    companion object {
        private var cachedFcmToken: String = ""
        internal var isSubscribedToPushes = false
        private var pushRegistrationCallback: ((result: RegisterPushNotificationsResult) -> Unit)? =
            null
        internal const val AUTH_RETRY = 10000L
        internal const val MAX_HORIZONTAL_MARGIN = 50
        internal const val MAX_VERTICAL_MARGIN: Int = 220
        internal const val ARGS_STREAM_SELECTED = "args_stream_selected"
        internal const val TAG = "AntourageFabLogs"
        internal var mLastClickTime: Long = 0

        /** added to prevent multiple calls of onResume breaking widget logic*/
        internal var wasPaused = true

        private var apiKey: String = ""
        private var refUserId: String? = null
        private var nickname: String? = null
        private val authorizeHandler = Handler()
        private var authorizeRunning = false

        fun authWith(
            apiKey: String,
            refUserId: String? = null,
            nickname: String? = null,
            context: Context
        ) {
            this.apiKey = apiKey
            this.refUserId = refUserId
            this.nickname = nickname

            val userCache = UserCache.getInstance(context)
            val token = userCache?.getToken()
            if (token == null || token.isEmptyTrimmed()) {
                if (BASE_URL.isEmptyTrimmed()) BASE_URL =
                    UserCache.getInstance(context)?.getBeChoice() ?: DevSettingsDialog.DEFAULT_URL
                authorizeUser(apiKey, refUserId, nickname, context)
            }
        }

        private fun authorizeUser(
            apiKey: String,
            refUserId: String? = null,
            nickname: String? = null,
            context: Context
        ) {
            Log.d(TAG, "Trying to authorize ant user...")
            authorizeRunning = true
            refUserId?.let { UserCache.getInstance(context)?.saveUserRefId(it) }
            nickname?.let { UserCache.getInstance(context)?.saveUserNickName(it) }
            UserCache.getInstance(context)?.saveApiKey(apiKey)

            val response = Repository.generateUser(UserRequest(apiKey, refUserId, nickname))
            response.observeForever(object : Observer<Resource<User>> {
                override fun onChanged(it: Resource<User>?) {
                    when (val responseStatus = it?.status) {
                        is Status.Success -> {
                            val user = responseStatus.data
                            Log.d(TAG, "Ant authorization successful")
                            user?.apply {
                                if (token != null && id != null) {
                                    stopAuthHandler()
                                    authorizeRunning = false
                                    Log.d(
                                        TAG,
                                        "Ant token and ant userId != null, started live video timer"
                                    )
                                    UserCache.getInstance(context)?.saveUserAuthInfo(token, id)
                                    if (!isSubscribedToPushes) retryRegisterNotifications()
                                    startAntRequests()
                                }
                            }
                            response.removeObserver(this)
                        }
                        is Status.Failure -> {
                            Log.d(TAG, "Ant authorization failed: ${responseStatus.errorMessage}")
                            authorizeRunning = false
                            startAuthHandler(context)
                            response.removeObserver(this)
                        }
                    }
                }
            })
        }

        private fun startAuthHandler(context: Context) {
            if (!authorizeRunning) {
                authorizeHandler.removeCallbacksAndMessages(null)
                authorizeHandler.postDelayed({
                    if (!userAuthorized(context) && !wasPaused && apiKey.isNotEmpty()) authWith(
                        apiKey,
                        refUserId,
                        nickname,
                        context
                    )
                }, AUTH_RETRY)
            }
        }

        private fun stopAuthHandler() {
            authorizeHandler.removeCallbacksAndMessages(null)
        }

        private fun startAntRequests(isInitial: Boolean = true) {
            if (isInitial) {
                ReceivingVideosManager.isFirstRequestVod = true
            }
            ReceivingVideosManager.isFirstRequest = true
            ReceivingVideosManager.startReceivingLiveStreams(true)
        }

        private fun userAuthorized(context: Context): Boolean {
            return !(UserCache.getInstance(context)?.getToken().isNullOrBlank())
        }

        fun retryRegisterNotifications(firebaseToken: String? = null) {
            if (pushRegistrationCallback == null) return
            if (firebaseToken != null) cachedFcmToken = firebaseToken
            if (UserCache.getInstance() != null && UserCache.getInstance()!!.getToken()
                    .isNullOrBlank()
            ) {
                return
            }
            if (cachedFcmToken.isNotEmpty() && pushRegistrationCallback != null) {
                Handler(Looper.getMainLooper()).post {
                    registerNotifications(cachedFcmToken, pushRegistrationCallback)
                }
            }
        }

        fun registerNotifications(
            fcmToken: String?,
            callback: ((result: RegisterPushNotificationsResult) -> Unit)? = null
        ) {
            Log.d(TAG, "Trying to register ant push notifications...")
            pushRegistrationCallback = callback
            if (fcmToken.isNullOrEmpty()) return
            cachedFcmToken = fcmToken
            val response =
                Repository.subscribeToPushNotifications(SubscribeToPushesRequest(fcmToken))
            response.observeForever(object : Observer<Resource<NotificationSubscriptionResponse>> {
                override fun onChanged(it: Resource<NotificationSubscriptionResponse>?) {
//                            subscriptionRunning = false
                    when (val responseStatus = it?.status) {
                        is Status.Success -> {
                            responseStatus.data?.topic?.let { topicName ->
                                RegisterPushNotificationsResult.Success(
                                    topicName
                                )
                            }?.let { result -> callback?.invoke(result) }
                            isSubscribedToPushes = true
                            Log.d(TAG, "Push notification registration successful")
                            responseStatus.data?.topic?.let {
                                Log.d(TAG, "Topic name: $it")
                            }
                            response.removeObserver(this)
                        }
                        is Status.Failure -> {
                            callback?.invoke(RegisterPushNotificationsResult.Failure(responseStatus.errorMessage))
                            Log.d(
                                TAG,
                                "Push notification registration failed; ${responseStatus.errorMessage}"
                            )
                            response.removeObserver(this)
                        }
                    }
                }
            })
        }
    }

    private var viewIsDrawn: Boolean = false
    private var horizontalMargin: Int = 0
    private var verticalMargin: Int = 0
    private var widgetPosition = WidgetPosition.bottomRight
    private var shouldDisconnectSocket: Boolean = true
    private var goingLiveToLive: Boolean = false
    private var currentlyDisplayedLiveStream: StreamResponse? = null
    private var currentAnimationDrawableId: Int = -1
    private val liveStreams = arrayListOf<StreamResponse>()
    private val vods = arrayListOf<StreamResponse>()
    private val shownLiveStreams = linkedSetOf<StreamResponse>()
    private var currentFabState: FabState = FabState.INACTIVE
    private var nextFabState: FabState? = null
    private var forceVodStateIfNeeded: Boolean = false
    private var isAnimationRunning = false
    private var circleAnimatedDrawable: AnimatedVectorDrawableCompat? = null
    private var playIconAnimatedDrawable: AnimatedVectorDrawableCompat? = null
    private var playIconAlphaHandler: Handler = Handler()
    private var playIconStartHandler: Handler = Handler()
    private var bounceHandler: Handler = Handler()
    private var currentPlayerState: Int = 0
    private var isShowingLive: Boolean = false
    private var badgeColor: Drawable? = null
    private var textBadge: String = ""
        set(value) {
            if (value == badgeView.text) return
            if (value.isNotEmpty()) badgeView.text = value
            updateBadgeColor(value)
            if (ViewCompat.isLaidOut(this)) {
                invalidateBadge(value)
            }
        }

    init {
        if (BASE_URL.isEmptyTrimmed()) BASE_URL =
            UserCache.getInstance(context)?.getBeChoice() ?: DevSettingsDialog.DEFAULT_URL
        View.inflate(context, R.layout.antourage_fab_layout, this)
        fabContainer.onClick { checkWhatToOpen() }
        AntourageFabLifecycleObserver.registerActionHandler(this)
        clearStreams()
        initPlayAnimation()
        initDefaultFabLocation()
    }

    private fun initDefaultFabLocation() {
        setPosition(true)
    }

    /**
     * Method to show fab
     */
    fun showFab(activity: Activity) {
        if (parent == null) {
            val viewGroup =
                (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
            viewGroup.addView(this)
        }
    }

    /**
     * Method to force set locale (currently default or Swedish)
     */
    fun setLocale(lang: String? = null) {
        if (lang != null) {
            forceLocale(Locale(lang))
        } else if (Global.currentLocale != null) {
            forceLocale(Global.currentLocale!!)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun forceLocale(locale: Locale) {
        if (getActivity(context) != null) {
            val config =
                Configuration(getActivity(context)!!.resources.configuration)
            Locale.setDefault(locale)
            config.setLocale(locale)
            Global.currentLocale = locale
            getActivity(context)?.baseContext?.resources?.updateConfiguration(
                config,
                getActivity(context)?.baseContext?.resources?.displayMetrics
            )
        }
    }

    /**
     * Method needed for react native integration for positioning
     * Should be called in createViewInstance
     * */
    fun resetViewIsDrawn(){
        viewIsDrawn = false
    }

    /**
     * Method to move fab to predefined positions
     * */
    fun setPosition(position: String?) {
        Log.d(TAG, "setPosition: $position")
        position?.let {
            try {
                widgetPosition = WidgetPosition.valueOf(it)
                setPosition(false)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "INVALID position value: $widgetPosition | $e")
            }

        }
    }

    /**
     * Method to move fab to predefined positions
     * */
    fun setPosition(position: WidgetPosition) {
        Log.d(TAG, "setPosition: $position")
        widgetPosition = position
        setPosition(false)
    }

    private fun setPosition(justResetPosition: Boolean) {
        if (!viewIsDrawn) {
            viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    viewIsDrawn = true
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    invalidatePosition(justResetPosition)
                }
            })
        } else {
            invalidatePosition(justResetPosition)
        }
    }

    private fun invalidatePosition(justResetPosition: Boolean) {
        val parent = parent as? View
        if(parent!=null){
            if(parent.height == 0 || parent.width ==0 ) return
            when (widgetPosition) {
                WidgetPosition.bottomLeft -> {
                    x = 0f
                    y = (parent.height - height).toFloat()
                }
                WidgetPosition.bottomRight -> {
                    x = (parent.width - width).toFloat()
                    y = (parent.height - height).toFloat()
                }
                WidgetPosition.bottomMid -> {
                    x = ((parent.width / 2).toDouble().roundToInt() - (width / 2).toDouble()
                        .roundToInt()).toFloat()
                    y = (parent.height - height).toFloat()
                }
                WidgetPosition.midLeft -> {
                    x = 0f
                    y = ((parent.height / 2).toDouble()
                        .roundToInt() - (height / 2).toDouble().roundToInt()).toFloat()
                }
                WidgetPosition.midRight -> {
                    x = (parent.width - width).toFloat()
                    y = ((parent.height / 2).toDouble()
                        .roundToInt() - (height / 2).toDouble().roundToInt()).toFloat()
                }
                WidgetPosition.topLeft -> {
                    x = 0f
                    y = 0f
                }
                WidgetPosition.topMid -> {
                    x = ((parent.width / 2).toDouble().roundToInt() - (width / 2).toDouble()
                        .roundToInt()).toFloat()
                    y = 0f
                }
                WidgetPosition.topRight -> {
                    x = (parent.width - width).toFloat()
                    y = 0f
                }
            }
            if (!justResetPosition) applyMargins()
        }
    }

    /**
     * Method to set margins
     * */
    fun setMargins(horizontal: Int, vertical: Int) {
        horizontalMargin = horizontal.validateHorizontalMarginForFab(context)
        verticalMargin = vertical.validateVerticalMarginForFab(context)
        applyMargins()
    }

    private fun applyMargins() {
        setPosition(true)
        if (!viewIsDrawn) {
            viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    viewIsDrawn = true
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    invalidateMargins()
                }
            })
        } else {
            invalidateMargins()
        }
    }

    private fun invalidateMargins() {
        val parent = parent as? View
        if(parent == null || parent.height == 0 || parent.width == 0 ) return
        when (widgetPosition) {
            WidgetPosition.topLeft -> {
                x += horizontalMargin
                y += verticalMargin
            }
            WidgetPosition.topMid -> {
                y += verticalMargin
            }
            WidgetPosition.topRight -> {
                x -= horizontalMargin
                y += verticalMargin
            }
            WidgetPosition.midLeft -> {
                x += horizontalMargin
            }
            WidgetPosition.midRight -> {
                x -= horizontalMargin
            }
            WidgetPosition.bottomLeft -> {
                x += horizontalMargin
                y -= verticalMargin
            }
            WidgetPosition.bottomMid -> {
                y -= verticalMargin
            }
            WidgetPosition.bottomRight -> {
                x -= horizontalMargin
                y -= verticalMargin
            }
        }
    }

    override fun onResume() {
        if (!wasPaused) return
        if (didViewerAppear) onViewerDisappear()
        wasPaused = false
        if (!userAuthorized(context) && apiKey.isNotEmpty() && !authorizeRunning) authWith(
            apiKey,
            refUserId,
            nickname,
            context
        )
        setLocale()
        internetStateLiveData.observeForever(networkStateObserver)
        shouldDisconnectSocket = true

        ReceivingVideosManager.setReceivingVideoCallback(object :
            ReceivingVideosManager.ReceivingVideoCallback {
            override fun onVODForFabReceived(resource: Resource<List<StreamResponse>>) {
                super.onVODForFabReceived(resource)

                if (ReceivingVideosManager.isFirstRequestVod) {
                    ReceivingVideosManager.isFirstRequestVod = false
                    ReceivingVideosManager.pauseReceivingVideos()
                    UserCache.getInstance(context)?.getToken()?.let {
                        SocketConnector.connectToSockets(it)
                        initSocketListeners()
                    }
                }

                when (val status = resource.status) {
                    is Status.Success -> {
                        if (!status.data.isNullOrEmpty()) {
                            vods.clear()
                            vods.addAll(status.data)
                            manageVODs()
                        } else {
                            vods.clear()
                            manageVODs()
                        }
                    }
                    is Status.Failure -> {
                        vods.clear()
                        manageVODs()
                    }
                }
            }

            override fun onLiveBroadcastReceived(resource: Resource<List<StreamResponse>>) {
                when (val status = resource.status) {
                    is Status.Success -> {
                        if (!status.data.isNullOrEmpty()) {
                            liveStreams.clear()
                            liveStreams.addAll(status.data)
                            if (!goingLiveToLive) {
                                manageLiveStreams()
                            }
                        } else {
                            liveStreams.clear()
                            manageVODs(true)
                            goingLiveToLive = false
                        }
                    }
                    is Status.Failure -> {
                        manageVODs(true)
                        goingLiveToLive = false
                    }
                }
            }
        })

        StreamPreviewManager.setStreamManager(object : StreamPreviewManager.StreamCallback {
            override fun onNewState(playbackState: Int) {
                super.onNewState(playbackState)
                currentPlayerState = playbackState
                if (playbackState == Player.STATE_READY) {
                    bounceHandler.removeCallbacksAndMessages(null)
                    setIncomingWidgetStatus(FabState.LIVE)
                }
            }

            override fun onError() {
                super.onError()
                manageVODs(true)
            }
        })

        Handler().postDelayed({
            if (userAuthorized(context)) {
                startAntRequests()
            }
        }, 500)
    }

    override fun onPause() {
        wasPaused = true
        stopAuthHandler()
        StreamPreviewManager.removeEventListener()
        ReceivingVideosManager.stopReceivingVideos()
        if (shouldDisconnectSocket) disconnectSocket()
        removeSocketListeners()
        currentPlayerState = 0
        isAnimationRunning = false
        goingLiveToLive = false
        vods.clear()
        currentFabState = FabState.INACTIVE
        setIncomingWidgetStatus(null)
        bounceHandler.removeCallbacksAndMessages(null)
        Handler().postDelayed({
            circleAnimatedDrawable?.apply {
                clearAnimationCallbacks()
            }
            circleAnimatedDrawable?.stop()
            textBadge = ""
            releasePlayer()
        }, 100)
    }

    private fun manageLiveStreams() {
        if (!newestStreamWasShown() && !goingLiveToLive) {
            if (isShowingLive) {
                setLiveToLiveState()
            } else {
                setLiveState()
            }
        }
    }

    private fun manageVODs(liveEnded: Boolean = false) {
        if (Global.networkAvailable) {
            if (liveEnded || !isShowingLive) {
                setVodState()
            }
        } else {
            setIncomingWidgetStatus(FabState.INACTIVE)
        }
    }

    private fun setLiveState() {
        setIncomingWidgetStatus(FabState.PRE_LIVE)
    }

    private fun setLiveToLiveState() {
        goingLiveToLive = true
        setIncomingWidgetStatus(FabState.INACTIVE)
    }

    private fun setVodState() {
        if (vods.isNotEmpty()) {
            if (currentFabState != FabState.PRE_VOD && currentFabState != FabState.VOD && nextFabState != FabState.PRE_VOD && nextFabState != FabState.VOD || forceVodStateIfNeeded) {
                forceVodStateIfNeeded = false
                setIncomingWidgetStatus(FabState.PRE_VOD)
                return
            }
        } else {
            setIncomingWidgetStatus(FabState.INACTIVE)
        }
    }

    private fun setIncomingWidgetStatus(status: FabState?) {
        nextFabState = if (!isAnimationRunning && status != null && status != currentFabState) {
            changeFabState(status)
            null
        } else {
            status
        }
    }

    private fun changeFabState(state: FabState) {
        currentFabState = state
        hideStreamPreview(state)
        when (state) {
            FabState.INACTIVE -> {
                hideBadge()
                circleAnimatedDrawable?.clearAnimationCallbacks()
                if (goingLiveToLive) {
                    Handler().postDelayed({
                        setIncomingWidgetStatus(FabState.PRE_LIVE)
                    }, 1200)
                }
            }
            FabState.PRE_LIVE -> {
                isShowingLive = true
                val streamToDisplay = getNextStreamToDisplay()
                streamToDisplay?.let {
                    currentlyDisplayedLiveStream = streamToDisplay
                    startPlayingStream(streamToDisplay)
                    shownLiveStreams.add(streamToDisplay)
                }
                setTextToBadge(context.getString(R.string.ant_live))
                startAnimation(state)
            }
            FabState.LIVE -> {
                if (goingLiveToLive) goingLiveToLive = false
                showPlayer()
                startAnimation(state)
            }
            FabState.PRE_VOD -> {
                setTextToBadge(context.getString(R.string.ant_new_vod))
                startAnimation(state)
            }
            FabState.VOD -> {
                startAnimation(state)
            }
        }
    }

    private fun startAnimation(animation: FabState) {
        setAnimatedDrawable(animation.animationDrawableId)
        circleAnimatedDrawable?.clearAnimationCallbacks()
        circleAnimatedDrawable?.apply {
            registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                override fun onAnimationStart(drawable: Drawable?) {
                    super.onAnimationStart(drawable)
                    isAnimationRunning = true
                }

                override fun onAnimationEnd(drawable: Drawable?) {
                    isAnimationRunning = false
                    if (nextFabState != null) {
                        changeFabState(nextFabState!!)
                        nextFabState = null
                        return
                    }
                    when (animation) {
                        FabState.PRE_LIVE -> {
                            currentPlayerState.let {
                                if (it != Player.STATE_READY) {
                                    bounceHandler.postDelayed({
                                        startAnimation(FabState.PRE_LIVE)
                                    }, 800)
                                }
                            }
                        }
                        FabState.LIVE -> {
                            currentPlayerState.let {
//                                if (it == Player.STATE_READY && playerView.player?.isPlaying == true || it == Player.STATE_BUFFERING)
                                if (it == Player.STATE_READY && playerView.player?.isPlaying == true)
                                    startAnimation(
                                        FabState.LIVE
                                    )
                            }
                        }
                        FabState.PRE_VOD -> {
                            setIncomingWidgetStatus(FabState.VOD)
                        }
                        FabState.VOD -> {
                            startAnimation(FabState.VOD)
                        }
                        FabState.INACTIVE -> {
                        }
                    }
                }
            })
            start()
        }
    }

    private fun hideStreamPreview(state: FabState) {
        if (state != FabState.LIVE && state != FabState.PRE_LIVE) {
            releasePlayer()
        }
    }

    private fun showPlayer() {
        playerView.alpha = 0f
        playerView.visibility = View.VISIBLE
        playerView.animate().alpha(1f).setDuration(300).start()
        logoView.animate().alpha(0f).setDuration(300).start()
        playIconStartHandler.postDelayed({
            showPlayAnimation()
        }, 1200)
    }

    private fun releasePlayer() {
        isShowingLive = false
        clearStreams()
        StreamPreviewManager.releasePlayer()
        playerView.animate().alpha(0f).setDuration(300).start()
        logoView.animate().alpha(1f).setDuration(300).start()
        playIconView.alpha = 0f
        playIconAlphaHandler.removeCallbacksAndMessages(null)
        playIconStartHandler.removeCallbacksAndMessages(null)
        playIconAnimatedDrawable?.clearAnimationCallbacks()
        playIconAnimatedDrawable?.stop()
        playIconView.animation?.cancel()
        playIconView.animation?.reset()
        playIconView.clearAnimation()
    }

    private fun showPlayAnimation() {
        /** play animation doesn't repeat without this on some lower M devices*/
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            initPlayAnimation()
        }
        playIconView.alpha = 0f
        playIconView.visibility = View.VISIBLE
        playIconView.animate().alpha(1f).setDuration(100).start()
        playIconAnimatedDrawable?.apply {
            clearAnimationCallbacks()
            registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    if (isShowingLive) {
                        showPlayAnimation()
                    }
                }
            })
            start()
            playIconAlphaHandler.postDelayed({
                playIconView.animate().alpha(0f).setDuration(200).start()
            }, 3800)
        }
    }

    private fun initPlayAnimation() {
        playIconAnimatedDrawable = context?.let {
            AnimatedVectorDrawableCompat.create(
                it,
                R.drawable.antourage_fab_play_animation
            )
        }
        playIconView.setImageDrawable(playIconAnimatedDrawable)
    }

    private fun setAnimatedDrawable(drawableId: Int) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || currentAnimationDrawableId != drawableId) {
            currentAnimationDrawableId = drawableId
            circleAnimatedDrawable = context?.let {
                AnimatedVectorDrawableCompat.create(
                    it,
                    drawableId
                )
            }
            circleView.setImageDrawable(circleAnimatedDrawable)
            circleView.background = null
        }
    }

    private fun startPlayingStream(stream: StreamResponse) {
        playerView.player = stream.hlsUrl?.get(0)?.let {
            StreamPreviewManager.getExoPlayer(
                it, context
            )
        }
    }

    private fun invalidateBadge(value: String) {
        if (value == context.getString(R.string.ant_live) || value == context.getString(R.string.ant_new_vod)) {
            showBadge()
        } else {
            hideBadge()
        }
    }

    private fun showBadge() {
        badgeView.background = badgeColor
        badgeView.showBadge(measureAndLayout)
    }

    /**method for React Native for force hide badge on widget appearing*/
    fun forceHideBadge(){
        badgeView?.alpha = 0f
        badgeView?.text = ""
    }

    private fun hideBadge() {
        badgeView.hideBadge(measureAndLayout)
    }

    private fun updateBadgeColor(value: String) {
        val backgroundDrawableId =
            if (value == context.getString(R.string.ant_live)) R.drawable.antourage_fab_badge_rounded_background_live else R.drawable.antourage_fab_badge_rounded_background_vod
        badgeColor =
            ContextCompat.getDrawable(
                context,
                backgroundDrawableId
            )
    }

    private fun getNextStreamToDisplay(): StreamResponse? {
        for (element in liveStreams) {
            if (shownLiveStreams.none { it.id == element.id }) {
                return element
            }
        }
        return null
    }

    private fun newestStreamWasShown(): Boolean {
        return if (liveStreams.isNotEmpty()) shownLiveStreams.contains(liveStreams[0])
        else false
    }

    private fun checkWhatToOpen() {
        //to prevent quick multiple taps
        if (SystemClock.elapsedRealtime() - mLastClickTime < 1000) {
            return
        }
        mLastClickTime = SystemClock.elapsedRealtime()

        shouldDisconnectSocket = false

        onViewerAppear()

        when (currentFabState) {
            FabState.INACTIVE -> {
                openAntActivity()
            }
            FabState.LIVE -> {
                openLiveStreamActivity()
            }
            FabState.PRE_LIVE -> {
                openLiveStreamActivity()
            }
            FabState.PRE_VOD -> {
                openVodActivity()
            }
            FabState.VOD -> {
                openVodActivity()
            }
        }
    }

    private var didViewerAppear = false

    /** needed for ReactNative library and uses reactContext */
    private fun onViewerAppear() {
        didViewerAppear = true
        try {
            val event: WritableMap = Arguments.createMap()
            val reactContext: ReactContext = context as ReactContext
            reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
                id,
                "onViewerAppear",
                event
            )
        } catch (e: Throwable) {
            //not in reactContext
        }
    }

    /** needed for ReactNative library and uses reactContext */
    private fun onViewerDisappear() {
        didViewerAppear = false
        try {
            val event: WritableMap = Arguments.createMap()
            val reactContext: ReactContext = context as ReactContext
            reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
                id,
                "onViewerDisappear",
                event
            )
        } catch (e: Throwable) {
            //not in reactContext
        }
    }

    private fun clearStreams() {
        currentlyDisplayedLiveStream = null
        shownLiveStreams.clear()
    }

    private fun openLiveStreamActivity() {
        if (currentlyDisplayedLiveStream != null) {
            val intent = Intent(context, AntourageActivity::class.java)
            currentlyDisplayedLiveStream?.isLive = true
            intent.putExtra(ARGS_STREAM_SELECTED, currentlyDisplayedLiveStream)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }

    private fun openVodActivity() {
        if (vods.isNotEmpty()) {
            Repository.vods = vods.toMutableList()
            val intent = Intent(context, AntourageActivity::class.java)
            intent.putExtra(ARGS_STREAM_SELECTED, vods[0])
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }
    }

    private fun openAntActivity() {
        setAllLiveStreamsAsSeen()
        val intent = Intent(context, AntourageActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    /**
    if user goes to videos list screen, he will see all live videos anyway
    so no need to show them in fab expansion
     */
    private fun setAllLiveStreamsAsSeen() {
        shownLiveStreams.addAll(liveStreams)
    }


    private val networkStateObserver: Observer<NetworkConnectionState> = Observer { networkState ->
        when (networkState?.ordinal) {
            NetworkConnectionState.LOST.ordinal -> {
                if (!Global.networkAvailable) {
                    clearStreams()
                    forceVodStateIfNeeded = true
                    disconnectSocket()
                    ReceivingVideosManager.pauseWhileNoNetwork()
                    SocketConnector.cancelReconnect()
                    setIncomingWidgetStatus(FabState.INACTIVE)
                }
            }
            NetworkConnectionState.AVAILABLE.ordinal -> {
                if (userAuthorized(context)) {
                    startAntRequests()
                }
            }
        }
    }

    override fun requestLayout() {
        super.requestLayout()
        post(measureAndLayout)
    }

    private val measureAndLayout = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    private val socketConnectionObserver = Observer<SocketConnector.SocketConnection> {
        if (it == SocketConnector.SocketConnection.DISCONNECTED) {
            if (Global.networkAvailable) {
                if (userAuthorized(context)) {
                    startAntRequests(false)
                }
            }
        }
    }

    private val liveFromSocketObserver = Observer<List<StreamResponse>> { newStreams ->
        if (newStreams != null) {
            val newLives = newStreams as ArrayList<StreamResponse>
            if (!newLives.isNullOrEmpty()) {
                liveStreams.clear()
                liveStreams.addAll(newLives)
                if (!goingLiveToLive) {
                    runOnUi { manageLiveStreams() }
                }
            } else {
                liveStreams.clear()
                runOnUi { manageVODs(true) }
                goingLiveToLive = false
            }
        }
    }

    private val vodsFromSocketObserver = Observer<List<StreamResponse>> { newStreams ->
        if (newStreams != null) {
            val newVods = newStreams as ArrayList<StreamResponse>

            if (!newVods.isNullOrEmpty()) {
                vods.clear()
                vods.addAll(newVods)
                runOnUi { manageVODs() }
            } else {
                vods.clear()
                runOnUi { manageVODs() }
            }
        }
    }

    private fun removeSocketListeners() {
        internetStateLiveData.removeObserver(networkStateObserver)
        socketConnection.removeObserver(socketConnectionObserver)
        newVodsLiveData.removeObserver(vodsFromSocketObserver)
        newLivesLiveData.removeObserver(liveFromSocketObserver)
    }

    private fun initSocketListeners() {
        socketConnection.observeForever(socketConnectionObserver)
        newVodsLiveData.observeForever(vodsFromSocketObserver)
        newLivesLiveData.observeForever(liveFromSocketObserver)
    }

    private fun runOnUi(method: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            method()
        }
    }

    private fun setTextToBadge(text: String) {
        textBadge = text
    }

    internal enum class FabState(val animationDrawableId: Int) {
        INACTIVE(0),
        PRE_LIVE(R.drawable.antourage_fab_bounce_once_animation),
        LIVE(R.drawable.antourage_fab_rotation_animation),
        PRE_VOD(R.drawable.antourage_fab_bounce_animation),
        VOD(R.drawable.antourage_fab_rotation_animation)
    }
}