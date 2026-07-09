const { db } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
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
    
    // Validate name
    if (name !== undefined) {
      if (typeof name !== 'string' || name.length < 1 || name.length > 50) {
        return res.status(400).json({ error: 'اسم غير صالح (1-50 حرف)' });
      }
    }
    
    // Validate username
    if (username !== undefined) {
      if (typeof username !== 'string' || username.length < 3 || username.length > 30) {
        return res.status(400).json({ error: 'اسم المستخدم غير صالح (3-30 حرف)' });
      }
      // Check uniqueness
      const existing = await db.collection('users')
        .where('username', '==', username)
        .get();
      for (const doc of existing.docs) {
        if (doc.id !== email) {
          return res.status(400).json({ error: 'اسم المستخدم محجوز' });
        }
      }
    }
    
    // Validate avatar size (max 500KB base64 ~ 700K chars)
    if (avatarBase64 !== undefined) {
      if (typeof avatarBase64 !== 'string' || avatarBase64.length > 700000) {
        return res.status(400).json({ error: 'حجم الصورة كبير جداً' });
      }
    }
    
    // Validate birthDate
    if (birthDate !== undefined && birthDate !== '') {
      if (typeof birthDate !== 'string' || birthDate.length > 20) {
        return res.status(400).json({ error: 'تاريخ ميلاد غير صالح' });
      }
    }
    
    // Validate country
    if (country !== undefined && country !== '') {
      if (typeof country !== 'string' || country.length > 50) {
        return res.status(400).json({ error: 'دولة غير صالحة' });
      }
    }
    
    // Build update object (only allowed fields)
    const update = { lastUpdated: Date.now() };
    if (name !== undefined) update.name = name.trim();
    if (username !== undefined) update.username = username.trim();
    if (avatarBase64 !== undefined) update.avatarBase64 = avatarBase64;
    if (birthDate !== undefined) update.birthDate = birthDate;
    if (country !== undefined) update.country = country;
    
    try {
      // Use set with merge to keep existing fields
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
