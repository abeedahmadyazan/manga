package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.ReportsManager

class AdminPanelActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_panel)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val user = AuthManager.getCurrentUser(this)
        if (user?.isAdmin != true) {
            finish()
            return
        }

        loadReports()
        loadSuspendedUsers()
    }

    private fun loadReports() {
        val reports = ReportsManager.getPendingReports(this)
        val recyclerView = findViewById<RecyclerView>(R.id.reportsRecyclerView)
        val emptyText = findViewById<TextView>(R.id.reportsEmpty)

        if (reports.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    setPadding(32, 32, 32, 32)
                    setTextColor(getColor(R.color.white))
                    textSize = 12f
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun getItemCount() = reports.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val r = reports[position]
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                (holder.itemView as TextView).text = "🚨 بلاغ: ${r.reason}\n📝 التعليق: ${r.commentText.take(50)}\n👤 بواسطة: ${r.reportedByName}\n⏰ ${sdf.format(java.util.Date(r.reportedAt))}"
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
}
