const { db, admin } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  // GET /api/lists - get own lists
  if (req.method === 'GET') {
    try {
      const doc = await db.collection('user_lists').doc(email).get();
      if (!doc.exists) return res.json({ lists: {} });
      res.json({ lists: doc.data() });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر تحميل القوائم' });
    }
    return;
  }
  
  // PUT /api/lists - update own lists
  if (req.method === 'PUT') {
    const body = req.body || {};
    // Validate: only allow known list types
    const allowedKeys = ['favorites', 'reading', 'planned', 'completed', 'dropped'];
    const update = {};
    for (const key of Object.keys(body)) {
      if (allowedKeys.includes(key)) {
        if (Array.isArray(body[key])) {
          update[key] = body[key].slice(0, 1000); // max 1000 items per list
        }
      }
    }
    
    try {
      await db.collection('user_lists').doc(email).set(update, { merge: true });
      res.json({ success: true, ...update });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر تحديث القوائم' });
    }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
