const { db, auth } = require('./_lib');

// Profile endpoint — REQUIRES Firebase token (no more X-User-Email)
const ADMIN_EMAIL = 'yznabyd@gmail.com'; // Server-side only, NOT in APK!

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,X-App-Version,Authorization');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  const clientVersion = parseInt(req.headers['x-app-version'] || '0', 10);
  if (clientVersion < 13) return res.status(426).json({ error: 'يرجى تحديث التطبيق' });
  
  // REQUIRE Firebase token
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'مطلوب تسجيل الدخول' });
  }
  const idToken = authHeader.split('Bearer ')[1];
  
  let decoded;
  try {
    decoded = await auth.verifyIdToken(idToken);
  } catch (e) {
    return res.status(401).json({ error: 'رمز غير صالح' });
  }
  
  const uid = decoded.uid;
  let email = decoded.email || '';
  
  // If no email in token, try Admin SDK
  if (!email) {
    try {
      const userRecord = await auth.getUser(uid);
      email = userRecord.email || '';
    } catch (e) {}
  }
  
  if (!email || email.length === 0) {
    return res.status(400).json({ error: 'يجب تسجيل الدخول بحساب Google' });
  }
  
  // Check if admin (server-side, NOT in APK)
  const isAdmin = (email === ADMIN_EMAIL);
  
  // GET /api/profile?email=xxx
  if (req.method === 'GET') {
    const targetEmail = req.query.email || email;
    try {
      const doc = await db.collection('users').doc(targetEmail).get();
      if (!doc.exists) return res.status(404).json({ error: 'المستخدم غير موجود' });
      res.json({ user: { email: targetEmail, ...doc.data() } });
    } catch (e) { res.status(500).json({ error: 'تعذّر تحميل البيانات' }); }
    return;
  }
  
  // PUT /api/profile
  if (req.method === 'PUT') {
    const { name, username, avatarBase64, birthDate, country } = req.body || {};
    const update = { lastUpdated: Date.now() };
    let message = 'تم التحديث';
    
    if (name !== undefined && name !== null && typeof name === 'string' && name.length > 0 && name.length <= 50) {
      update.name = name.trim();
      message = 'تم تحديث الاسم بنجاح';
    }
    if (username !== undefined && username !== null && typeof username === 'string' && username.length > 0 && username.length <= 30) {
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
    
    // If admin, set isAdmin in the user doc
    if (isAdmin) update.isAdmin = true;
    
    try {
      // Check if the user doc already exists. If not, this is a NEW user —
      // set createdAt so the profile shows the correct join date instead of
      // 1970 (which happens when createdAt is missing → defaults to 0L).
      const existingDoc = await db.collection('users').doc(email).get();
      if (!existingDoc.exists) {
        update.createdAt = Date.now();
      }
      await db.collection('users').doc(email).set(update, { merge: true });
      
      // If admin, also create admins/{uid} doc (server-side only)
      if (isAdmin) {
        await db.collection('admins').doc(uid).set({
          email: email,
          role: 'admin',
          bootstrapAt: Date.now()
        }, { merge: true });
      }
      
      res.json({ success: true, message: message, isAdmin: isAdmin });
    } catch (e) { res.status(500).json({ error: 'تعذّر تحديث الملف' }); }
    return;
  }
  
  res.status(405).json({ error: 'Method not allowed' });
};
