package com.yazan.manga

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CloudCommentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommentsActivity : AppCompatActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var commentInput: EditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var sortTabs: TabLayout

    private var contextId: String = ""
    private var contextType: String = "manga"
    private var contextTitle: String = ""
    private var currentSort: String = "newest"
    private var allComments: List<CloudCommentsManager.Comment> = emptyList()
    private var listener: ListenerRegistration? = null
    private lateinit var adapter: CommentsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comments)

        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"
        contextTitle = intent.getStringExtra("context_title") ?: "تعليقات"

        initViews()
        startListening()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.commentsTitle).text = "💬 $contextTitle"
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.commentsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyText = findViewById(R.id.emptyText)
        commentInput = findViewById(R.id.commentInput)
        sendBtn = findViewById(R.id.btnSendComment)
        sortTabs = findViewById(R.id.sortTabs)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        swipeRefresh.setOnRefreshListener {
            // Manual pull-to-refresh: re-fetch comments immediately.
            refreshComments()
        }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        adapter = CommentsAdapter(
            currentUser = AuthManager.getCurrentUser(this),
            onLike = { c ->
                // Bot protection: ignore rapid taps
                if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                    AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(c.id, it.email, true) {} }
                } else {
                    Toast.makeText(this, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                }
            },
            onDislike = { c ->
                if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                    AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(c.id, it.email, false) {} }
                } else {
                    Toast.makeText(this, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                }
            },
            onReply = { c -> openReplies(c.id, c.authorName) },
            onDelete = { c -> confirmDelete(c.id) },
            onBan = { c -> confirmBan(c.authorEmail, c.authorName) },
            onReport = { c -> showReportDialog(c) },
            onProfile = { email ->
            val intent = Intent(this, UserProfileActivity::class.java)
            intent.putExtra("user_email", email)
            startActivity(intent)
        },
            onEdit = { c -> showEditDialog(c) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sendBtn.setOnClickListener {
            val text = commentInput.text.toString().trim()
            if (text.isNotEmpty()) sendComment(text)
        }

        sortTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentSort = when (tab.position) { 1 -> "oldest"; 2 -> "likes"; else -> "newest" }
                updateList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun startListening() {
        loadingIndicator.visibility = View.VISIBLE
        // Refresh admin status so the adapter has the correct isAdmin flag
        com.yazan.manga.data.AuthManager.refreshAdminStatus(this)
        listener = CloudCommentsManager.listenToComments(
            contextId = contextId,
            onUpdate = { comments ->
                loadingIndicator.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                allComments = comments
                updateList()
            },
            onError = { e ->
                loadingIndicator.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                Toast.makeText(this, "تعذّر تحميل التعليقات", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updateList() {
        // Refresh currentUser so admin status changes are reflected
        adapter.updateCurrentUser(AuthManager.getCurrentUser(this))
        
        val topLevel = allComments.filter { it.parentId == null }
        val sorted = when (currentSort) {
            "oldest" -> topLevel.sortedBy { it.createdAt }
            "likes" -> topLevel.sortedByDescending { it.likes.size - it.dislikes.size }
            else -> topLevel.sortedByDescending { it.createdAt }
        }
        val repliesMap = allComments.filter { it.parentId != null }.groupBy { it.parentId!! }
        
        if (sorted.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
        adapter.updateList(sorted, repliesMap)
    }

    private fun sendComment(text: String) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) { Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show(); return }
        sendBtn.isEnabled = false
        CloudCommentsManager.addComment(this, contextId, contextType, text, null) { success, error ->
            sendBtn.isEnabled = true
            if (success) {
                commentInput.text.clear()
                // Refresh immediately so the new comment appears without
                // waiting for the next 5s poll cycle.
                refreshComments()
            } else {
                Toast.makeText(this, error ?: "حدث خطأ", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Re-fetch comments from the server immediately. Used by pull-to-refresh
     * and after posting/editing/deleting a comment so the UI updates instantly.
     */
    private fun refreshComments() {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val comments = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.yazan.manga.data.ApiClient.getComments(contextId)
            }
            swipeRefresh.isRefreshing = false
            allComments = comments
            updateList()
        }
    }

    private fun confirmDelete(commentId: String) {
        AlertDialog.Builder(this).setTitle("حذف التعليق").setMessage("هل تريد الحذف؟")
            .setPositiveButton("حذف") { _, _ ->
                CloudCommentsManager.deleteComment(commentId) { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                            // Refresh immediately from the server so the
                            // deleted comment disappears without waiting.
                            refreshComments()
                        } else {
                            Toast.makeText(this, "فشل الحذف", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    /**
     * Show a dialog to edit a comment. Enforces the same 60-second cooldown
     * as posting a new comment (shares the same cooldown timer).
     */
    private fun showEditDialog(comment: CloudCommentsManager.Comment) {
        // Admin bypasses cooldown
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "يجب تسجيل الدخول", Toast.LENGTH_SHORT).show()
            return
        }

        // Cooldown is now enforced server-side by the Vercel API.
        // No need to check Firestore directly here.
        showEditDialogInternal(comment, user)
    }

    private fun showEditDialogInternal(comment: CloudCommentsManager.Comment, user: AuthManager.User) {
        val input = EditText(this).apply {
            setText(comment.text)
            setSelection(comment.text.length)
            setSingleLine(false)
            maxLines = 4
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("تعديل التعليق")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isEmpty()) {
                    Toast.makeText(this, "التعليق فارغ", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newText.length > 500) {
                    Toast.makeText(this, "التعليق طويل جداً", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newText == comment.text) {
                    Toast.makeText(this, "لم تتغير أي شي", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                CloudCommentsManager.editComment(comment.id, newText) { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "تم التعديل", Toast.LENGTH_SHORT).show()
                            // Refresh immediately so the edited text shows up
                            // without waiting for the next poll.
                            refreshComments()
                        } else {
                            Toast.makeText(this, "فشل التعديل", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmBan(email: String, name: String) {
        AlertDialog.Builder(this).setTitle("حظر المستخدم").setMessage("حظر $name؟")
            .setPositiveButton("حظر") { _, _ -> CloudCommentsManager.banUser(email, this) {} }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showReportDialog(comment: CloudCommentsManager.Comment) {
        val reasons = arrayOf(
            "محتوى مسيء أو غير لائق",
            "إهانة أو تحرش",
            "سبام أو تكرار",
            "مخالفة أخرى"
        )
        val checked = intArrayOf(0)
        AlertDialog.Builder(this)
            .setTitle("الإبلاغ عن تعليق ${comment.authorName}")
            .setSingleChoiceItems(reasons, checked[0]) { _, which -> checked[0] = which }
            .setPositiveButton("إرسال البلاغ") { _, _ ->
                val reason = reasons[checked[0]]
                CloudCommentsManager.reportComment(this, comment, contextTitle, reason) { success, error ->
                    runOnUiThread {
                        if (success) Toast.makeText(this, "تم إرسال البلاغ للمشرف", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "حدث خطأ", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openReplies(commentId: String, authorName: String) {
        val intent = Intent(this, RepliesActivity::class.java)
        intent.putExtra("parent_id", commentId)
        intent.putExtra("context_id", contextId)
        intent.putExtra("context_type", contextType)
        intent.putExtra("parent_author", authorName)
        startActivity(intent)
    }

    override fun onDestroy() { super.onDestroy(); listener?.remove() }
}

class CommentsAdapter(
    private var currentUser: AuthManager.User?,
    private val onLike: (CloudCommentsManager.Comment) -> Unit,
    private val onDislike: (CloudCommentsManager.Comment) -> Unit,
    private val onReply: (CloudCommentsManager.Comment) -> Unit,
    private val onDelete: (CloudCommentsManager.Comment) -> Unit,
    private val onBan: (CloudCommentsManager.Comment) -> Unit,
    private val onReport: (CloudCommentsManager.Comment) -> Unit,
    private val onProfile: (String) -> Unit,
    private val onEdit: (CloudCommentsManager.Comment) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.VH>() {

    private val items = mutableListOf<CloudCommentsManager.Comment>()
    private val repliesMap = mutableMapOf<String, List<CloudCommentsManager.Comment>>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    // Cache of cloud profiles: email -> (name, avatarBase64). Avoids refetching on every scroll.
    private val cloudProfiles = mutableMapOf<String, AuthManager.CloudUser?>()

    init {
        // Stable IDs so RecyclerView doesn't rebind unchanged comments
        // when the list updates (prevents avatar flicker).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    fun updateList(top: List<CloudCommentsManager.Comment>, replies: Map<String, List<CloudCommentsManager.Comment>>) {
        // Use DiffUtil so only NEW/CHANGED comments are rebound. This
        // prevents existing comments from having their avatars cleared +
        // reloaded every time a new comment is added or a like changes.
        val old = items.toList()
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = top.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == top[n].id
                override fun areContentsTheSame(o: Int, n: Int) =
                    old[o].text == top[n].text &&
                    old[o].likes == top[n].likes &&
                    old[o].dislikes == top[n].dislikes &&
                    old[o].editedAt == top[n].editedAt &&
                    old[o].authorName == top[n].authorName
            }
        )
        items.clear(); items.addAll(top)
        repliesMap.clear(); repliesMap.putAll(replies)
        diff.dispatchUpdatesTo(this)
    }
    
    fun updateCurrentUser(user: AuthManager.User?) {
        currentUser = user
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(items[position]) }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar = v.findViewById<TextView>(R.id.commentAvatar)
        private val avatarImg = v.findViewById<android.widget.ImageView>(R.id.commentAvatarImage)
        private val adminBadge = v.findViewById<TextView>(R.id.commentAdminBadge)
        private val author = v.findViewById<TextView>(R.id.commentAuthor)
        private val time = v.findViewById<TextView>(R.id.commentTime)
        private val text = v.findViewById<TextView>(R.id.commentText)
        private val btnLike = v.findViewById<View>(R.id.btnLike)
        private val btnDislike = v.findViewById<View>(R.id.btnDislike)
        private val btnReply = v.findViewById<View>(R.id.btnReply)
        private val btnEdit = v.findViewById<View>(R.id.btnEdit)
        private val tvEdited = v.findViewById<TextView>(R.id.tvEdited)
        private val btnDelete = v.findViewById<View>(R.id.btnDelete)
        private val btnReport = v.findViewById<View>(R.id.btnReport)
        private val imgLike = v.findViewById<android.widget.ImageView>(R.id.imgLike)
        private val tvLikeCount = v.findViewById<TextView>(R.id.tvLikeCount)
        private val imgDislike = v.findViewById<android.widget.ImageView>(R.id.imgDislike)
        private val tvDislikeCount = v.findViewById<TextView>(R.id.tvDislikeCount)

        fun bind(c: CloudCommentsManager.Comment) {
            // === AVATAR: show cached bitmap immediately if available ===
            // Previously this always cleared the avatar to "?" then reloaded,
            // causing a flash on every list update. Now we show the cached
            // avatar instantly and only fall back to "?" if truly unknown.
            val cachedBmp = com.yazan.manga.data.AvatarCache.get(c.authorEmail)
            if (cachedBmp != null) {
                avatar.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(itemView.context)
                    .load(cachedBmp)
                    .circleCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(avatarImg)
            } else if (c.authorAvatar.isNotEmpty()) {
                // Decode the base64 avatar from the comment data itself
                com.yazan.manga.data.AvatarCache.put(c.authorEmail, c.authorAvatar)
                avatar.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                try {
                    val bytes = android.util.Base64.decode(c.authorAvatar, android.util.Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(bmp)
                            .circleCrop()
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .into(avatarImg)
                    }
                } catch (e: Exception) {
                    avatar.text = c.authorName.firstOrNull()?.toString() ?: "?"
                    avatar.visibility = View.VISIBLE
                    avatarImg.visibility = View.GONE
                }
            } else {
                // No avatar image — show first letter
                avatar.text = c.authorName.firstOrNull()?.toString() ?: "?"
                avatar.visibility = View.VISIBLE
                avatarImg.visibility = View.GONE
                avatarImg.setImageDrawable(null)
            }

            // For admins: show the name inside the green pill badge, and hide
            // the plain author TextView so nobody can impersonate an admin by
            // adding '(مشرف)' to their own name.
            if (c.isAdmin) {
                adminBadge.text = c.authorName  // temporary; will be overwritten by cloud profile
                adminBadge.visibility = View.VISIBLE
                author.visibility = View.GONE
            } else {
                adminBadge.visibility = View.GONE
                author.visibility = View.VISIBLE
                author.text = c.authorName  // temporary; will be overwritten by cloud profile
            }
            time.text = com.yazan.manga.data.relativeTime(c.createdAt)
            text.text = c.text
            val currentUserCopy2 = currentUser
            val liked = currentUserCopy2 != null && c.likes.contains(currentUserCopy2.email)
            val disliked = currentUserCopy2 != null && c.dislikes.contains(currentUserCopy2.email)
            tvLikeCount.text = c.likes.size.toString()
            tvDislikeCount.text = c.dislikes.size.toString()
            // Like: blue when liked, gray when not (transparent bg)
            val blue = android.graphics.Color.parseColor("#3b82f6")
            val red = android.graphics.Color.parseColor("#ef4444")
            val gray = android.graphics.Color.parseColor("#9ca3af")
            imgLike.imageTintList = android.content.res.ColorStateList.valueOf(if (liked) blue else gray)
            tvLikeCount.setTextColor(if (liked) blue else gray)
            imgLike.isSelected = liked
            // Dislike: red when disliked, gray when not
            imgDislike.imageTintList = android.content.res.ColorStateList.valueOf(if (disliked) red else gray)
            tvDislikeCount.setTextColor(if (disliked) red else gray)
            imgDislike.isSelected = disliked

            val currentUserCopy = currentUser
            val isOwner = currentUserCopy?.email == c.authorEmail
            val canDelete = isOwner || (currentUserCopy?.isAdmin == true)
            btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
            // Show edit button only for the comment owner
            btnEdit.visibility = if (isOwner) View.VISIBLE else View.GONE
            // Show "edited" indicator if the comment was edited
            tvEdited.visibility = if (c.editedAt != null) View.VISIBLE else View.GONE
            // Show the report button for any logged-in user who isn't the comment owner
            // AND isn't an admin (you can't report admins)
            btnReport.visibility = if (currentUserCopy != null && !isOwner && !c.isAdmin) View.VISIBLE else View.GONE

            btnLike.setOnClickListener { onLike(c) }
            btnDislike.setOnClickListener { onDislike(c) }
            btnReply.setOnClickListener { onReply(c) }
            // Show reply count next to the reply icon
            val replyCount = repliesMap[c.id]?.size ?: 0
            val replyTv = (btnReply as? android.widget.LinearLayout)?.findViewById<android.widget.TextView>(android.R.id.text1)
                ?: (btnReply as? android.widget.LinearLayout)?.let { layout ->
                    // Find the second child (TextView after ImageView)
                    if (layout.childCount >= 2 && layout.getChildAt(1) is android.widget.TextView) {
                        layout.getChildAt(1) as android.widget.TextView
                    } else null
                }
            replyTv?.text = if (replyCount > 0) "$replyCount رد" else "رد"
            btnEdit.setOnClickListener { onEdit(c) }
            btnDelete.setOnClickListener { onDelete(c) }
            btnReport.setOnClickListener { onReport(c) }

            author.setOnClickListener { onProfile(c.authorEmail) }
            avatar.setOnClickListener { onProfile(c.authorEmail) }
            avatarImg.setOnClickListener { onProfile(c.authorEmail) }

            // Fetch the latest name + avatar from the cloud (so changes are visible to everyone)
            loadCloudProfile(c)

            itemView.setOnLongClickListener {
                if (currentUser?.isAdmin == true && !c.isAdmin) { onBan(c); true }
                else false
            }
        }

        /** Fetch the commenter's cloud profile (name + avatar) and update the view. */
        private fun loadCloudProfile(c: CloudCommentsManager.Comment) {
            // === FAST PATH: check in-memory AvatarCache first (0ms) ===
            val cachedBmp = com.yazan.manga.data.AvatarCache.get(c.authorEmail)
            if (cachedBmp != null) {
                avatar.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(itemView.context)
                    .load(cachedBmp)
                    .circleCrop()
                    .into(avatarImg)
                // Still update the name from cache
                cloudProfiles[c.authorEmail]?.let { applyProfile(c, it) }
                return
            }
            
            // === MEDIUM PATH: use authorAvatar from API response ===
            if (c.authorAvatar.isNotEmpty()) {
                com.yazan.manga.data.AvatarCache.put(c.authorEmail, c.authorAvatar)
                try {
                    val bytes = android.util.Base64.decode(c.authorAvatar, android.util.Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        avatar.visibility = View.GONE
                        avatarImg.visibility = View.VISIBLE
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(bmp)
                            .circleCrop()
                            .into(avatarImg)
                        return
                    }
                } catch (e: Exception) {}
            }
            
            // === SLOW PATH: fetch from cloud (only if not cached) ===
            if (cloudProfiles.containsKey(c.authorEmail)) {
                cloudProfiles[c.authorEmail]?.let { applyProfile(c, it) }
                return
            }
            cloudProfiles[c.authorEmail] = null
            AuthManager.fetchCloudUser(c.authorEmail) { cu ->
                cloudProfiles[c.authorEmail] = cu
                // Cache the avatar in memory
                if (cu?.avatarBase64?.isNotEmpty() == true) {
                    com.yazan.manga.data.AvatarCache.put(c.authorEmail, cu.avatarBase64)
                }
                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    items.getOrNull(bindingAdapterPosition)?.id == c.id) {
                    applyProfile(c, cu)
                }
            }
        }

        private fun applyProfile(c: CloudCommentsManager.Comment, cu: AuthManager.CloudUser?) {
            // Update the displayed name if the cloud has a newer one — in the
            // correct place (green pill for admins, plain text for others).
            if (!cu?.name.isNullOrEmpty() && cu!!.name != c.authorName) {
                if (c.isAdmin) {
                    adminBadge.text = cu.name
                } else {
                    author.text = cu.name
                }
                avatar.text = cu.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
            // Show the avatar image if available (circular)
            val b64 = cu?.avatarBase64
            if (!b64.isNullOrEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        avatar.visibility = View.GONE
                        avatarImg.visibility = View.VISIBLE
                        // Use Glide with circleCrop() to render the avatar as a circle
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(bmp)
                            .circleCrop()
                            .into(avatarImg)
                    }
                } catch (_: Exception) {}
            }
        }
    }
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}