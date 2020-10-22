package com.antourage.weavervideo

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.multidex.MultiDex
import com.antourage.weaverlib.ui.fab.AntourageFab
import com.antourage.weaverlib.ui.fab.RegisterPushNotificationsResult
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessaging
import com.squareup.picasso.Picasso
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException


class MainActivity : AppCompatActivity() {

    //    lateinit var antfab: AntourageFab
    private lateinit var connectivityManager: ConnectivityManager

    companion object {
        const val TAG = "Antourage_testing_tag"
        const val TEST_API_KEY = "A5F76EE9-BC76-4F76-A042-933B8993FC2C"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MultiDex.install(this)
        setContentView(R.layout.activity_main)

        Picasso.get().load(R.drawable.hacken_header).into(header)
        Picasso.get().load(R.drawable.hacken_header_overlay).into(header_overlay)
        Picasso.get().load(R.drawable.hacken_footer).into(footer)
        Picasso.get().load(R.drawable.hacken_background).into(mainContent)

        connectivityManager =
            this@MainActivity.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

//        /** To add widget programatically*/
//        antfab = AntourageFab(this)
//        if(antfab.parent == null){
//            antfab.setLocale("sv")
//            antfab.setPosition("bottomRight")
//            antfab.showFab(this)
//            antfab.setMargins(0, 0)
//        }

        //region Antourage authorization
        AntourageFab.authWith(TEST_API_KEY.toUpperCase(), context = this)
        //endregion

        //region Antourage push notification subscription
        var fcmToken: String? = ""
        Thread {
            try {
                fcmToken =
                    FirebaseInstanceId.getInstance().getToken(getString(R.string.SENDER_ID), "FCM")
            } catch (e: IOException) {
                e.printStackTrace()
            }
            runOnUiThread {
                AntourageFab.registerNotifications(fcmToken) { subscriptionResult ->
                    //Handle subscription result
                    when (subscriptionResult) {
                        //If result is successful, subscribe to the topic with
                        //topic name from result.
                        is RegisterPushNotificationsResult.Success -> {
                            Log.d(
                                TAG,
                                "Subscribed successfully; Topic name= ${subscriptionResult.topicName}"
                            )
                            FirebaseMessaging.getInstance()
                                .subscribeToTopic(subscriptionResult.topicName)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        Log.d(TAG, "Subscribed successfully!")
                                    } else {
                                        Log.d(TAG, "Subscription failed(")
                                    }
                                }
                        }
                        is RegisterPushNotificationsResult.Failure -> {
                            Log.d(
                                TAG,
                                "Subscription failed: ${subscriptionResult.cause}"
                            )
                        }
                    }
                }
            }
        }.start()
        //endregion
    }

    override fun onResume() {
        super.onResume()
        antfab.onResume()
    }

    override fun onPause() {
        super.onPause()
        antfab.onPause()
    }
}
