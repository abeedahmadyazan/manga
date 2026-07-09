const { db } = require('../_lib');
const { authenticate, isAdmin } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version,X-User-Email');
  if (req.method === 'OPTIONS') return res.status(200).end();

  if (req.method === 'GET') {
    const authResult = await authenticate(req);
    if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
    
    try {
      const snapshot = await db.collection('announcements').orderBy('createdAt', 'desc').limit(1).get();
      if (snapshot.empty) return res.json({ announcement: null });
      const doc = snapshot.docs[0];
      res.json({ announcement: { id: doc.id, ...doc.data() } });
    } catch (e) {
      try {
        const snapshot = await db.collection('announcements').limit(10).get();
        if (snapshot.empty) return res.json({ announcement: null });
        const docs = snapshot.docs.map(d => ({ id: d.id, ...d.data() }));
        docs.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
        res.json({ announcement: docs[0] });
      } catch (e2) { res.json({ announcement: null }); }
    }
    return;
  }

  if (req.method === 'POST') {
    const authResult = await authenticate(req);
    if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
    if (!await isAdmin(authResult.user.uid)) return res.status(403).json({ error: 'غير مصرح' });
    
    const { title, message, forceUpdate, minVersionCode, updateUrl } = req.body || {};
    if (!title || title.length > 200) return res.status(400).json({ error: 'عنوان غير صالح' });
    if (!message || message.length > 2000) return res.status(400).json({ error: 'رسالة غير صالحة' });
    
    const announcement = {
      title: title.trim(), message: message.trim(),
      forceUpdate: !!forceUpdate, minVersionCode: minVersionCode || 0,
      updateUrl: updateUrl || 'https://github.com/abeedahmadyazan/mangaapp/releases/latest',
      createdBy: authResult.user.email, createdAt: Date.now(),
    };
    const docRef = await db.collection('announcements').add(announcement);
    res.status(201).json({ id: docRef.id, ...announcement });
    return;
  }
  res.status(405).json({ error: 'Method not allowed' });
};
