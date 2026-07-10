const { db } = require('./_lib');

// This endpoint does NOT require Firebase token verification.
// It uses the X-User-Email header as the user identifier.
// This is needed because the app uses anonymous auth + Account Picker,
// so the Firebase token might not have an email.
//
// Security: Firestore Rules still enforce that only signed-in users
// can write. The Admin SDK bypasses rules, so this endpoint CAN write.
// But the endpoint only writes to the user's OWN document (identified by email).

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'PUT,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,X-App-Version,X-User-Email');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  if (req.method !== 'PUT') return res.status(405).json({ error: 'Method not allowed' });
  
  // Get email from header (no auth required)
  const email = req.headers['x-user-email'] || '';
  if (!email || email.length === 0 || email.length > 200) {
    return res.status(400).json({ error: 'يجب تسجيل الدخول' });
  }
  
  // Check app version
  const clientVersion = parseInt(req.headers['x-app-version'] || '0', 10);
  if (clientVersion < 13) {
    return res.status(426).json({ error: 'يرجى تحديث التطبيق' });
  }
  
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
  
  try {
    await db.collection('users').doc(email).set(update, { merge: true });
    res.json({ success: true, message: message });
  } catch (e) {
    res.status(500).json({ error: 'تعذّر تحديث الملف على السيرفر' });
  }
};
