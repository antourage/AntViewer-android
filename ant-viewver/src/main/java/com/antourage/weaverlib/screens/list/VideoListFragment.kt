package com.antourage.weaverlib.screens.list

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.R
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.other.*
import com.antourage.weaverlib.other.models.FeedInfo
import com.antourage.weaverlib.other.models.ProfileResponse
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.ConnectionStateMonitor
import com.antourage.weaverlib.other.networking.NetworkConnectionState
import com.antourage.weaverlib.other.networking.VideoCloseBackUp
import com.antourage.weaverlib.other.networking.feed.FeedRepository
import com.antourage.weaverlib.screens.base.AntourageActivity
import com.antourage.weaverlib.screens.base.BaseFragment
import com.antourage.weaverlib.screens.list.dev_settings.DevSettingsDialog
import com.antourage.weaverlib.screens.list.refresh.AntPullToRefreshView
import com.antourage.weaverlib.screens.list.rv.VerticalSpaceItemDecorator
import com.antourage.weaverlib.screens.list.rv.VideosAdapter
import com.antourage.weaverlib.screens.vod.VodPlayerFragment
import com.antourage.weaverlib.screens.weaver.PlayerFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.squareup.picasso.Callback
import com.squareup.picasso.NetworkPolicy
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.fragment_videos_list.*
import org.jetbrains.anko.backgroundColor
import org.jetbrains.anko.backgroundDrawable

internal class VideoListFragment : BaseFragment<VideoListViewModel>() {
    override fun getLayoutId() = R.layout.fragment_videos_list

    private lateinit var snackBarBehaviour: BottomSheetBehavior<View>
    private lateinit var videoAdapter: VideosAdapter
    private lateinit var placeholdersAdapter: VideoPlaceholdersAdapter
    private lateinit var rvLayoutManager: LinearLayoutManager
    private lateinit var placeholderLayoutManager: LinearLayoutManager
    private var refreshVODs = true
    private var dontRefreshWhileInit = true
    private var isLoadingMoreVideos = false
    private var isNewLiveButtonShown = false
    private var isInitialListSet = true
    private var newItemsList = mutableListOf<StreamResponse>()
    private var shouldDisconnectSocket: Boolean = true
    private var isSnackBarScrollActive: Boolean = false
    private var canShowNewButton = false

    companion object {
        fun newInstance(): VideoListFragment {
            return VideoListFragment()
        }
    }

    //region Observers
    private val streamsObserver: Observer<List<StreamResponse>> = Observer { list ->
        list?.let { newStreams ->
            if (newStreams.isEmpty()) {
                if (viewModel.vodsUpdatedWithoutError) {
                    if (Global.networkAvailable) showEmptyListPlaceholder()
                    else showNoConnectionPlaceHolder()
                }
            } else {
                val listBeforeUpdate =
                    mutableListOf<StreamResponse>().apply { addAll(videoAdapter.getStreams()) }

                isLoadingMoreVideos = false

                if (videoRefreshLayout.mIsRefreshing) {
                    videoAdapter.setStreamListForceUpdate(newStreams)
                    videosRV.onRefresh()
                } else {
                    videoAdapter.setStreamList(newStreams)
                }

                videosRV.resumePlaying()

                Handler(Looper.getMainLooper()).postDelayed({
                    checkIsNewItemAdded(newStreams, listBeforeUpdate)
                    checkIsLiveWasRemoved(newStreams)
                }, 500)

                isInitialListSet = false
                if (viewModel.vodsUpdated && viewModel.liveVideosUpdated) {
                    if (Global.networkAvailable || !isNoConnectionSnackbarShowing()) {
                        resolveErrorSnackbar()
                    }
                    hidePlaceholder()
                }
            }
        }
        stopRefreshing()
    }

    private val errorObserver: Observer<String> = Observer { error ->
        error?.let { it ->
            if (Global.networkAvailable && it.isNotEmpty()) {
                stopRefreshing()
                showErrorLayout()
            }
        }
    }

    private val loaderObserver: Observer<Boolean> = Observer { show ->
        if (show == true) {
            showLoadingLayout()
        }
    }

    private val feedInfoObserver: Observer<FeedInfo> = Observer { feedInfo ->
        feedInfo?.let {
            updateFeedInfo(feedInfo)
        }
    }

    private val profileObserver: Observer<ProfileResponse> = Observer { profile ->
        profile?.let {
            loadNewProfileImage()
        }
    }

    private val beChoiceObserver: Observer<Boolean> = Observer {
        if (it != null && it)
            context?.let { context ->
                val dialog = DevSettingsDialog(context, viewModel)
                dialog.show()
            }
    }

    private val networkStateObserver: Observer<NetworkConnectionState> = Observer { networkState ->
        when (networkState?.ordinal) {
            NetworkConnectionState.LOST.ordinal -> {
                if (!Global.networkAvailable) {
                    if (placeholdersAdapter.getState() == VideoPlaceholdersAdapter.LoadingState.LOADING.value) {
                        placeholdersAdapter.setState(VideoPlaceholdersAdapter.LoadingState.NO_INTERNET)
                    }
                    showNoConnectionPlaceHolder()
                    viewModel.onNetworkChanged(false)
                }
            }
            NetworkConnectionState.AVAILABLE.ordinal -> {
                viewModel.onNetworkChanged(true)
                resolveErrorSnackbar(R.string.ant_you_are_online)
                if (placeholdersAdapter.getState() == VideoPlaceholdersAdapter.LoadingState.ERROR.value
                    || placeholdersAdapter.getState() == VideoPlaceholdersAdapter.LoadingState.NO_INTERNET.value && placeholderRefreshLayout.alpha == 1f
                ) {
                    viewModel.onNetworkGained(true)
                    placeholdersAdapter.setState(VideoPlaceholdersAdapter.LoadingState.LOADING)
                }
            }
        }
    }

    private fun subscribeToObservers() {
        viewModel.listOfStreams.observe(this.viewLifecycleOwner, streamsObserver)
        viewModel.errorLiveData.observe(this.viewLifecycleOwner, errorObserver)
        viewModel.loaderLiveData.observe(this.viewLifecycleOwner, loaderObserver)
        viewModel.feedInfoLiveData.observe(this.viewLifecycleOwner, feedInfoObserver)
        viewModel.profileLiveData.observe(this.viewLifecycleOwner, profileObserver)
        viewModel.getShowBeDialog().observe(this.viewLifecycleOwner, beChoiceObserver)
        ConnectionStateMonitor.internetStateLiveData.observe(
            this.viewLifecycleOwner,
            networkStateObserver
        )
    }
    //endregion

    //region Lifecycle callbacks
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(VideoListViewModel::class.java)
        setFragmentResultListener("antWebResponse") { _, bundle ->
            val result = bundle.getInt("antWebMessage")
            if (result != 0) showNotificationSnackBar(getString(result))
        }
        activity?.onBackPressedDispatcher?.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                shouldDisconnectSocket = false
                isEnabled = false
                activity?.onBackPressed()
            }
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        subscribeToObservers()
        loadFeedInfo()
        loadProfileInfo()

        btnNewLive.isEnabled = false
        ivClose.visibility = if ((activity as AntourageActivity).shouldHideBackButton){
            View.GONE
        }else{
            View.VISIBLE
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onResume() {
        super.onResume()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        invalidateUserBtn()
        videosRV.resetVideoView()
        shouldDisconnectSocket = true
        context?.let {
            viewModel.subscribeToLiveStreams(true)
            viewModel.refreshVODsLocally()
            if (!ConnectionStateMonitor.isNetworkAvailable()) {
                showNoConnectionPlaceHolder()
            } else if (ConnectionStateMonitor.isNetworkAvailable() &&
                viewModel.listOfStreams.value.isNullOrEmpty()
            ) {
                showLoadingLayout()
                if (!dontRefreshWhileInit) {
                    viewModel.refreshVODs(0)
                }
            }

            if (ConnectionStateMonitor.isNetworkAvailable() && isNoConnectionSnackbarShowing()) {
                resolveErrorSnackbar(R.string.ant_you_are_online)
            }
            if (videoRefreshLayout.alpha == 1f) videosRV.onResume()

        }
    }

    override fun onPause() {
        super.onPause()
        videosRV.onPause()
        triggerNewLiveButton(isVisible = false, isPause = true)
        canShowNewButton = false
        viewModel.onPause(shouldDisconnectSocket)
        viewModel.errorLiveData.postValue(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videosRV.adapter = null
        VideoCloseBackUp.sendBackUps()
    }
    //endregion


    override fun initUi(view: View?) {
        initSnackbar()
        initVideoRV()
        initPlaceHolderRV()
        initOnRefreshListeners()

        loginBtn.setOnClickListener {
            (activity as AntourageActivity).openJoinTab()
        }

        userBtn.setOnClickListener {
            (activity as AntourageActivity).openProfileTab()
        }

        btnNewLive.setOnClickListener {
            videosRV.betterSmoothScrollToPosition(0)
            videosRV.resetPlayPosition()
        }

        ivClose.setOnClickListener {
            shouldDisconnectSocket = false
            activity?.finish()
        }
        viewBEChoice.setOnClickListener { viewModel.onLogoPressed() }

        ReceivingVideosManager.setReceivingVideoCallback(viewModel)

        if (refreshVODs) {
            viewModel.refreshVODs()
            //needed for onResume to not refreshVods too
            Handler(Looper.getMainLooper()).postDelayed({
                dontRefreshWhileInit = false
            }, 300)
            refreshVODs = false
        }
    }

    private fun initNewButtonCountdown() {
        //needed in case live video was opened, then ended, and new live appeared
        canShowNewButton = false
        Handler(Looper.getMainLooper()).postDelayed({
            canShowNewButton = true
        }, 800)
    }

    private fun initVideoRV() {
        val onJoinedClick: (stream: StreamResponse) -> Unit = { streamResponse ->
            openLiveFragment(streamResponse, true)
        }

        val onClick: (stream: StreamResponse) -> Unit = { streamResponse ->
            when {
                streamResponse.isLive -> {
                    videosRV.hideAutoPlayLayout()
                    shouldDisconnectSocket = false
                    openLiveFragment(streamResponse)
                }
                streamResponse.id == -1 -> {
                    videosRV.betterSmoothScrollToPosition(0)
                }
                else -> {
                    context?.let { context ->
                        streamResponse.id?.let {
                            UserCache.getInstance(context)?.saveVideoToSeen(it)
                        }
                    }
                    videosRV.hideAutoPlayLayout()
                    shouldDisconnectSocket = false
                    openVodFragment(streamResponse)
                }
            }
        }

        videoAdapter = VideosAdapter(onClick, onJoinedClick, videosRV)
        //to trigger autoplay when autoplaying live and it dissapears
        videoAdapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                if (videosRV.playPosition == positionStart) {
                    videosRV.forceRestartAutoPlayOnChange()
                }
            }
        })
        videosRV.setRoomRepository(viewModel.roomRepository)
        rvLayoutManager = LinearLayoutManager(context)
        rvLayoutManager.orientation = LinearLayoutManager.VERTICAL
        rvLayoutManager.initialPrefetchItemCount = 4
        rvLayoutManager.isItemPrefetchEnabled = true
        videosRV.layoutManager = rvLayoutManager
        videosRV.adapter = videoAdapter
        val dividerItemDecoration = VerticalSpaceItemDecorator(
            dp2px(requireContext(), 32f).toInt()
        )
        videosRV.addItemDecoration(dividerItemDecoration)
        videosRV.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                videoRefreshLayout.isEnabled = true

                if (isNewLiveButtonShown) {
                    if (rvLayoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                        triggerNewLiveButton(false)
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val total = rvLayoutManager.itemCount
                    val lastVisibleItem = rvLayoutManager.findLastVisibleItemPosition()
                    if (videoAdapter.getStreams()
                            .isNotEmpty() && !isLoadingMoreVideos && total <= lastVisibleItem + 1 && videoAdapter.getStreams()[lastVisibleItem].id == -2
                    ) {
                        viewModel.refreshVODs(noLoadingPlaceholder = true)
                        isLoadingMoreVideos = true
                    }
                }
            }
        })
    }

    private fun initPlaceHolderRV() {
        placeholdersAdapter = VideoPlaceholdersAdapter()
        placeholderLayoutManager = LinearLayoutManager(context)
        placeholderLayoutManager.orientation = LinearLayoutManager.VERTICAL
        placeHolderRV.layoutManager = placeholderLayoutManager

        placeHolderRV.adapter = placeholdersAdapter
        val dividerItemDecoration = VerticalSpaceItemDecorator(
            dp2px(requireContext(), 32f).toInt()
        )
        placeHolderRV.addItemDecoration(dividerItemDecoration)
    }

    private fun initOnRefreshListeners() {
        videoRefreshLayout.setOnRefreshListener(object : AntPullToRefreshView.OnRefreshListener {
            override fun onRefresh() {
                videosRV.hideAutoPlayLayout()
                context?.let {
                    if (ConnectionStateMonitor.isNetworkAvailable()) {
                        viewModel.refreshVODs(0, true)
                        FeedRepository.updateLastSeenVod()
                        viewModel.refreshChatPollInfo()
                    } else {
                        videoRefreshLayout.setRefreshing(false)
                        showErrorSnackbar(R.string.ant_no_connection)
                    }
                }
            }
        })

        placeholderRefreshLayout.setOnRefreshListener(object :
            AntPullToRefreshView.OnRefreshListener {
            override fun onRefresh() {
                context?.let {
                    if (ConnectionStateMonitor.isNetworkAvailable()) {
                        viewModel.refreshVODs(0, true)
                        showLoadingLayout(true)
                    } else {
                        placeholderRefreshLayout.setRefreshing(false)
                        showErrorSnackbar(R.string.ant_no_connection)
                    }
                }
            }
        })

        noContentRefreshLayout.setOnRefreshListener(object :
            AntPullToRefreshView.OnRefreshListener {
            override fun onRefresh() {
                context?.let {
                    if (ConnectionStateMonitor.isNetworkAvailable()) {
                        viewModel.refreshVODs(0, true)
                    } else {
                        noContentRefreshLayout.setRefreshing(false)
                        showErrorSnackbar(R.string.ant_no_connection)
                    }
                }
            }
        })
    }

    private fun stopRefreshing() {
        videoRefreshLayout.setRefreshing(false)
        noContentRefreshLayout.setRefreshing(false)
        placeholderRefreshLayout.setRefreshing(false)
    }

    private fun initSnackbar() {
        snackBarBehaviour = BottomSheetBehavior.from(snackBar)
        var canScroll = true
        var lastOffset = 1f
        snackBarBehaviour.addBottomSheetCallback(object :
            BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, offset: Float) {
                videosRV.setPadding(0, 0, 0, (bottomSheet.height * offset).toInt())
                if (canScroll) {
                    canScroll = if (lastOffset > offset) {
                        if (videoRefreshLayout.alpha == 1f && videosRV.canScrollVertically(-1)) {
                            isSnackBarScrollActive = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                isSnackBarScrollActive = false
                            }, 1500)
                            videosRV.smoothScrollBy(0, (bottomSheet.height))
                        }
                        false
                    } else {
                        isSnackBarScrollActive = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            isSnackBarScrollActive = false
                        }, 1500)
                        videosRV.smoothScrollBy(0, -(bottomSheet.height))
                        false
                    }
                    lastOffset = offset
                }
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_DRAGGING) {
                    //to disable user swipe
                    snackBarBehaviour.state = BottomSheetBehavior.STATE_EXPANDED
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED || newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    canScroll = true
                }
            }
        })
    }

    private fun showErrorSnackbar(messageId: Int) {
        if (snackBarBehaviour.state != BottomSheetBehavior.STATE_EXPANDED) {
            context?.resources?.getString(messageId)
                ?.let { messageToDisplay ->
                    snackBar.text = messageToDisplay
                    context?.let {
                        snackBar.backgroundColor =
                            ContextCompat.getColor(it, R.color.ant_error_bg_color)
                    }
                }
            snackBarBehaviour.state = BottomSheetBehavior.STATE_EXPANDED
        } else if (messageId == R.string.ant_no_connection) {
            context?.resources?.getString(messageId)
                ?.let { messageToDisplay ->
                    snackBar.text = messageToDisplay
                    context?.let {
                        snackBar.backgroundColor =
                            ContextCompat.getColor(it, R.color.ant_error_bg_color)
                    }
                }
        }
    }

    private fun isNoConnectionSnackbarShowing(): Boolean {
        if (snackBar == null) return false
        return snackBarBehaviour.state == BottomSheetBehavior.STATE_EXPANDED && snackBar.text == context?.resources?.getString(
            R.string.ant_no_connection
        )
    }

    private fun showNotificationSnackBar(message: String) {
        if (snackBarBehaviour?.state != BottomSheetBehavior.STATE_EXPANDED) {
            snackBar?.text = message
            context?.let {
                snackBar.backgroundColor =
                    ContextCompat.getColor(it, R.color.ant_error_resolved_bg_color)
            }
            snackBarBehaviour.state = BottomSheetBehavior.STATE_EXPANDED
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isNoConnectionSnackbarShowing())
                    snackBarBehaviour.state = BottomSheetBehavior.STATE_COLLAPSED
            }, 3500)
        }
    }


    private fun resolveErrorSnackbar(messageId: Int? = null) {
        if (snackBarBehaviour.state == BottomSheetBehavior.STATE_EXPANDED || snackBarBehaviour.state == BottomSheetBehavior.STATE_SETTLING) {
            if (messageId != null) {
                context?.resources?.getString(messageId)
                    ?.let { messageToDisplay ->
                        snackBar.text = messageToDisplay
                        context?.let {
                            val colorFrom: Int =
                                ContextCompat.getColor(it, R.color.ant_error_bg_color)
                            val colorTo: Int =
                                ContextCompat.getColor(it, R.color.ant_error_resolved_bg_color)
                            val duration = 500L
                            ObjectAnimator.ofObject(
                                snackBar,
                                "backgroundColor",
                                ArgbEvaluator(),
                                colorFrom,
                                colorTo
                            )
                                .setDuration(duration)
                                .start()
                        }
                    }
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isNoConnectionSnackbarShowing())
                    snackBarBehaviour.state = BottomSheetBehavior.STATE_COLLAPSED
            }, 2000)
        }
    }

    private fun showLoadingLayout(showOnlyIfError: Boolean = false) {
        resolveErrorSnackbar()
        placeholdersAdapter.setState(VideoPlaceholdersAdapter.LoadingState.LOADING)

        if (showOnlyIfError) {
            if (videoRefreshLayout.alpha != 1f) {
                if (placeholderRefreshLayout.alpha != 1f) {
                    placeholderRefreshLayout.revealWithAnimation()
                    placeHolderRV.revealWithAnimation()
                }
            }
        } else {
            if (noContentRefreshLayout.visibility == View.VISIBLE) noContentRefreshLayout.hideWithAnimation()
            if (videoRefreshLayout.visibility == View.VISIBLE) {
                videoRefreshLayout.hideWithAnimation()
            }
            if (placeholderRefreshLayout.alpha != 1f) {
                placeholderRefreshLayout.revealWithAnimation()
                placeHolderRV.revealWithAnimation()
            }
        }

    }

    private fun showErrorLayout() {
        showErrorSnackbar(R.string.ant_server_error)
        if (noContentRefreshLayout.visibility == View.VISIBLE) noContentRefreshLayout.hideWithAnimation()
        placeholdersAdapter.setState(VideoPlaceholdersAdapter.LoadingState.ERROR)
        if (videoRefreshLayout.alpha != 1f) {
            if (placeholderRefreshLayout.alpha != 1f) {
                placeholderRefreshLayout.revealWithAnimation()
                placeHolderRV.revealWithAnimation()
            }
        }
    }

    private fun hidePlaceholder() {
        if (videoRefreshLayout.visibility != View.VISIBLE) {
            videoRefreshLayout.revealWithAnimation()
            videosRV.revealWithAnimation()
        }
        if (noContentRefreshLayout.visibility == View.VISIBLE) noContentRefreshLayout.hideWithAnimation()
        if (placeholderRefreshLayout.visibility == View.VISIBLE) placeholderRefreshLayout.hideWithAnimation()
    }

    private fun showEmptyListPlaceholder() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (noContentRefreshLayout.visibility != View.VISIBLE) {
                placeholderRefreshLayout.hideWithAnimation()
                videoRefreshLayout.hideWithAnimation()
                videosRV.onPause()
                noContentRefreshLayout.revealWithAnimation()
                noContentContainer.revealWithAnimation()
            }
        }, 310)
    }

    private fun showNoConnectionPlaceHolder() {
        showErrorSnackbar(R.string.ant_no_connection)
        placeholdersAdapter.setState(VideoPlaceholdersAdapter.LoadingState.NO_INTERNET)
        if (videoRefreshLayout.alpha != 1f && noContentRefreshLayout.alpha != 1f) {
            if (placeholderRefreshLayout.alpha != 1f) {
                placeholderRefreshLayout.revealWithAnimation()
                placeHolderRV.revealWithAnimation()
            }
        }
    }

    private fun triggerNewLiveButton(isVisible: Boolean, isPause: Boolean = false) {
        if (isVisible && !isNewLiveButtonShown) {
            isNewLiveButtonShown = true
            if (btnNewLive != null) {
                btnNewLive.visibility = View.VISIBLE
                btnNewLive.isEnabled = true
                btnNewLive.animate().translationYBy(150f).setDuration(300).start()
            }
        } else if (!isVisible) {
            if (!isPause) {
                newItemsList.clear()
            }
            if (isNewLiveButtonShown) {
                isNewLiveButtonShown = false
                btnNewLive.isEnabled = false
                if (btnNewLive != null) {
                    btnNewLive.animate().translationY(0f).setDuration(300)
                        .withEndAction { btnNewLive.visibility = View.GONE }.start()
                }
            }
        }
    }

    private fun checkIsLiveWasRemoved(newStreams: List<StreamResponse>) {
        val iterator = newItemsList.iterator()
        for (stream in iterator) {
            if (newStreams.none { it.id == stream.id }) {
                iterator.remove()
            }
        }
        if (newItemsList.isEmpty() && isNewLiveButtonShown) {
            triggerNewLiveButton(false)
        }
    }

    private fun checkIsNewItemAdded(
        newStreams: List<StreamResponse>,
        oldStreams: List<StreamResponse>
    ) {
        val newestItems = mutableListOf<StreamResponse>()
        //handle pagination not to trigger new button
        if (oldStreams.isNotEmpty() && newStreams.isNotEmpty() && viewModel.doNotTriggerNewButton) return
        if (!isInitialListSet && oldStreams.isNotEmpty()) {
            for (stream in newStreams) {
                if (oldStreams.none { it.id == stream.id }) {
                    if (videoAdapter.getStreams().isNotEmpty()) {
                        newestItems.add(stream)
                        newItemsList.add(stream)
                    }
                }
            }

            if (oldStreams.isNotEmpty() && newestItems.isNotEmpty()) {
                var sizeToCompare = newestItems.size
                if(oldStreams.none { it.id == -1 } && newestItems.any { it.id == -1 } && newestItems.size>1){
                    sizeToCompare = newestItems.size - 1
                }
                if (rvLayoutManager.findFirstCompletelyVisibleItemPosition() == sizeToCompare) {
                    newItemsList.clear()
                    if (isSnackBarScrollActive) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            scrollRvAndTriggerAutoplay()
                        }, 1500)
                    } else {
                        scrollRvAndTriggerAutoplay()
                    }
                } else if (oldStreams.isNotEmpty() && rvLayoutManager.findFirstVisibleItemPosition() > newStreams.indexOf(
                        newestItems[0]
                    )
                ) {
                    triggerNewLiveButton(true)
                }
            } else if (oldStreams.isNotEmpty() && newItemsList.isNotEmpty() && rvLayoutManager.findFirstVisibleItemPosition() > newStreams.indexOf(
                    newItemsList[0]
                )
            ) {
                triggerNewLiveButton(true)
            }
        }
    }

    private fun scrollRvAndTriggerAutoplay() {
        videosRV?.smoothScrollToPosition(0)
        Handler(Looper.getMainLooper()).postDelayed({
            videosRV?.forceRestartAutoPlayOnChange()
        }, 1000)
    }

    private fun openLiveFragment(stream: StreamResponse, isFromJoinChat: Boolean = false) {
        videosRV.isOpeningPlayer = true
        replaceFragment(
            PlayerFragment.newInstance(stream, isFromJoinChat),
            R.id.mainContent,
            addToBackStack = true,
            slideFromBottom = true
        )
    }

    private fun openVodFragment(stream: StreamResponse) {
        videosRV.isOpeningPlayer = true
        replaceFragment(
            VodPlayerFragment.newInstance(stream),
            R.id.mainContent,
            addToBackStack = true,
            slideFromBottom = true
        )
    }

    //region FeedInfo
    private fun loadFeedInfo() {
        if (viewModel.getSavedFeedImageUrl().isNullOrEmpty()) {
            viewModel.getFeedInfo()
        } else {
            loadSavedInfo()
        }
    }

    private fun loadProfileInfo() {
        if (UserCache.getInstance()?.getRefreshToken() != null) {
            if (UserCache.getInstance()?.getUserImageUrl().isNullOrEmpty()) {
                viewModel.getProfileInfo()
            } else {
                loadSavedProfileImage()
            }
        }
    }

    private fun loadSavedProfileImage() {
        Picasso.get()
            .load(UserCache.getInstance()?.getUserImageUrl())
            .placeholder(R.drawable.antourage_ic_incognito_user)
            .networkPolicy(NetworkPolicy.OFFLINE)
            .into(userBtn, object : Callback {
                override fun onSuccess() {
                    invalidateShadowView()
                    viewModel.getProfileInfo()
                }

                override fun onError(e: Exception?) {
                    viewModel.getProfileInfo()
                }
            })
    }

    private fun loadNewProfileImage() {
        if (!UserCache.getInstance()?.getUserImageUrl().isNullOrEmpty()) {
            Picasso.get()
                .load(UserCache.getInstance()?.getUserImageUrl())
                .placeholder(R.drawable.antourage_ic_incognito_user)
                .into(userBtn, object : Callback {
                    override fun onSuccess() {
                        invalidateShadowView()
                    }

                    override fun onError(e: Exception?) {
                        invalidateShadowView()
                    }
                })
        } else {
            loadDefaultUserImage()
        }
    }

    private fun loadDefaultUserImage() {
        Picasso.get()
            .load(R.drawable.antourage_ic_incognito_user)
            .placeholder(R.drawable.antourage_ic_incognito_user)
            .into(userBtn, object : Callback {
                override fun onSuccess() {
                    invalidateShadowView()
                }

                override fun onError(e: Exception?) {
                    invalidateShadowView()
                }
            })
    }

    private fun loadSavedInfo() {
        Picasso.get()
            .load(viewModel.getSavedFeedImageUrl())
            .networkPolicy(NetworkPolicy.OFFLINE)
            .into(ivTeamImage, object : Callback {
                override fun onSuccess() {
                    val vto: ViewTreeObserver = ivTeamImage.viewTreeObserver
                    vto.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            ivTeamImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            viewModel.getSavedTagLine()?.let {
                                setTitle(it)
                            }

                        }
                    })
                    viewModel.getFeedInfo()
                }

                override fun onError(e: Exception?) {
                    viewModel.getSavedTagLine()?.let {
                        setTitle(it)
                    }
                    viewModel.getFeedInfo()
                }
            })
    }

    private fun updateFeedInfo(feedInfo: FeedInfo) {
        if (feedInfo.imageUrl.isNullOrEmpty()) {
            ivTeamImage.setImageDrawable(null)
            setTitle(feedInfo.tagLine)
        } else {
            context?.let {
                Picasso
                    .get()
                    .load(feedInfo.imageUrl)
                    .into(ivTeamImage, object : Callback {
                        override fun onSuccess() {
                            val vto: ViewTreeObserver = ivTeamImage.viewTreeObserver
                            vto.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                                override fun onGlobalLayout() {
                                    ivTeamImage?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                                    setTitle(feedInfo.tagLine)
                                }
                            })
                        }

                        override fun onError(e: Exception?) {
                        }
                    })
            }
            context?.let {
                Picasso
                    .get()
                    .load(feedInfo.imageUrl)
                    .into(ivNoContent)
            }
        }
    }

    private fun setTitle(text: String?) {
        if (text.isNullOrEmpty()) {
            tvTitle.text = ""
        } else {
            tvTitle.text = text
        }
    }

    private fun invalidateShadowView() {
        if (UserCache.getInstance()?.getRefreshToken() == null || !UserCache.getInstance()
                ?.getUserImageUrl().isNullOrEmpty()
        ) {
            shadowView?.backgroundDrawable =
                ContextCompat.getDrawable(requireContext(), R.drawable.antourage_blue_shadow)
        } else {
            shadowView?.backgroundDrawable =
                ContextCompat.getDrawable(requireContext(), R.drawable.antourage_dark_shadow)
        }
    }

    private fun invalidateUserBtn() {
        invalidateShadowView()
        if (UserCache.getInstance()?.getRefreshToken() == null) {
            userBtn?.visibility = View.GONE
            loginBtn?.visibility = View.VISIBLE
        } else {
            userBtn?.visibility = View.VISIBLE
            loginBtn?.visibility = View.GONE
        }
    }
    //endregion
}
