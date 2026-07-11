/**
 * Cleanup expired documents.
 *
 * This is a fallback for Firestore TTL policies (which require permissions
 * we don't have on the service account). Run this endpoint periodically
 * via Vercel Cron or an external scheduler.
 *
 * It deletes:
 * - login_attempts where expiresAt < now
 * - login_blocks where expiresAt < now
 * - comment_cooldowns where expiresAt < now
 */

const { db } = require('./_lib');

module.exports = async (req, res) => {
  // SECURITY: this endpoint requires a secret token to prevent abuse
  const authHeader = req.headers.authorization || '';
  const expectedToken = process.env.CLEANUP_SECRET;
  if (!expectedToken || authHeader !== `Bearer ${expectedToken}`) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const now = Date.now();
  const collections = ['login_attempts', 'login_blocks', 'comment_cooldowns', 'device_links'];
  const results = {};

  for (const col of collections) {
    try {
      // Query for expired documents
      const snapshot = await db.collection(col)
        .where('expiresAt', '<', now)
        .limit(500) // batch limit to avoid timeouts
        .get();

      if (snapshot.empty) {
        results[col] = { deleted: 0 };
        continue;
      }

      // Batch delete
      const batch = db.batch();
      snapshot.docs.forEach(doc => batch.delete(doc.ref));
      await batch.commit();
      results[col] = { deleted: snapshot.size };
    } catch (e) {
      results[col] = { error: e.message };
    }
  }

  res.json({ timestamp: now, results });
};
