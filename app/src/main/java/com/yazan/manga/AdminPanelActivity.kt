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

        // Add broadcast button
        val btnBroadcast = findViewById<android.widget.Button?>(R.id.btnBroadcast)
        btnBroadcast?.setOnClickListener { showBroadcastDialog() }

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

    // =============================================================
    //  Broadcast management (admin notifications)
    // =============================================================

    private fun showBroadcastDialog() {
        val titleInput = android.widget.EditText(this).apply {
            hint = "عنوان الرسالة"
            setPadding(40, 24, 40, 24)
        }

        val input = android.widget.EditText(this).apply {
            hint = "اكتب نص الرسالة..."
            setPadding(40, 24, 40, 24)
            minLines = 3
        }

        val linkTextInput = android.widget.EditText(this).apply {
            hint = "نص الرابط (اختياري)"
            setPadding(40, 24, 40, 24)
        }

        val linkUrlInput = android.widget.EditText(this).apply {
            hint = "الرابط (اختياري)"
            setPadding(40, 24, 40, 24)
        }

        val forceBlockCheckbox = android.widget.CheckBox(this).apply {
            text = "رسالة إلزامية (تظهر مرة وحدة، تختفي بالتحديث)"
            setPadding(20, 16, 20, 16)
        }

        val allVersionsCheckbox = android.widget.CheckBox(this).apply {
            text = "إظهار لكل النسخ (مش مرتبطة بإصدار معين)"
            setPadding(20, 16, 20, 16)
        }

        // Auto-detect current app version — use as default target
        val currentVersion = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pInfo.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pInfo.versionCode
        } catch (e: Exception) { 1 }

        val versionInput = android.widget.EditText(this).apply {
            hint = "رقم الإصدار (افتراضي: $currentVersion)"
            setPadding(40, 24, 40, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val versionLabel = android.widget.TextView(this).apply {
            text = "الإصدار المستهدف (افتراضياً إصدارك الحالي: $currentVersion):"
            setPadding(20, 8, 20, 4)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#aaaaaa"))
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            addView(titleInput)
            addView(input)
            addView(linkTextInput)
            addView(linkUrlInput)
            addView(forceBlockCheckbox)
            addView(allVersionsCheckbox)
            addView(versionLabel)
            addView(versionInput)
        }

        AlertDialog.Builder(this)
            .setTitle("إرسال رسالة للمستخدمين")
            .setView(layout)
            .setPositiveButton("إرسال") { _, _ ->
                val title = titleInput.text.toString().trim()
                val message = input.text.toString().trim()
                val linkText = linkTextInput.text.toString().trim().ifEmpty { null }
                val linkUrl = linkUrlInput.text.toString().trim().ifEmpty { null }
                val forceBlock = forceBlockCheckbox.isChecked
                val allVersions = allVersionsCheckbox.isChecked
                // If no version entered and not all versions → use current app version
                val targetVersion = if (allVersions) -1
                    else versionInput.text.toString().trim().toIntOrNull() ?: currentVersion

                if (title.isEmpty() || message.isEmpty()) {
                    Toast.makeText(this, "العنوان والنص مطلوبان", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                Thread {
                    val success = com.yazan.manga.data.ApiClient.sendBroadcast(
                        title, message, linkText, linkUrl, forceBlock, allVersions, targetVersion
                    )
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "تم إرسال الرسالة بنجاح!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "تعذّر إرسال الرسالة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        reportsListener?.remove()
    }
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}