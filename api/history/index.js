const { db } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  // GET /api/history - get own history
  if (req.method === 'GET') {
    try {
      const doc = await db.collection('reading_history').doc(email).get();
      if (!doc.exists) return res.json({ history: [] });
      const data = doc.data();
      res.json({ history: data.entries || [] });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر تحميل السجل' });
    }
    return;
  }
  
  // POST /api/history - add to history
  if (req.method === 'POST') {
    const entry = req.body || {};
    if (!entry.mangaId || entry.mangaId.length > 200) {
      return res.status(400).json({ error: 'بيانات غير صالحة' });
    }
    try {
      const docRef = db.collection('reading_history').doc(email);
      const doc = await docRef.get();
      const entries = doc.exists ? (doc.data().entries || []) : [];
      // Remove existing entry for same manga
      const filtered = entries.filter(e => e.mangaId !== entry.mangaId);
      // Add new at start
      filtered.unshift({
        mangaId: entry.mangaId,
        mangaTitle: (entry.mangaTitle || '').slice(0, 200),
        mangaCover: (entry.mangaCover || '').slice(0, 500),
        chapterId: (entry.chapterId || '').slice(0, 200),
        chapterNumber: (entry.chapterNumber || '').slice(0, 20),
        chapterTitle: (entry.chapterTitle || '').slice(0, 200),
        readAt: Date.now(),
      });
      // Keep max 200 entries
      const trimmed = filtered.slice(0, 200);
      await docRef.set({ entries: trimmed });
      res.json({ success: true, history: trimmed });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر حفظ السجل' });
    }
    return;
  }
  
  // DELETE /api/history - clear history
  if (req.method === 'DELETE') {
    try {
      await db.collection('reading_history').doc(email).delete();
      res.json({ success: true });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر مسح السجل' });
    }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
