package app.clearspace.network.moderation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class ContentReporter(private val context: Context) {
    private val db = FirebaseFirestore.getInstance()

    suspend fun reportContent(contentHash: String, reason: String) {
        withContext(Dispatchers.IO) {
            try {
                val auth = FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
                // Add the report to "content_reports" for review (kept apart from the
                // global_blacklist/*/reports collection group that clients may read)
                val report = hashMapOf(
                    "contentHash" to contentHash,
                    "reason" to reason,
                    "timestamp" to System.currentTimeMillis()
                )
                db.collection("content_reports").add(report).await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
