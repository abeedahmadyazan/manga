const { db } = require('./_lib');

/**
 * Check if user is on cooldown (60s between comments)
 * Uses Firestore as the rate limit store (works across all Vercel instances)
 */
async function checkCooldown(email) {
  const doc = await db.collection('comment_cooldowns').doc(email).get();
  if (!doc.exists) return { allowed: true };
  const lastComment = doc.data().lastCommentAt || 0;
  if (Date.now() - lastComment < 60000) {
    const wait = Math.ceil((60000 - (Date.now() - lastComment)) / 1000);
    return { allowed: false, wait };
  }
  return { allowed: true };
}

async function updateCooldown(email) {
  await db.collection('comment_cooldowns').doc(email).set({
    lastCommentAt: Date.now(),
  });
}

module.exports = { checkCooldown, updateCooldown };
