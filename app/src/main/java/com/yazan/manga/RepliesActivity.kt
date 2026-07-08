package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RepliesActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var replyInput: android.widget.EditText
    private lateinit var sendBtn: MaterialButton
    private var parentId: String = ""
    private var contextId: String = ""
    private var contextType: String = ""

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

        swipeRefresh.setOnRefreshListener { loadReplies(); swipeRefresh.isRefreshing = false }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        sendBtn.setOnClickListener {
            val text = replyInput.text.toString().trim()
            if (text.isNotEmpty()) sendReply(text)
        }

        loadReplies()
    }

    private fun sendReply(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }

        val cd = CommentsManager.checkCooldown(this)
        if (!cd.allowed) {
            Toast.makeText(this, "انتظر ${cd.secondsLeft} ثانية", Toast.LENGTH_SHORT).show()
            return
        }

        val error = CommentsManager.addComment(this, contextId, contextType, text, parentId)
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } else {
            replyInput.text.clear()
            loadReplies()
        }
    }

    private fun loadReplies() {
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                CommentsManager.getReplies(this@RepliesActivity, parentId)
            }
            if (replies.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.layoutManager = LinearLayoutManager(this@RepliesActivity)
                val user = AuthManager.getCurrentUser(this@RepliesActivity)
                recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val tv = TextView(parent.context).apply {
                            setPadding(32, 24, 32, 24)
                            setTextColor(getColor(R.color.white))
                            textSize = 13f
                        }
                        return object : RecyclerView.ViewHolder(tv) {}
                    }
                    override fun getItemCount() = replies.size
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val r = replies[position]
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        (holder.itemView as TextView).text = "${if (r.isAdmin) "👑 " else ""}${r.authorName}\n${r.text}\n👍 ${r.likes.size} | 👎 ${r.dislikes.size} | ${sdf.format(java.util.Date(r.createdAt))}"
                    }
                }
            }
        }
    }
}
