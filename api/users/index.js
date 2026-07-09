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
  
  if (!email || email.length === 0) {
    return res.status(400).json({ error: 'يجب تسجيل الدخول بحساب Google' });
  }
  
  // GET /api/users?email=xxx
  if (req.method === 'GET') {
    const targetEmail = req.query.email || email;
    if (!targetEmail || targetEmail.length > 200) {
      return res.status(400).json({ error: 'إيميل غير صالح' });
    }
    try {
      const doc = await db.collection('users').doc(targetEmail).get();
      if (!doc.exists) return res.status(404).json({ error: 'المستخدم غير موجود' });
      res.json({ user: { email: targetEmail, ...doc.data() } });
    } catch (e) {
      res.status(500).json({ error: 'تعذّر تحميل البيانات' });
    }
    return;
  }
  
  // PUT /api/users - update own profile
  if (req.method === 'PUT') {
    const { name, username, avatarBase64, birthDate, country } = req.body || {};
    
    // Validate name (but don't reject if empty - just skip)
    if (name !== undefined && name !== null) {
      if (typeof name !== 'string' || name.length > 50) {
        return res.status(400).json({ error: 'اسم غير صالح' });
      }
    }
    
    // Validate username (but don't reject if empty - just skip)
    if (username !== undefined && username !== null) {
      if (typeof username !== 'string' || username.length > 30) {
        return res.status(400).json({ error: 'اسم المستخدم غير صالح' });
      }
      
      // Check uniqueness - but don't fail if the check itself fails
      try {
        const existing = await db.collection('users')
          .where('username', '==', username)
          .limit(1)
          .get();
        for (const doc of existing.docs) {
          if (doc.id !== email) {
            return res.status(400).json({ error: 'اسم المستخدم محجوز' });
          }
        }
      } catch (e) {
        // If uniqueness check fails, proceed anyway (better to save than lose data)
        console.error('Username uniqueness check failed:', e.message);
      }
    }
    
    // Validate avatar size
    if (avatarBase64 !== undefined && avatarBase64 !== null) {
      if (typeof avatarBase64 !== 'string' || avatarBase64.length > 700000) {
        return res.status(400).json({ error: 'حجم الصورة كبير جداً' });
      }
    }
    
    // Build update object
    const update = { lastUpdated: Date.now() };
    if (name !== undefined && name !== null && name.length > 0) update.name = name.trim();
    if (username !== undefined && username !== null && username.length > 0) update.username = username.trim();
    if (avatarBase64 !== undefined && avatarBase64 !== null) update.avatarBase64 = avatarBase64;
    if (birthDate !== undefined && birthDate !== null) update.birthDate = birthDate;
    if (country !== undefined && country !== null) update.country = country;
    
    try {
      await db.collection('users').doc(email).set(update, { merge: true });
      res.json({ success: true, ...update });
    } catch (e) {
      console.error('Profile update error:', e.message);
      res.status(500).json({ error: 'تعذّر تحديث الملف' });
    }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
