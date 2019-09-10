package com.antourage.weaverlib.screens.base.chat

import android.arch.lifecycle.Observer
import android.content.res.Configuration
import android.graphics.Color
import android.support.design.widget.NavigationView
import android.support.v4.widget.DrawerLayout
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import com.antourage.weaverlib.R
import com.antourage.weaverlib.other.models.Message
import com.antourage.weaverlib.other.ui.CustomDrawerLayout
import com.antourage.weaverlib.screens.base.chat.rv.ChatLayoutManager
import com.antourage.weaverlib.screens.base.chat.rv.MessagesAdapter
import com.antourage.weaverlib.screens.base.player.BasePlayerFragment
import kotlinx.android.synthetic.main.fragment_weaver_portrait.*

/**
 * handles Chat visibility
 */
abstract class ChatFragment<VM : ChatViewModel> : BasePlayerFragment<VM>(),
    CustomDrawerLayout.DrawerTouchListener {

    protected var isChatDismissed: Boolean = false

    private lateinit var rvMessages: RecyclerView
    private lateinit var drawerLayout: CustomDrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var llMessageWrapper: LinearLayout

    //region Observers
    private val messagesObserver: Observer<List<Message>> = Observer { list ->
        if (list != null) {
            (rvMessages.adapter as MessagesAdapter).setMessageList(list)
        }
    }

    //endregion

    override fun onDrawerTouch() {
        handleControlsVisibility()
    }

    override fun initUi(view: View?) {
        super.initUi(view)
        view?.apply {
            drawerLayout = findViewById(R.id.drawerLayout)
            drawerLayout.touchListener = this@ChatFragment
            navigationView = findViewById(R.id.navView)
            rvMessages = findViewById(R.id.rvMessages)
            llMessageWrapper = findViewById(R.id.ll_wrapper)
        }
        initMessagesRV()
        initNavigationView()
    }

    override fun subscribeToObservers() {
        viewModel.getMessagesLiveData().observe(this.viewLifecycleOwner, messagesObserver)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newOrientation = newConfig.orientation
        if (newOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            rvMessages.isVerticalFadingEdgeEnabled = true
            rvMessages.adapter =
                MessagesAdapter(listOf(), Configuration.ORIENTATION_LANDSCAPE)
            if (viewModel.getMessagesLiveData().value != null)
                (rvMessages.adapter as MessagesAdapter).setMessageList(viewModel.getMessagesLiveData().value!!)
            rvMessages.smoothScrollToPosition(rvMessages.adapter!!.itemCount)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        } else if (newOrientation == Configuration.ORIENTATION_PORTRAIT) {
            rvMessages.isVerticalFadingEdgeEnabled = false
            rvMessages.adapter =
                MessagesAdapter(listOf(), Configuration.ORIENTATION_PORTRAIT)
            (rvMessages.adapter as MessagesAdapter).setMessageList(viewModel.getMessagesLiveData().value!!)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
        }
    }

    private fun initNavigationView() {
        drawerLayout.apply {
            setScrimColor(Color.TRANSPARENT)
            openDrawer(navigationView)
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
        }
        navigationView.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (activity != null) {
                    val drawerToggle =
                        object : ActionBarDrawerToggle(activity, drawerLayout, null, 0, 0) {
                            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                                super.onDrawerSlide(drawerView, slideOffset)
                                val orientation = resources.configuration.orientation
                                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    llMessageWrapper.alpha = slideOffset
                                    if (slideOffset == 0.0f) {
                                        etMessage.isEnabled = false
                                        isChatDismissed = true
                                    }
                                    if (slideOffset == 1.0f) {
                                        etMessage.isEnabled = true
                                        isChatDismissed = false
                                    }
                                } else {
                                    drawerLayout.openDrawer(navigationView, false)
                                }
                            }
                        }
                    drawerLayout.addDrawerListener(drawerToggle)
                    navigationView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    private fun initMessagesRV() {
        val linearLayoutManager = ChatLayoutManager(context)
        linearLayoutManager.stackFromEnd = true
        rvMessages.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            layoutManager = linearLayoutManager
            adapter = MessagesAdapter(listOf(), Configuration.ORIENTATION_PORTRAIT)
        }
    }
}