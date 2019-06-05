package com.antourage.weavervideo

import android.os.Bundle
import android.support.multidex.MultiDex
import android.support.v7.app.AppCompatActivity
import com.antourage.weaverlib.screens.base.AntourageActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MultiDex.install(this)
        setContentView(R.layout.activity_main)
        //AntourageFabLifecycleObserver.registerLifecycle(lifecycle)
    }

    override fun onPause() {
        super.onPause()
        antfab.onPause()
    }

    override fun onResume() {
        super.onResume()
        antfab.onResume()
    }
}
