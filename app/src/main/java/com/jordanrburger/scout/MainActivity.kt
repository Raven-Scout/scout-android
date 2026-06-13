package com.jordanrburger.scout

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jordanrburger.scout.domain.PairingUri
import com.jordanrburger.scout.ui.nav.ScoutNavHost
import com.jordanrburger.scout.ui.theme.ScoutTheme

class MainActivity : ComponentActivity() {

    // scout://action-items/{prefix} deep link (FCM taps land here, spec §5)
    private var highlightPrefix by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            ScoutTheme {
                ScoutNavHost(highlightPrefix = highlightPrefix)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** scout://pair?... — scanned with a stock camera app, the link itself
     *  pairs the device; scout://action-items/{prefix} highlights an item. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        when (data.host) {
            "pair" -> {
                val config = PairingUri.parse(data.toString())
                if (config != null) {
                    val settings = ScoutApp.container(this).settings
                    settings.setBridgeUrl(config.baseUrl)
                    settings.setSecret(config.secret)
                    Toast.makeText(this, "Paired with ${config.baseUrl}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Invalid Scout pairing link", Toast.LENGTH_LONG).show()
                }
            }
            "action-items" -> highlightPrefix = data.lastPathSegment
        }
    }
}
