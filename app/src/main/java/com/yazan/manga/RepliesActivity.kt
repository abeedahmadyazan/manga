package com.yazan.manga

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CloudCommentsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepliesActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var replyInput: EditText
    private lateinit var sendBtn: MaterialButton
    
    private var parentId: String = ""
    private var contextId: String = ""
    private var contextType: String = ""
    private var allReplies: List<CloudCommentsManager.Comment> = emptyList()
    private var listener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replies)

        parentId = intent.getStringExtra("parent_id") ?: ""
        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"

        val parentAuthor = intent.getStringExtra("parent_author") ?: "تعليق"
        findViewById<TextView>(R.id.repliesTitle).text = "💬 ردود على: $parentAuthor"

        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.repliesRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        replyInput = findViewById(R.id.replyInput)
        sendBtn = findViewById(R.id.btnSendReply)
        
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        swipeRefresh.setOnRefreshListener { swipeRefresh.isRefreshing = false }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        sendBtn.setOnClickListener {
            val text = replyInput.text.toString().trim()
            if (text.isNotEmpty()) sendReply(text)
        }

        startListening()
    }

    private fun startListening() {
        swipeRefresh.isRefreshing = true
        listener = CloudCommentsManager.listenToComments(
            contextId = contextId,
            onUpdate = { comments ->
                swipeRefresh.isRefreshing = false
                allReplies = comments.filter { it.parentId == parentId }
                renderReplies()
            },
            onError = { e ->
                swipeRefresh.isRefreshing = false
                Toast.makeText(this, "تعذّر تحميل الردود", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun sendReply(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) { Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show(); return }

        sendBtn.isEnabled = false
        CloudCommentsManager.addComment(this, contextId, contextType, text, parentId) { success, error ->
            sendBtn.isEnabled = true
            if (success) {
                replyInput.text.clear()
                Toast.makeText(this, "تم إرسال الرد", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "حدث خطأ", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderReplies() {
        if (allReplies.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.layoutManager = LinearLayoutManager(this)

        val user = AuthManager.getCurrentUser(this)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun getItemCount() = allReplies.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val r = allReplies[position]
                val avatar = holder.itemView.findViewById<TextView>(R.id.commentAvatar)
                val avatarImg = holder.itemView.findViewById<android.widget.ImageView>(R.id.commentAvatarImage)
                val adminBadge = holder.itemView.findViewById<TextView>(R.id.commentAdminBadge)
                val author = holder.itemView.findViewById<TextView>(R.id.commentAuthor)
                val time = holder.itemView.findViewById<TextView>(R.id.commentTime)
                val text = holder.itemView.findViewById<TextView>(R.id.commentText)
                val btnLike = holder.itemView.findViewById<TextView>(R.id.btnLike)
                val btnDislike = holder.itemView.findViewById<TextView>(R.id.btnDislike)
                val btnReply = holder.itemView.findViewById<TextView>(R.id.btnReply)
                val btnDelete = holder.itemView.findViewById<TextView>(R.id.btnDelete)
                val btnReport = holder.itemView.findViewById<TextView>(R.id.btnReport)

                avatar.text = r.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                avatar.visibility = View.VISIBLE
                avatarImg.visibility = View.GONE
                // Admin: name in green pill; non-admin: plain text
                if (r.isAdmin) {
                    adminBadge.text = r.authorName
                    adminBadge.visibility = View.VISIBLE
                    author.visibility = View.GONE
                } else {
                    adminBadge.visibility = View.GONE
                    author.visibility = View.VISIBLE
                    author.text = r.authorName
                }
                time.text = com.yazan.manga.data.relativeTime(r.createdAt)
                text.text = r.text
                btnLike.text = "👍 ${r.likes.size}"
                btnDislike.text = "👎 ${r.dislikes.size}"
                btnReply.visibility = View.GONE

                // Fetch the latest name + avatar from the cloud
                AuthManager.fetchCloudUser(r.authorEmail) { cu ->
                    runOnUiThread {
                        if (cu != null) {
                            // Update the name in the correct place (pill for admin, plain for others)
                            if (cu.name.isNotEmpty() && cu.name != r.authorName) {
                                if (r.isAdmin) {
                                    adminBadge.text = cu.name
                                } else {
                                    author.text = cu.name
                                }
                                avatar.text = cu.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            }
                            if (cu.avatarBase64.isNotEmpty()) {
                                try {
                                    val bytes = android.util.Base64.decode(cu.avatarBase64, android.util.Base64.NO_WRAP)
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bmp != null) {
                                        avatar.visibility = View.GONE
                                        avatarImg.visibility = View.VISIBLE
                                        // Circular avatar via Glide
                                        com.bumptech.glide.Glide.with(this@RepliesActivity)
                                            .load(bmp)
                                            .circleCrop()
                                            .into(avatarImg)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                val isOwner = user?.email == r.authorEmail
                val canDelete = isOwner || (user?.isAdmin == true)
                btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
                btnReport.visibility = if (user != null && !isOwner && !r.isAdmin) View.VISIBLE else View.GONE

                btnLike.setOnClickListener {
                    if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                        user?.let { CloudCommentsManager.toggleLike(r.id, it.email, true) {} }
                    } else {
                        Toast.makeText(this@RepliesActivity, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                    }
                }
                btnDislike.setOnClickListener {
                    if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                        user?.let { CloudCommentsManager.toggleLike(r.id, it.email, false) {} }
                    } else {
                        Toast.makeText(this@RepliesActivity, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                    }
                }
                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this@RepliesActivity)
                        .setTitle("حذف الرد").setMessage("هل تريد الحذف؟")
                        .setPositiveButton("حذف") { _, _ ->
                            CloudCommentsManager.deleteComment(r.id) { success ->
                                runOnUiThread {
                                    if (success) {
                                        Toast.makeText(this@RepliesActivity, "تم الحذف", Toast.LENGTH_SHORT).show()
                                        // Remove locally as a fallback; the listener will also refresh
                                        allReplies = allReplies.filterNot { it.id == r.id }
                                        renderReplies()
                                    } else {
                                        Toast.makeText(this@RepliesActivity, "فشل الحذف", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton("إلغاء", null).show()
                }
                btnReport.setOnClickListener {
                    val reasons = arrayOf("محتوى مسيء أو غير لائق", "إهانة أو تحرش", "سبام أو تكرار", "مخالفة أخرى")
                    val checked = intArrayOf(0)
                    AlertDialog.Builder(this@RepliesActivity)
                        .setTitle("الإبلاغ عن رد ${r.authorName}")
                        .setSingleChoiceItems(reasons, checked[0]) { _, which -> checked[0] = which }
                        .setPositiveButton("إرسال البلاغ") { _, _ ->
                            CloudCommentsManager.reportComment(this@RepliesActivity, r, "رد", reasons[checked[0]]) { success, error ->
                                runOnUiThread {
                                    if (success) Toast.makeText(this@RepliesActivity, "تم إرسال البلاغ", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(this@RepliesActivity, "حدث خطأ", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton("إلغاء", null).show()
                }

                author.setOnClickListener { openUserProfile(r.authorEmail) }
                avatar.setOnClickListener { openUserProfile(r.authorEmail) }
                avatarImg.setOnClickListener { openUserProfile(r.authorEmail) }
            }
        }
    }

    private fun openUserProfile(email: String) {
        val intent = android.content.Intent(this, UserProfileActivity::class.java)
        intent.putExtra("user_email", email)
        startActivity(intent)
    }

    override fun onDestroy() { super.onDestroy(); listener?.remove() }
}
