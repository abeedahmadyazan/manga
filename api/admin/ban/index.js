const { db } = require('../../_lib');
const { authenticate, isAdmin } = require('../../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  if (!await isAdmin(authResult.user.uid)) return res.status(403).json({ error: 'غير مصرح' });
  
  // POST - ban user
  if (req.method === 'POST') {
    const { email, reason, duration } = req.body || {};
    if (!email) return res.status(400).json({ error: 'الإيميل مطلوب' });
    try {
      const banData = {
        email, reason: reason || 'مخالفة',
        bannedAt: Date.now(), bannedBy: authResult.user.email,
        permanent: duration === 0,
        until: duration > 0 ? Date.now() + duration : null,
      };
      await db.collection('banned_users').doc(email).set(banData);
      res.json({ success: true, ...banData });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر الحظر' });
    }
    return;
  }
  
  // DELETE - unban user (?email=xxx)
  if (req.method === 'DELETE') {
    const { email } = req.query;
    if (!email) return res.status(400).json({ error: 'الإيميل مطلوب' });
    try {
      await db.collection('banned_users').doc(email).delete();
      res.json({ success: true });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر رفع الحظر' });
    }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
