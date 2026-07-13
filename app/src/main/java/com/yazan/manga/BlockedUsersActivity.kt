package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.data.ApiClient
import com.yazan.manga.ui.BaseSwipeBackActivity

/**
 * Blocked users list — a dedicated, polished screen (replaces the old dialog).
 *
 * Each row shows:
 *   - circular avatar (or letter fallback)
 *   - name + email
 *   - "إلغاء الحظر" button on the side
 *
 * Tapping a row opens the user's profile. Tapping the unblock button shows a
 * confirmation dialog, then calls /api/blocks (DELETE) and refreshes the list.
 *
 * Security note: admins can never appear in this list because the server
 * (POST /api/blocks) rejects any attempt to block an admin with 403.
 */
class BlockedUsersActivity : BaseSwipeBackActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyState: View
    private lateinit var tvCount: TextView
    private var adapter: BlockedUsersAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_users)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.blockedRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyState = findViewById(R.id.emptyState)
        tvCount = findViewById(R.id.tvCount)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadBlockedUsers()
    }

    private fun loadBlockedUsers() {
        loadingIndicator.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        Thread {
            val blocks = ApiClient.getBlockedUsers()
            runOnUiThread {
                loadingIndicator.visibility = View.GONE
                tvCount.text = blocks.size.toString()
                if (blocks.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter = BlockedUsersAdapter(blocks) { blocked ->
                        showUnblockConfirm(blocked)
                    }
                    recyclerView.adapter = adapter
                }
            }
        }.start()
    }

    private fun showUnblockConfirm(blocked: ApiClient.BlockedUser) {
        val displayName = if (blocked.name.isNotEmpty()) blocked.name else blocked.email
        AlertDialog.Builder(this)
            .setTitle("إلغاء الحظر")
            .setMessage("إلغاء حظر «$displayName»؟ ستعود تعليقاته لتظهر لك، وتعليقاتك لتظهر له.")
            .setPositiveButton("إلغاء الحظر") { _, _ ->
                Thread {
                    val (ok, err) = ApiClient.unblockUser(blocked.email)
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this, "تم إلغاء الحظر", Toast.LENGTH_SHORT).show()
                            loadBlockedUsers()  // refresh
                        } else {
                            Toast.makeText(this, err ?: "تعذّر إلغاء الحظر", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("تراجع", null)
            .show()
    }

    // =============================================================
    //  RecyclerView adapter
    // =============================================================
    private inner class BlockedUsersAdapter(
        private val items: List<ApiClient.BlockedUser>,
        private val onUnblockClick: (ApiClient.BlockedUser) -> Unit
    ) : RecyclerView.Adapter<BlockedUsersAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatarImage: ImageView = v.findViewById(R.id.avatarImage)
            val avatarLetter: TextView = v.findViewById(R.id.avatarLetter)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvEmail: TextView = v.findViewById(R.id.tvEmail)
            val btnUnblock: com.google.android.material.button.MaterialButton = v.findViewById(R.id.btnUnblock)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_user, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = items[position]
            val displayName = if (b.name.isNotEmpty()) b.name else "مستخدم"
            holder.tvName.text = displayName
            // Privacy: NEVER show anything derived from the email — no email,
            // no first-letter, no substring. Show a neutral label only.
            holder.tvEmail.text = "مستخدم محظور"

            // Avatar: decode base64 if available, otherwise show first letter.
            if (b.avatarBase64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(b.avatarBase64, android.util.Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        holder.avatarImage.visibility = View.VISIBLE
                        holder.avatarLetter.visibility = View.GONE
                        // Circular crop
                        val rounded = android.graphics.drawable.BitmapDrawable(resources, bmp)
                        com.bumptech.glide.Glide.with(holder.itemView.context)
                            .load(bmp)
                            .circleCrop()
                            .placeholder(R.drawable.bg_avatar_gradient)
                            .into(holder.avatarImage)
                    } else {
                        showLetter(holder, displayName)
                    }
                } catch (e: Exception) {
                    showLetter(holder, displayName)
                }
            } else {
                showLetter(holder, displayName)
            }

            holder.btnUnblock.setOnClickListener { onUnblockClick(b) }

            // Tapping the row opens the user's profile.
            holder.itemView.setOnClickListener {
                val intent = Intent(this@BlockedUsersActivity, UserProfileActivity::class.java)
                intent.putExtra("user_email", b.email)
                startActivity(intent)
            }
        }

        private fun showLetter(holder: VH, name: String) {
            holder.avatarImage.visibility = View.GONE
            holder.avatarLetter.visibility = View.VISIBLE
            holder.avatarLetter.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }
    }
}
