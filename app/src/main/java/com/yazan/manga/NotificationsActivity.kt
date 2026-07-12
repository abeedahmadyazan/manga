package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.ApiClient
import com.yazan.manga.data.AuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private var isAdmin = false
    private var broadcasts: List<ApiClient.Broadcast> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)

        recyclerView = findViewById(R.id.notificationsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Check if user is admin
        val user = AuthManager.getCurrentUser(this)
        isAdmin = user?.isAdmin == true

        loadBroadcasts()
    }

    private fun loadBroadcasts() {
        loadingIndicator.visibility = View.VISIBLE
        emptyText.visibility = View.GONE

        Thread {
            try {
                broadcasts = if (isAdmin) {
                    ApiClient.getAdminBroadcasts()
                } else {
                    ApiClient.getBroadcasts()
                }

                runOnUiThread {
                    loadingIndicator.visibility = View.GONE
                    if (broadcasts.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                    } else {
                        recyclerView.adapter = NotificationsAdapter(broadcasts, isAdmin) { broadcast ->
                            showBroadcastDetail(broadcast)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingIndicator.visibility = View.GONE
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "تعذّر تحميل الإشعارات"
                }
            }
        }.start()
    }

    private fun showBroadcastDetail(broadcast: ApiClient.Broadcast) {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date(broadcast.createdAt))

        val sb = StringBuilder()
        sb.append(broadcast.message)
        sb.append("\n\n📅 $dateStr")

        if (broadcast.forceBlock) {
            sb.append("\n\n⚠️ هذه رسالة إلزامية")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(broadcast.title)
            .setMessage(sb.toString())
            .setPositiveButton("تم") { dialog, _ -> dialog.dismiss() }

        // Add link button if both linkText and linkUrl are present
        if (broadcast.linkText != null && broadcast.linkUrl != null) {
            builder.setNeutralButton(broadcast.linkText) { dialog, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("link", broadcast.linkUrl))
                Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        // Add delete button for admin
        if (isAdmin) {
            builder.setNegativeButton("حذف") { dialog, _ ->
                AlertDialog.Builder(this)
                    .setTitle("تأكيد الحذف")
                    .setMessage("هل تريد حذف هذا الإشعار؟")
                    .setPositiveButton("حذف") { _, _ ->
                        Thread {
                            val success = ApiClient.deleteBroadcast(broadcast.id)
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                                    loadBroadcasts()
                                } else {
                                    Toast.makeText(this, "تعذّر الحذف", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                dialog.dismiss()
            }
        }

        builder.show()
    }

    // =============================================================
    //  Adapter
    // =============================================================
    inner class NotificationsAdapter(
        private val items: List<ApiClient.Broadcast>,
        private val showDelete: Boolean,
        private val onClick: (ApiClient.Broadcast) -> Unit
    ) : RecyclerView.Adapter<NotificationsAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.notificationTitle)
            private val preview: TextView = v.findViewById(R.id.notificationPreview)
            private val date: TextView = v.findViewById(R.id.notificationDate)
            private val forceIcon: View = v.findViewById(R.id.iconForceBlock)
            private val deleteBtn: ImageButton = v.findViewById(R.id.btnDeleteNotification)

            fun bind(broadcast: ApiClient.Broadcast) {
                title.text = broadcast.title
                preview.text = broadcast.message
                val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                date.text = dateFormat.format(Date(broadcast.createdAt))

                forceIcon.visibility = if (broadcast.forceBlock) View.VISIBLE else View.GONE
                deleteBtn.visibility = if (showDelete) View.VISIBLE else View.GONE

                itemView.setOnClickListener { onClick(broadcast) }

                deleteBtn.setOnClickListener {
                    AlertDialog.Builder(itemView.context)
                        .setTitle("تأكيد الحذف")
                        .setMessage("هل تريد حذف هذا الإشعار؟")
                        .setPositiveButton("حذف") { _, _ ->
                            Thread {
                                val success = ApiClient.deleteBroadcast(broadcast.id)
                                (itemView.context as? NotificationsActivity)?.runOnUiThread {
                                    if (success) {
                                        Toast.makeText(itemView.context, "تم الحذف", Toast.LENGTH_SHORT).show()
                                        loadBroadcasts()
                                    } else {
                                        Toast.makeText(itemView.context, "تعذّر الحذف", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
        }
    }
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}