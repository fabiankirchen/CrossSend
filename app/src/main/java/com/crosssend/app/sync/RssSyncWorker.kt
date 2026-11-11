package com.crosssend.app.sync

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CancellationException
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
            Log.e(TAG, "RSS background sync worker encountered an error", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RssSyncWorker"

        suspend fun syncFeed(app: CrossSendApp, feed: FeedEntity) {
            val parsedFeed = app.rssFeedParser.fetchFeed(feed.url)
            val entities = parsedFeed.items.map { item ->
                FeedArticleEntity.fromRssItem(feed.id, item)
            }
            app.database.feedDao().upsertAndPruneFeed(feed.id, entities, System.currentTimeMillis(), 90)
        }

        suspend fun syncAllFeeds(app: CrossSendApp) {
            val feeds = app.database.feedDao().getAllFeedsList()
            for (feed in feeds) {
                try {
                    syncFeed(app, feed)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to sync feed ${feed.title} (${feed.url})", e)
                }
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
