const { db } = require('./_lib');

// Read user profile without auth (uses X-User-Email header)
module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,X-App-Version,X-User-Email');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const email = req.query.email || req.headers['x-user-email'] || '';
  if (!email || email.length === 0) {
    return res.status(400).json({ error: 'إيميل مطلوب' });
  }
  
  try {
    const doc = await db.collection('users').doc(email).get();
    if (!doc.exists) return res.status(404).json({ error: 'المستخدم غير موجود' });
    res.json({ user: { email, ...doc.data() } });
  } catch (e) {
    res.status(500).json({ error: 'تعذّر تحميل البيانات' });
  }
};
