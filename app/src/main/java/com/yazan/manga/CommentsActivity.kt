package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager
import com.yazan.manga.ui.CommentsAdapter

class CommentsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var commentInput: EditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var commentsTitle: TextView
    private lateinit var commentsCount: TextView
    private lateinit var replyBanner: View
    private lateinit var replyLabel: TextView
    private lateinit var btnCancelReply: ImageButton

    private lateinit var btnSortNewest: MaterialButton
    private lateinit var btnSortOldest: MaterialButton
    private lateinit var btnSortLikes: MaterialButton

    private lateinit var adapter: CommentsAdapter

    private var contextId: String = ""
    private var contextType: String = "manga"
    private var contextTitle: String = ""
    private var currentSort: String = "newest"
    private var currentComments: MutableList<CommentsManager.Comment> = mutableListOf()

    /** Parent comment for the next posted comment (null = top-level). */
    private var replyParent: CommentsManager.Comment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)

        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"
        contextTitle = intent.getStringExtra("context_title") ?: "تعليقات"

        initViews()
        loadComments()
    }

    private fun initViews() {
        commentsTitle = findViewById(R.id.commentsTitle)
        commentsCount = findViewById(R.id.commentsCount)
        recyclerView = findViewById(R.id.commentsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        commentInput = findViewById(R.id.commentInput)
        sendBtn = findViewById(R.id.btnSendComment)
        replyBanner = findViewById(R.id.replyBanner)
        replyLabel = findViewById(R.id.replyLabel)
        btnCancelReply = findViewById(R.id.btnCancelReply)

        btnSortNewest = findViewById(R.id.btnSortNewest)
        btnSortOldest = findViewById(R.id.btnSortOldest)
        btnSortLikes = findViewById(R.id.btnSortLikes)

        commentsTitle.text = "💬 $contextTitle"

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Send button
        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "اكتب نصاً أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendComment(text)
        }

        // Cancel reply
        btnCancelReply.setOnClickListener {
            replyParent = null
            replyBanner.visibility = View.GONE
            commentInput.hint = "اكتب تعليقك..."
        }

        // Sort buttons
        btnSortNewest.setOnClickListener { switchSort("newest") }
        btnSortOldest.setOnClickListener { switchSort("oldest") }
        btnSortLikes.setOnClickListener { switchSort("likes") }
        updateSortStyles()

        // Adapter
        adapter = CommentsAdapter(
            context = this,
            onLike = { c -> handleLike(c) },
            onDislike = { c -> handleDislike(c) },
            onReply = { c -> startReply(c) },
            onEdit = { c -> showEditDialog(c) },
            onDelete = { c -> showDeleteDialog(c) },
            onBan = { c -> showBanDialog(c) },
            onProfileClick = { email -> openUserProfile(email) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun switchSort(sort: String) {
        if (currentSort == sort) return
        currentSort = sort
        updateSortStyles()
        renderComments()
    }

    private fun updateSortStyles() {
        val tabs = listOf(
            "newest" to btnSortNewest,
            "oldest" to btnSortOldest,
            "likes"  to btnSortLikes
        )
        tabs.forEach { (key, btn) ->
            if (key == currentSort) {
                btn.background = getDrawable(R.drawable.bg_tab_active)
                btn.setTextColor(getColor(R.color.white))
            } else {
                btn.background = getDrawable(R.drawable.bg_tab_inactive)
                btn.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "يجب تسجيل الدخول أولاً", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, ProfileActivity::class.java))
            return
        }

        // Cooldown check (60 seconds between comments)
        val cd = CommentsManager.checkCooldown(this)
        if (!cd.allowed) {
            Toast.makeText(this, "انتظر ${cd.secondsLeft} ثانية قبل التعليق مرة أخرى", Toast.LENGTH_SHORT).show()
            return
        }

        // Max 2 top-level comments per chapter
        if (contextType == "chapter" && replyParent == null) {
            if (!CommentsManager.checkChapterLimit(this, contextId, user.email)) {
                Toast.makeText(this, "وصلت للحد الأقصى (تعليقان لكل فصل)", Toast.LENGTH_LONG).show()
                return
            }
        }

        val parentId = replyParent?.id
        val error = CommentsManager.addComment(
            context = this,
            contextId = contextId,
            contextType = contextType,
            text = text,
            parentId = parentId
        )

        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } else {
            commentInput.text.clear()
            replyParent = null
            replyBanner.visibility = View.GONE
            commentInput.hint = "اكتب تعليقك..."
            Toast.makeText(this, "تم النشر", Toast.LENGTH_SHORT).show()
            loadComments()
        }
    }

    private fun loadComments() {
        currentComments = CommentsManager.getComments(this, contextId).toMutableList()
        renderComments()
    }

    private fun renderComments() {
        val topLevel = currentComments.filter { it.parentId == null }
        commentsCount.text = topLevel.size.toString()
        if (topLevel.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }
        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        val sorted = CommentsManager.sortComments(topLevel, currentSort)
        adapter.submitList(sorted)
    }

    // ============================================================
    //  Action handlers
    // ============================================================

    private fun handleLike(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "سجّل الدخول للإعجاب", Toast.LENGTH_SHORT).show()
            return
        }
        CommentsManager.toggleLike(this, c.id)
        loadComments()
    }

    private fun handleDislike(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "سجّل الدخول للإعجاب", Toast.LENGTH_SHORT).show()
            return
        }
        CommentsManager.toggleDislike(this, c.id)
        loadComments()
    }

    private fun startReply(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "سجّل الدخول للرد", Toast.LENGTH_SHORT).show()
            return
        }
        replyParent = c
        replyLabel.text = "رد على: ${c.authorName}"
        replyBanner.visibility = View.VISIBLE
        commentInput.hint = "اكتب ردّك على ${c.authorName}..."
        commentInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(commentInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showEditDialog(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        if (user?.email != c.authorEmail) {
            Toast.makeText(this, "يمكن تعديل تعليقك فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(c.text)
            setSelection(c.text.length)
            setPadding(40, 24, 40, 24)
            minLines = 2
            maxLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل التعليق")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isEmpty()) {
                    Toast.makeText(this, "النص فارغ", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val err = CommentsManager.editComment(this, c.id, newText)
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                else loadComments()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showDeleteDialog(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        val isOwner = user?.email == c.authorEmail
        val isAdmin = user?.isAdmin == true
        if (!isOwner && !isAdmin) {
            Toast.makeText(this, "غير مسموح", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حذف التعليق")
            .setMessage("هل تريد حذف هذا التعليق؟ لا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ ->
                CommentsManager.deleteComment(this, c.id)
                loadComments()
                Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showBanDialog(c: CommentsManager.Comment) {
        val user = AuthManager.getCurrentUser(this)
        if (user?.isAdmin != true) {
            Toast.makeText(this, "إجراء المشرف فقط", Toast.LENGTH_SHORT).show()
            return
        }
        if (c.isAdmin) {
            Toast.makeText(this, "لا يمكن حظر مشرف", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("حظر المستخدم")
            .setMessage("سيتم حظر جهاز ${c.authorName} نهائياً وحذف جميع تعليقاته. متابعة؟")
            .setPositiveButton("حظر") { _, _ ->
                val err = CommentsManager.banUser(this, c.id)
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                else {
                    Toast.makeText(this, "تم حظر المستخدم", Toast.LENGTH_SHORT).show()
                    loadComments()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openUserProfile(email: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user?.email == email) {
            // It's me → go to my profile
            startActivity(Intent(this, ProfileActivity::class.java))
            return
        }
        val intent = Intent(this, UserProfileActivity::class.java)
        intent.putExtra("user_email", email)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshUser()
        loadComments()
    }
}
