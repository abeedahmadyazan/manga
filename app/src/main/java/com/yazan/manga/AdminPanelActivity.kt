package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CloudCommentsManager

class AdminPanelActivity : AppCompatActivity() {

    private lateinit var reportsRecyclerView: RecyclerView
    private lateinit var reportsEmpty: TextView
    private var reportsListener: ListenerRegistration? = null
    private var reportsList: List<CloudCommentsManager.Report> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_panel)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val user = AuthManager.getCurrentUser(this)
        if (user?.isAdmin != true) {
            finish()
            return
        }

        reportsRecyclerView = findViewById(R.id.reportsRecyclerView)
        reportsEmpty = findViewById(R.id.reportsEmpty)
        reportsRecyclerView.layoutManager = LinearLayoutManager(this)

        loadSuspendedUsers()
        startListeningToReports()
    }

    private fun startListeningToReports() {
        reportsListener = CloudCommentsManager.listenToReports(
            onUpdate = { reports ->
                reportsList = reports
                renderReports()
            },
            onError = { e ->
                runOnUiThread {
                    Toast.makeText(this, "تعذّر تحميل البلاغات", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun renderReports() {
        if (reportsList.isEmpty()) {
            reportsEmpty.visibility = View.VISIBLE
            reportsRecyclerView.visibility = View.GONE
            return
        }

        reportsEmpty.visibility = View.GONE
        reportsRecyclerView.visibility = View.VISIBLE
        reportsRecyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val container = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 24)
                    setBackgroundResource(R.color.surface)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 16 }
                }
                return object : RecyclerView.ViewHolder(container) {}
            }

            override fun getItemCount() = reportsList.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val r = reportsList[position]
                val container = holder.itemView as LinearLayout
                container.removeAllViews()

                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

                val info = TextView(this@AdminPanelActivity).apply {
                    text = buildString {
                        append("🚨 السبب: ${r.reason}\n")
                        append("📝 التعليق: ${r.commentText.take(60)}${if (r.commentText.length > 60) "..." else ""}\n")
                        append("👤 المُبلَّغ عنه: ${r.reportedName} (${r.reportedEmail})\n")
                        append("📢 بلّغ: ${r.reportedByName}\n")
                        append("⏰ ${sdf.format(java.util.Date(r.createdAt))}")
                    }
                    setTextColor(getColor(R.color.white))
                    textSize = 12f
                }
                container.addView(info)

                val buttons = LinearLayout(this@AdminPanelActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 24, 0, 0)
                }

                val btnDeleteComment = TextView(this@AdminPanelActivity).apply {
                    text = "🗑️ حذف التعليق"
                    setTextColor(getColor(R.color.danger))
                    setPadding(24, 12, 24, 12)
                    setOnClickListener {
                        AlertDialog.Builder(this@AdminPanelActivity)
                            .setTitle("حذف التعليق")
                            .setMessage("حذف التعليق وحل البلاغ؟")
                            .setPositiveButton("حذف") { _, _ ->
                                val admin = AuthManager.getCurrentUser(this@AdminPanelActivity)
                                CloudCommentsManager.resolveReport(r.id, true, r.commentId) {}
                            }
                            .setNegativeButton("إلغاء", null).show()
                    }
                }

                val btnDismiss = TextView(this@AdminPanelActivity).apply {
                    text = "✓ تجاهل"
                    setTextColor(getColor(R.color.primary))
                    setPadding(48, 12, 24, 12)
                    setOnClickListener {
                        AlertDialog.Builder(this@AdminPanelActivity)
                            .setTitle("تجاهل البلاغ")
                            .setMessage("تجاهل هذا البلاغ (بدون حذف التعليق)؟")
                            .setPositiveButton("تجاهل") { _, _ ->
                                CloudCommentsManager.deleteReport(r.id) {}
                            }
                            .setNegativeButton("إلغاء", null).show()
                    }
                }

                buttons.addView(btnDeleteComment)
                buttons.addView(btnDismiss)
                container.addView(buttons)
            }
        }
    }

    private fun loadSuspendedUsers() {
        val users = AuthManager.getAllUsers(this)
        val suspended = users.filter { user ->
            val (suspended, _) = AuthManager.isUserSuspended(this, user.email)
            suspended
        }
        val textView = findViewById<TextView>(R.id.suspendedUsersText)
        if (suspended.isEmpty()) {
            textView.text = "لا يوجد مستخدمون موقوفون"
        } else {
            textView.text = suspended.joinToString("\n\n") { "📧 ${it.email}\n👤 ${it.username}" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reportsListener?.remove()
    }
}
