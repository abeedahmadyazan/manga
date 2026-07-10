const { db } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version,X-User-Email');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  if (!email || email.length === 0) return res.status(400).json({ error: 'يجب تسجيل الدخول' });
  
  if (req.method === 'GET') {
    const targetEmail = req.query.email || email;
    try {
      const doc = await db.collection('users').doc(targetEmail).get();
      if (!doc.exists) return res.status(404).json({ error: 'المستخدم غير موجود' });
      res.json({ user: { email: targetEmail, ...doc.data() } });
    } catch (e) { res.status(500).json({ error: 'تعذّر تحميل البيانات' }); }
    return;
  }
  
  if (req.method === 'PUT') {
    const { name, username, avatarBase64, birthDate, country } = req.body || {};
    const update = { lastUpdated: Date.now() };
    let message = 'تم التحديث';
    
    if (name !== undefined && name !== null && name.length > 0 && name.length <= 50) {
      update.name = name.trim();
      message = 'تم تحديث الاسم بنجاح';
    }
    if (username !== undefined && username !== null && username.length > 0 && username.length <= 30) {
      try {
        const existing = await db.collection('users').where('username', '==', username).limit(1).get();
        for (const doc of existing.docs) {
          if (doc.id !== email) return res.status(400).json({ error: 'اسم المستخدم محجوز' });
        }
      } catch (e) {}
      update.username = username.trim();
      message = 'تم تحديث اسم المستخدم بنجاح';
    }
    if (avatarBase64 !== undefined && avatarBase64 !== null && typeof avatarBase64 === 'string' && avatarBase64.length <= 700000) {
      update.avatarBase64 = avatarBase64;
      message = 'تم تحديث الصورة بنجاح';
    }
    if (birthDate !== undefined) update.birthDate = birthDate;
    if (country !== undefined) update.country = country;
    
    try {
      await db.collection('users').doc(email).set(update, { merge: true });
      res.json({ success: true, message: message });
    } catch (e) { res.status(500).json({ error: 'تعذّر تحديث الملف' }); }
    return;
  }
  res.status(405).json({ error: 'Method not allowed' });
};
