const { db } = require('../../_lib');
const { authenticate, isAdmin } = require('../../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'null');
  res.setHeader('Access-Control-Allow-Methods', 'GET,PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  if (!await isAdmin(authResult.user.uid)) return res.status(403).json({ error: 'غير مصرح' });
  
  // GET - list reports
  if (req.method === 'GET') {
    try {
      const snapshot = await db.collection('reports')
        .where('resolved', '==', false)
        .orderBy('createdAt', 'desc')
        .limit(100)
        .get();
      const reports = [];
      snapshot.forEach(doc => reports.push({ id: doc.id, ...doc.data() }));
      res.json({ reports });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر تحميل البلاغات' });
    }
    return;
  }
  
  // PUT - resolve report (with optional ?id=xxx&resolve=true)
  if (req.method === 'PUT') {
    const { id } = req.query;
    try {
      await db.collection('reports').doc(id).update({
        resolved: true,
        resolvedBy: authResult.user.email,
        resolvedAt: Date.now(),
      });
      res.json({ success: true });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر حل البلاغ' });
    }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
