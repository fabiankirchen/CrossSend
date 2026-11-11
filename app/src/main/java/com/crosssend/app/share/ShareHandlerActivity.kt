package com.crosssend.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import kotlinx.coroutines.launch

class ShareHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.action
        val type = intent.type ?: ""

        if (Intent.ACTION_SEND == action || Intent.ACTION_SEND_MULTIPLE == action || Intent.ACTION_VIEW == action) {
            val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)
                ?: intent.getStringExtra("subject")
                ?: intent.clipData?.description?.label)?.toString()?.trim()

            val sharedText = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT))?.toString()

            val multiUris = if (Intent.ACTION_SEND_MULTIPLE == action) {
                val streamList = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                ).orEmpty()
                val collected = streamList.ifEmpty {
                    val clip = intent.clipData
                    if (clip != null) {
                        (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
                    } else {
                        emptyList()
                    }
                }
                collected.distinct()
            } else {
                emptyList()
            }

            if (multiUris.isNotEmpty()) {
                val safeUris = multiUris.filter {
                    com.crosssend.app.converter.DocumentProcessor.isSafeSharedUri(this, it)
                }
                if (safeUris.isEmpty()) {
                    android.util.Log.w("ShareHandler", "Blocked all unsafe shared URIs in ACTION_SEND_MULTIPLE")
                    Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }

                android.util.Log.i("ShareHandler", "Received batch share ($action): subject='$subject', type='$type', count=${safeUris.size}")
                Toast.makeText(this, getString(R.string.share_receiving_content), Toast.LENGTH_SHORT).show()

                val app = CrossSendApp.instance
                app.applicationScope.launch {
                    for (uri in safeUris) {
                        app.documentProcessor.handleShare(subject, sharedText, uri, type)
                    }
                }
            } else {
                val streamUri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.data
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

                if (streamUri != null && !com.crosssend.app.converter.DocumentProcessor.isSafeSharedUri(this, streamUri)) {
                    android.util.Log.w("ShareHandler", "Blocked unsafe shared URI: $streamUri")
                    Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }

                android.util.Log.i("ShareHandler", "Received share/view ($action): subject='$subject', type='$type', uri='$streamUri', textLen=${sharedText?.length ?: 0}")
                Toast.makeText(this, getString(R.string.share_receiving_content), Toast.LENGTH_SHORT).show()

                val app = CrossSendApp.instance
                app.applicationScope.launch {
                    app.documentProcessor.handleShare(subject, sharedText, streamUri, type)
                }
            }
        }

        // Finish immediately so the user is never blocked and stays in their current app!
        finish()
    }
}
