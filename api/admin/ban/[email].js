const { db } = require('../../../_lib');
const { authenticate, isAdmin } = require('../../../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'DELETE') return res.status(405).json({ error: 'Method not allowed' });
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  if (!await isAdmin(authResult.user.uid)) return res.status(403).json({ error: 'غير مصرح' });
  
  const { email } = req.query;
  await db.collection('banned_users').doc(email).delete();
  res.json({ success: true });
};
