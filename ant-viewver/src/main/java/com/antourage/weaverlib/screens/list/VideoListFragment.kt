package com.antourage.weaverlib.screens.list

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antourage.weaverlib.Global
import com.antourage.weaverlib.R
import com.antourage.weaverlib.UserCache
import com.antourage.weaverlib.di.injector
import com.antourage.weaverlib.other.betterSmoothScrollToPosition
import com.antourage.weaverlib.other.dp2px
import com.antourage.weaverlib.other.models.StreamResponse
import com.antourage.weaverlib.other.networking.ConnectionStateMonitor
import com.antourage.weaverlib.other.networking.NetworkConnectionState
import com.antourage.weaverlib.other.replaceFragment
import com.antourage.weaverlib.screens.base.BaseFragment
import com.antourage.weaverlib.screens.list.dev_settings.DevSettingsDialog
import com.antourage.weaverlib.screens.list.rv.VerticalSpaceItemDecorator
import com.antourage.weaverlib.screens.list.rv.VideosAdapter2
import com.antourage.weaverlib.screens.vod.VodPlayerFragment
import com.antourage.weaverlib.screens.weaver.PlayerFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.android.synthetic.main.fragment_videos_list2.*
import org.jetbrains.anko.backgroundColor


internal class VideoListFragment : BaseFragment<VideoListViewModel>() {

        override fun getLayoutId() = R.layout.fragment_videos_list2

    private lateinit var snackBarBehaviour: BottomSheetBehavior<View>
    private lateinit var videoAdapter: VideosAdapter2

    private lateinit var rvLayoutManager: LinearLayoutManager
    private var refreshVODs = true
    private var isLoadingMoreVideos = false
    private var isNewLiveButtonShown = false
    private var isInitialListSet = true
    private var newLivesList = mutableListOf<StreamResponse>()

    companion object {
        fun newInstance(): VideoListFragment {
            return VideoListFragment()
        }
    }

    //region Observers
    private val streamsObserver: Observer<List<StreamResponse>> = Observer { list ->
        list?.let { newStreams ->
            if (newStreams.isNullOrEmpty()) {
                showEmptyListPlaceholder()
            } else {
                isLoadingMoreVideos = false
                checkIsNewLiveAdded(newStreams)
                checkIsLiveWasRemoved(newStreams)
                if (videoRefreshLayout.isRefreshing) {
                    videoAdapter.setStreamListForceUpdate(newStreams)
                } else {
                    videoAdapter.setStreamList(newStreams)
                }
                isInitialListSet = false
                hidePlaceholder()
            }
        }
        videoRefreshLayout.isRefreshing = false
    }

    private val loaderObserver: Observer<Boolean> = Observer { show ->
        if (show == true) {
            showLoadingLayout()
        }
    }

    private val beChoiceObserver: Observer<Boolean> = Observer {
        if (it != null && it)
            context?.let { context -> DevSettingsDialog(context, viewModel).show() }
    }

    //endregion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity?.injector?.getVideoListViewModelFactory()?.let {
            viewModel = ViewModelProvider(this, it).get(VideoListViewModel::class.java)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        subscribeToObservers()
        initOnScrollListener()
    }

    private fun subscribeToObservers() {
        viewModel.listOfStreams.observe(this.viewLifecycleOwner, streamsObserver)
        viewModel.loaderLiveData.observe(this.viewLifecycleOwner, loaderObserver)
        viewModel.getShowBeDialog().observe(this.viewLifecycleOwner, beChoiceObserver)
        ConnectionStateMonitor.internetStateLiveData.observe(
            this.viewLifecycleOwner,
            networkStateObserver
        )
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onResume() {
        super.onResume()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        context?.let {
            viewModel.handleUserAuthorization()
            viewModel.refreshVODsLocally()
            if (!ConnectionStateMonitor.isNetworkAvailable(it) &&
                viewModel.listOfStreams.value.isNullOrEmpty()
            ) {
                showEmptyListPlaceholder()
            }

            if (ConnectionStateMonitor.isNetworkAvailable(it)) {
                resolveErrorSnackbar(R.string.ant_you_are_online)
            }
            videosRV.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
        videosRV.releasePlayer()
        videosRV.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videosRV.adapter = null
    }

    override fun initUi(view: View?) {
        initSnackbar()
        val onClick: (stream: StreamResponse) -> Unit = { streamResponse ->
            when {
                streamResponse.isLive -> {
                    val userId = context?.let { UserCache.getInstance(it)?.getUserId() } ?: -1
                    replaceFragment(
                        PlayerFragment.newInstance(streamResponse, userId),
                        R.id.mainContent,
                        true
                    )
                }
                streamResponse.id == -1 -> {
                    videosRV.betterSmoothScrollToPosition(0)
                }
                else -> {
                    context?.let { context ->
                        streamResponse.streamId?.let {
                            UserCache.getInstance(context)?.saveVideoToSeen(it)
                        }
                    }
                    replaceFragment(
                        VodPlayerFragment.newInstance(streamResponse),
                        R.id.mainContent,
                        true
                    )
                }
            }
        }

        btnNewLive.setOnClickListener { videosRV.betterSmoothScrollToPosition(0) }

        videoAdapter = VideosAdapter2(onClick, videosRV)

        initRecyclerView(videoAdapter, videosRV)

        rvLayoutManager = LinearLayoutManager(context)
        rvLayoutManager.orientation = LinearLayoutManager.VERTICAL
        rvLayoutManager.initialPrefetchItemCount = 4
        rvLayoutManager.isItemPrefetchEnabled = true

        videosRV.layoutManager = rvLayoutManager

        initOnScrollListener()

        videoRefreshLayout.setOnRefreshListener {
            context?.let {
                if (ConnectionStateMonitor.isNetworkAvailable(it)) {
                    if (viewModel.userAuthorized()) {
                        viewModel.refreshVODs(0, true)
                    } else {
                        videoRefreshLayout.isRefreshing = false
                        showWarningAlerter(resources.getString(R.string.invalid_toke_error_message))
                    }
                } else {
                    videoRefreshLayout.isRefreshing = false
                    showErrorSnackbar(R.string.ant_no_connection)
                }
            }
        }

//        placeHolderRV.layoutManager = LinearLayoutManager(context)

        ivClose.setOnClickListener { activity?.finish() }
        viewBEChoice.setOnClickListener { viewModel.onLogoPressed() }

        ReceivingVideosManager.setReceivingVideoCallback(viewModel)
        if (refreshVODs && viewModel.userAuthorized()) {
            viewModel.refreshVODs()
            refreshVODs = false
        }
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
                        videosRV.smoothScrollBy(0, (bottomSheet.height))
                        false
                    } else {
                        videosRV.smoothScrollBy(0, -(bottomSheet.height))
                        false
                    }
                    lastOffset = offset
                }
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_DRAGGING) {
                    //to disable user swipe
                    snackBarBehaviour.state = BottomSheetBehavior.STATE_EXPANDED;
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
        }
    }

    private fun resolveErrorSnackbar(messageId: Int) {
        if (snackBarBehaviour.state == BottomSheetBehavior.STATE_EXPANDED) {
            context?.resources?.getString(messageId)
                ?.let { messageToDisplay ->
                    snackBar.text = messageToDisplay
                    context?.let {
                        val colorFrom: Int = ContextCompat.getColor(it, R.color.ant_error_bg_color)
                        val colorTo: Int =
                            ContextCompat.getColor(it, R.color.ant_error_resolved_bg_color)
                        val duration = 1000L
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
            Handler().postDelayed({
                snackBarBehaviour.state = BottomSheetBehavior.STATE_COLLAPSED
            }, 2000)
        }
    }

    private fun initRecyclerView(
        adapter: RecyclerView.Adapter<out RecyclerView.ViewHolder>,
        recyclerView: RecyclerView
    ) {
        recyclerView.adapter = adapter
        val dividerItemDecoration = VerticalSpaceItemDecorator(
            dp2px(context!!, 32f).toInt()
        )
        recyclerView.addItemDecoration(dividerItemDecoration)
    }

    private fun initOnScrollListener() {
        videosRV.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (isNewLiveButtonShown) {
                    if (rvLayoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                        triggerNewLiveButton(false)
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!recyclerView.canScrollVertically(1) && newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val total = rvLayoutManager.itemCount
                    val lastVisibleItem = rvLayoutManager.findLastCompletelyVisibleItemPosition()
                    if (!isLoadingMoreVideos && total <= lastVisibleItem + 1 && videoAdapter.getStreams()[lastVisibleItem].id == -2) {
                        viewModel.refreshVODs(noLoadingPlaceholder = true)
                        isLoadingMoreVideos = true
                        Log.d("REFRESH_VODS", "onBottomReached")
                    }
                }
            }
        })
    }

    private fun showLoadingLayout() {
        showEmptyListPlaceholder()
//        placeHolderHandler.postDelayed({
//            videosRV.visibility = View.INVISIBLE
//            placeHolderAdapter.setItems(
//                arrayListOf(
//                    R.color.ant_no_content_placeholder_color_3
//                )
//            )
//            placeHolderRV.alpha = 0f
//            placeHolderRV.visibility = View.VISIBLE
//            placeHolderRV.animate().alpha(1f).setDuration(300).start()
//        }, 300)
    }

    private fun hidePlaceholder() {
        viewNoContentContainer?.animate()?.alpha(0f)
            ?.withEndAction { viewNoContentContainer?.visibility = View.INVISIBLE }
            ?.setDuration(300)
            ?.start()
//        tvNoContent.visibility = View.INVISIBLE
//        placeHolderRV.clearAnimation()
//        placeHolderRV.animate().alpha(0f).setDuration(300)
//            .withEndAction { placeHolderRV.visibility = View.INVISIBLE }.start()
        videosRV.visibility = View.VISIBLE
    }

    private fun showEmptyListPlaceholder() {
//        placeHolderRV.clearAnimation()
//        placeHolderRV.animate().alpha(0f).setDuration(300)
//            .withEndAction { placeHolderRV.visibility = View.INVISIBLE }.start()
        viewNoContentContainer?.alpha = 0f
        viewNoContentContainer?.visibility = View.VISIBLE
        viewNoContentContainer?.animate()?.alpha(1f)?.setDuration(300)?.start()
    }

    private fun triggerNewLiveButton(isVisible: Boolean) {
        if (isVisible && !isNewLiveButtonShown) {
            isNewLiveButtonShown = true
            btnNewLive.animate().translationYBy(150f).setDuration(300).start()
        } else if (!isVisible && isNewLiveButtonShown) {
            isNewLiveButtonShown = false
            btnNewLive.animate().translationY(0f).setDuration(300).start()
        }
    }

    private fun checkIsLiveWasRemoved(newStreams: List<StreamResponse>) {
        val iterator = newLivesList.iterator()
        for (stream in iterator) {
            if (newStreams.none() { it.id == stream.id }) {
                iterator.remove()
            }
        }
        if (newLivesList.isEmpty()) {
            triggerNewLiveButton(false)
        }
    }

    private fun checkIsNewLiveAdded(newStreams: List<StreamResponse>) {
        if (!isInitialListSet) {
            for (stream in newStreams) {
                if (stream.isLive && videoAdapter.getStreams().none { it.id == stream.id }) {
                    newLivesList.add(stream)
                    triggerNewLiveButton(true)
                    break
                }
            }
        }
    }

    private val networkStateObserver: Observer<NetworkConnectionState> = Observer { networkState ->
        when (networkState?.ordinal) {
            NetworkConnectionState.LOST.ordinal -> {
                if (!Global.networkAvailable) {
                    showErrorSnackbar(R.string.ant_no_connection)
                }
            }
            NetworkConnectionState.AVAILABLE.ordinal -> {
                resolveErrorSnackbar(R.string.ant_you_are_online)
                viewModel.onNetworkGained()
            }
        }
    }
}
