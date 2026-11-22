package com.crosssend.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crosssend.app.CrossSendApp
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

class RssSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            syncAllFeeds(CrossSendApp.instance)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SafeLogger.e(TAG, "RSS background sync worker encountered an error", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RssSyncWorker"
        private const val PREFS_NAME = "crosssend_prefs"
        private const val KEY_DISABLED_AUTO_SYNC_IDS = "feeds_disabled_auto_sync_ids"

        suspend fun syncFeed(app: CrossSendApp, feed: FeedEntity) {
            val parsedFeed = app.rssFeedParser.fetchFeed(feed.url)
            val entities = parsedFeed.items.map { item ->
                FeedArticleEntity.fromRssItem(feed.id, item)
            }
            app.database.feedDao().upsertAndPruneFeed(feed.id, entities, System.currentTimeMillis(), 90)
        }

        suspend fun syncAllFeeds(app: CrossSendApp) {
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val disabledIds = prefs.getStringSet(KEY_DISABLED_AUTO_SYNC_IDS, emptySet())
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet()

            val feeds = app.database.feedDao().getAllFeedsList()
                .filterNot { it.id in disabledIds }
            val semaphore = Semaphore(3)
            coroutineScope {
                feeds.map { feed ->
                    async {
                        semaphore.withPermit {
                            try {
                                syncFeed(app, feed)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                SafeLogger.e(TAG, "Failed to sync feed (${SafeLogger.redactUrl(feed.url)})", e)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<RssSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "rss_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
