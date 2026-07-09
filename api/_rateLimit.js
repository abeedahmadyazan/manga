const { db } = require('./_lib');

async function checkCooldown(email) {
  if (!email || email.length === 0) return { allowed: true };
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
  if (!email || email.length === 0) return;
  await db.collection('comment_cooldowns').doc(email).set({
    lastCommentAt: Date.now(),
  });
}

module.exports = { checkCooldown, updateCooldown };
