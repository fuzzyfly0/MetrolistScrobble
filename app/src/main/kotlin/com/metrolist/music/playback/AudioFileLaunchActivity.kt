/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.metrolist.music.MainActivity

/**
 * Android's Bluetooth stack only offers a media browser to car stereos (AVRCP browsing) when the
 * same app can also open audio files. This activity provides that audio file handler and simply
 * opens the app, since Metrolist does not play local files.
 */
class AudioFileLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
