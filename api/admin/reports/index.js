const { db } = require('../../_lib');
const { authenticate, isAdmin } = require('../../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  if (!await isAdmin(authResult.user.uid)) return res.status(403).json({ error: 'غير مصرح' });
  
  const snapshot = await db.collection('reports')
    .where('resolved', '==', false)
    .orderBy('createdAt', 'desc')
    .limit(100)
    .get();
  
  const reports = [];
  snapshot.forEach(doc => reports.push({ id: doc.id, ...doc.data() }));
  res.json({ reports });
};
