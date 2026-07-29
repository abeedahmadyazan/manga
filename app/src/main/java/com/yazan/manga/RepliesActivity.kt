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
import com.yazan.manga.ui.BaseSwipeBackActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.AvatarCache
import com.yazan.manga.data.CloudCommentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepliesActivity : BaseSwipeBackActivity() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var replyInput: EditText
    private lateinit var sendBtn: MaterialButton

    private var parentId: String = ""
    private var contextId: String = ""
    private var contextType: String = ""
    private var allReplies: List<CloudCommentsManager.Comment> = emptyList()
    private lateinit var titleView: TextView
    private var parentAuthor: String = "تعليق"
    private var listener: ListenerRegistration? = null
    private lateinit var repliesAdapter: RepliesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replies)
        com.yazan.manga.data.AmoledMode.applyIfEnabled(this)

        parentId = intent.getStringExtra("parent_id") ?: ""
        contextId = intent.getStringExtra("context_id") ?: ""
        contextType = intent.getStringExtra("context_type") ?: "manga"

        parentAuthor = intent.getStringExtra("parent_author") ?: "تعليق"
        titleView = findViewById(R.id.repliesTitle)
        titleView.text = "💬 ردود على: $parentAuthor"

        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerView = findViewById(R.id.repliesRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        replyInput = findViewById(R.id.replyInput)
        sendBtn = findViewById(R.id.btnSendReply)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        swipeRefresh.setOnRefreshListener {
            // Pull-to-refresh: re-fetch replies immediately.
            refreshReplies()
        }
        swipeRefresh.setColorSchemeResources(R.color.primary)

        sendBtn.setOnClickListener {
            val text = replyInput.text.toString().trim()
            if (text.isNotEmpty()) sendReply(text)
        }

        // Set up the RecyclerView once. The adapter is updated in-place via
        // updateList() — this prevents the whole list from being recreated
        // on every poll, which was the root cause of the avatar flicker.
        repliesAdapter = RepliesAdapter(
            currentUser = AuthManager.getCurrentUser(this),
            onLike = { r ->
                if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                    AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(r.id, it.email, true) {} }
                } else {
                    Toast.makeText(this, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                }
            },
            onDislike = { r ->
                if (com.yazan.manga.data.BotProtection.checkLikeTap()) {
                    AuthManager.getCurrentUser(this)?.let { CloudCommentsManager.toggleLike(r.id, it.email, false) {} }
                } else {
                    Toast.makeText(this, "مهلاً، توقف قليلاً", Toast.LENGTH_SHORT).show()
                }
            },
            onReply = { r -> openNestedReply(r) },
            onDelete = { r -> confirmDelete(r) },
            onReport = { r -> showReportDialog(r) },
            onProfile = { email -> openUserProfile(email) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = repliesAdapter

        startListening()
    }

    private fun startListening() {
        // === INSTANT: show cached replies while we fetch fresh ones ===
        val cached = com.yazan.manga.data.CommentsCache.get(this, contextId)
        if (cached.isNotEmpty()) {
            allReplies = cached.filter { it.parentId == parentId }
            val count = allReplies.size
            titleView.text = if (count > 0) "💬 $count رد" else "💬 ردود على: $parentAuthor"
            repliesAdapter.updateList(allReplies)
        }
        listener = CloudCommentsManager.listenToComments(
            contextId = contextId,
            onUpdate = { comments ->
                swipeRefresh.isRefreshing = false
                allReplies = comments.filter { it.parentId == parentId }
                // Save to cache so next launch shows them instantly
                com.yazan.manga.data.CommentsCache.save(this, contextId, comments)
                // Update title with reply count
                val count = allReplies.size
                titleView.text = if (count > 0) "💬 $count رد" else "💬 ردود على: $parentAuthor"
                repliesAdapter.updateList(allReplies)
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
                // Refresh immediately so the new reply appears instantly.
                refreshReplies()
            } else {
                Toast.makeText(this, error ?: "حدث خطأ", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Reply to a reply inline — does NOT open a new page.
     *
     * Instead, puts "@username " in the reply input field so the user can
     * type their reply. The reply is posted as a normal sibling reply (same
     * parentId as the parent comment), but prefixed with "@username" so
     * everyone knows who it's addressed to.
     *
     * This matches how YouTube/Reddit comments work — no nested pages,
     * just a mention prefix.
     */
    private fun openNestedReply(reply: CloudCommentsManager.Comment) {
        val mention = "@${reply.authorName} "
        replyInput.setText(mention)
        replyInput.requestFocus()
        replyInput.setSelection(mention.length)
        // Show keyboard
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(replyInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun confirmDelete(reply: CloudCommentsManager.Comment) {
        AlertDialog.Builder(this)
            .setTitle("حذف الرد").setMessage("هل تريد الحذف؟")
            .setPositiveButton("حذف") { _, _ ->
                CloudCommentsManager.deleteComment(reply.id) { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                            allReplies = allReplies.filterNot { it.id == reply.id }
                            repliesAdapter.updateList(allReplies)
                        } else {
                            Toast.makeText(this, "فشل الحذف", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showReportDialog(reply: CloudCommentsManager.Comment) {
        val reasons = arrayOf("محتوى مسيء أو غير لائق", "إهانة أو تحرش", "سبام أو تكرار", "مخالفة أخرى")
        val checked = intArrayOf(0)
        AlertDialog.Builder(this)
            .setTitle("الإبلاغ عن رد ${reply.authorName}")
            .setSingleChoiceItems(reasons, checked[0]) { _, which -> checked[0] = which }
            .setPositiveButton("إرسال البلاغ") { _, _ ->
                CloudCommentsManager.reportComment(this, reply, "رد", reasons[checked[0]]) { success, error ->
                    runOnUiThread {
                        if (success) Toast.makeText(this, "تم إرسال البلاغ", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this, "حدث خطأ", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    /**
     * Re-fetch replies from the server immediately. Used by pull-to-refresh
     * and after posting a reply so the UI updates without waiting for the
     * next 5s poll cycle.
     *
     * The swipeRefresh spinner is hidden after 600ms MAX, even if the API
     * is still loading. The actual data refresh happens in the background.
     */
    private fun refreshReplies() {
        swipeRefresh.isRefreshing = true
        swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 600)
        lifecycleScope.launch {
            val comments = withContext(Dispatchers.IO) {
                com.yazan.manga.data.ApiClient.getComments(contextId)
            }
            allReplies = comments.filter { it.parentId == parentId }
            // Save fresh data to cache
            com.yazan.manga.data.CommentsCache.save(this@RepliesActivity, contextId, comments)
            val count = allReplies.size
            titleView.text = if (count > 0) "💬 $count رد" else "💬 ردود على: $parentAuthor"
            repliesAdapter.updateList(allReplies)
        }
    }

    private fun openUserProfile(email: String) {
        val intent = android.content.Intent(this, UserProfileActivity::class.java)
        intent.putExtra("user_email", email)
        startActivity(intent)
    }

    override fun onDestroy() { super.onDestroy(); listener?.remove() }

    /**
     * When returning from background, re-fetch replies immediately so the
     * list doesn't appear empty (the polling timer may have been delayed).
     */
    override fun onResume() {
        super.onResume()
        if (contextId.isNotEmpty()) refreshReplies()
    }
}

/**
 * Dedicated RecyclerView adapter for replies.
 *
 * Key improvements over the previous inline anonymous adapter:
 * - **Stable IDs** (setHasStableIds) — RecyclerView knows which items are
 *   the same across updates, so it only rebinds changed items.
 * - **DiffUtil** — only items whose text/likes/dislikes actually changed
 *   get rebound. This prevents the avatar from being cleared + reloaded
 *   on every 5s poll, eliminating the flicker.
 * - **AvatarCache integration** — same pattern as CommentsAdapter: show
 *   cached bitmap instantly, fall back to base64 → decode → cache, then
 *   to cloud fetch. No more "?" flash on every update.
 */
class RepliesAdapter(
    private var currentUser: AuthManager.User?,
    private val onLike: (CloudCommentsManager.Comment) -> Unit,
    private val onDislike: (CloudCommentsManager.Comment) -> Unit,
    private val onReply: (CloudCommentsManager.Comment) -> Unit,
    private val onDelete: (CloudCommentsManager.Comment) -> Unit,
    private val onReport: (CloudCommentsManager.Comment) -> Unit,
    private val onProfile: (String) -> Unit
) : RecyclerView.Adapter<RepliesAdapter.VH>() {

    private val items = mutableListOf<CloudCommentsManager.Comment>()
    // Cache of cloud profiles: email -> CloudUser. Avoids refetching on every scroll.
    private val cloudProfiles = mutableMapOf<String, AuthManager.CloudUser?>()

    init {
        // Stable IDs so RecyclerView doesn't rebind unchanged replies
        // when the list updates (prevents avatar flicker).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    fun updateList(newItems: List<CloudCommentsManager.Comment>) {
        // Use DiffUtil so only NEW/CHANGED replies are rebound. This
        // prevents existing replies from having their avatars cleared +
        // reloaded every time a new reply is added or a like changes.
        val old = items.toList()
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
                override fun areContentsTheSame(o: Int, n: Int) =
                    old[o].text == newItems[n].text &&
                    old[o].likes == newItems[n].likes &&
                    old[o].dislikes == newItems[n].dislikes &&
                    old[o].editedAt == newItems[n].editedAt &&
                    old[o].authorName == newItems[n].authorName
            }
        )
        items.clear()
        items.addAll(newItems)
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

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

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
        private val btnDelete = v.findViewById<View>(R.id.btnDelete)
        private val btnReport = v.findViewById<View>(R.id.btnReport)
        private val imgLike = v.findViewById<android.widget.ImageView>(R.id.imgLike)
        private val tvLikeCount = v.findViewById<TextView>(R.id.tvLikeCount)
        private val imgDislike = v.findViewById<android.widget.ImageView>(R.id.imgDislike)
        private val tvDislikeCount = v.findViewById<TextView>(R.id.tvDislikeCount)

        fun bind(r: CloudCommentsManager.Comment) {
            // === AVATAR: source of truth is the API's authorAvatar field ===
            // Same logic as CommentsAdapter — check for avatar changes.
            val cachedBmp = AvatarCache.get(r.authorEmail)
            val apiAvatarChanged = r.authorAvatar.isNotEmpty() &&
                AvatarCache.hasChanged(r.authorEmail, r.authorAvatar)

            if (apiAvatarChanged) {
                val updated = AvatarCache.put(r.authorEmail, r.authorAvatar)
                if (updated) {
                    val freshBmp = AvatarCache.get(r.authorEmail)
                    if (freshBmp != null) {
                        avatar.visibility = View.GONE
                        avatarImg.visibility = View.VISIBLE
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(freshBmp)
                            .circleCrop()
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .into(avatarImg)
                    }
                }
            } else if (cachedBmp != null) {
                avatar.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(itemView.context)
                    .load(cachedBmp)
                    .circleCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(avatarImg)
            } else if (r.authorAvatar.isNotEmpty()) {
                AvatarCache.put(r.authorEmail, r.authorAvatar)
                val bmp = AvatarCache.get(r.authorEmail)
                if (bmp != null) {
                    avatar.visibility = View.GONE
                    avatarImg.visibility = View.VISIBLE
                    com.bumptech.glide.Glide.with(itemView.context)
                        .load(bmp)
                        .circleCrop()
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .into(avatarImg)
                } else {
                    showLetterAvatar(r.authorName)
                }
            } else {
                showLetterAvatar(r.authorName)
            }

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

            // Render reply text with @mentions highlighted in emerald.
            // This makes it visually clear who the reply is addressed to.
            val replyText = r.text
            if (replyText.startsWith("@")) {
                val spaceIdx = replyText.indexOf(' ')
                if (spaceIdx > 0) {
                    val mention = replyText.substring(0, spaceIdx)
                    val rest = replyText.substring(spaceIdx + 1)
                    val spannable = android.text.SpannableStringBuilder()
                        .append(mention)
                        .append(" ")
                        .append(rest)
                    // Color the @mention in emerald + make it bold
                    val emerald = android.graphics.Color.parseColor("#10b981")
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(emerald),
                        0, mention.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, mention.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text.text = spannable
                } else {
                    text.text = replyText
                }
            } else {
                text.text = replyText
            }

            // Reply button is now visible — enables reply-to-reply
            btnReply.visibility = View.VISIBLE

            // Like / dislike state
            tvLikeCount.text = r.likes.size.toString()
            tvDislikeCount.text = r.dislikes.size.toString()
            val blue = android.graphics.Color.parseColor("#3b82f6")
            val red = android.graphics.Color.parseColor("#ef4444")
            val gray = android.graphics.Color.parseColor("#9ca3af")
            val liked = currentUser != null && r.likes.contains(currentUser!!.email)
            val disliked = currentUser != null && r.dislikes.contains(currentUser!!.email)
            imgLike.imageTintList = android.content.res.ColorStateList.valueOf(if (liked) blue else gray)
            tvLikeCount.setTextColor(if (liked) blue else gray)
            imgLike.isSelected = liked
            imgDislike.imageTintList = android.content.res.ColorStateList.valueOf(if (disliked) red else gray)
            tvDislikeCount.setTextColor(if (disliked) red else gray)
            imgDislike.isSelected = disliked

            // Owner / delete / report visibility
            val isOwner = currentUser?.email == r.authorEmail
            val canDelete = isOwner || (currentUser?.isAdmin == true)
            btnDelete.visibility = if (canDelete) View.VISIBLE else View.GONE
            btnReport.visibility = if (currentUser != null && !isOwner && !r.isAdmin) View.VISIBLE else View.GONE

            // Click listeners
            btnLike.setOnClickListener { onLike(r) }
            btnDislike.setOnClickListener { onDislike(r) }
            btnReply.setOnClickListener { onReply(r) }
            btnDelete.setOnClickListener { onDelete(r) }
            btnReport.setOnClickListener { onReport(r) }

            author.setOnClickListener { onProfile(r.authorEmail) }
            avatar.setOnClickListener { onProfile(r.authorEmail) }
            avatarImg.setOnClickListener { onProfile(r.authorEmail) }

            // Fetch the latest name + avatar from the cloud (in background)
            loadCloudProfile(r)
        }

        private fun showLetterAvatar(name: String) {
            avatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            avatar.visibility = View.VISIBLE
            avatarImg.visibility = View.GONE
            avatarImg.setImageDrawable(null)
        }

        /** Fetch the commenter's cloud profile (name + avatar) and update the view. */
        private fun loadCloudProfile(r: CloudCommentsManager.Comment) {
            // === FAST PATH: check in-memory AvatarCache first (0ms) ===
            val cachedBmp = AvatarCache.get(r.authorEmail)
            if (cachedBmp != null) {
                avatar.visibility = View.GONE
                avatarImg.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(itemView.context)
                    .load(cachedBmp)
                    .circleCrop()
                    .into(avatarImg)
                // Still update the name from cache
                cloudProfiles[r.authorEmail]?.let { applyProfile(r, it) }
                return
            }

            // === MEDIUM PATH: use authorAvatar from API response ===
            if (r.authorAvatar.isNotEmpty()) {
                AvatarCache.put(r.authorEmail, r.authorAvatar)
                try {
                    val bytes = android.util.Base64.decode(r.authorAvatar, android.util.Base64.NO_WRAP)
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
            if (cloudProfiles.containsKey(r.authorEmail)) {
                cloudProfiles[r.authorEmail]?.let { applyProfile(r, it) }
                return
            }
            cloudProfiles[r.authorEmail] = null
            AuthManager.fetchCloudUser(r.authorEmail) { cu ->
                cloudProfiles[r.authorEmail] = cu
                // Cache the avatar in memory
                if (cu?.avatarBase64?.isNotEmpty() == true) {
                    AvatarCache.put(r.authorEmail, cu.avatarBase64)
                }
                if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                    items.getOrNull(bindingAdapterPosition)?.id == r.id) {
                    applyProfile(r, cu)
                }
            }
        }

        private fun applyProfile(r: CloudCommentsManager.Comment, cu: AuthManager.CloudUser?) {
            // Update the displayed name if the cloud has a newer one — in the
            // correct place (green pill for admins, plain text for others).
            if (!cu?.name.isNullOrEmpty() && cu!!.name != r.authorName) {
                if (r.isAdmin) {
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
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(bmp)
                            .circleCrop()
                            .into(avatarImg)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
