const { db, admin } = require('../_lib');
const { authenticate, isAdmin, isBanned, validateText } = require('../_auth');
const { checkCooldown, updateCooldown } = require('../_rateLimit');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version,X-User-Email');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  if (req.method === 'GET') return GET(req, res);
  if (req.method === 'POST') return POST(req, res);
  if (req.method === 'PUT') return PUT(req, res);
  if (req.method === 'DELETE') return DELETE(req, res);
  res.status(405).json({ error: 'Method not allowed' });
};

async function GET(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  
  const { contextId } = req.query;
  if (!contextId || contextId.length > 200) return res.status(400).json({ error: 'سياق غير صالح' });
  
  try {
    // Simple query WITHOUT orderBy (avoids needing composite index)
    const snapshot = await db.collection('comments')
      .where('contextId', '==', contextId)
      .limit(500)
      .get();
    
    const comments = [];
    snapshot.forEach(doc => comments.push({ id: doc.id, ...doc.data() }));
    
    // Sort in JavaScript (descending by createdAt)
    comments.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    
    res.json({ comments });
  } catch (e) {
    console.error('GET comments error:', e.message);
    res.status(500).json({ error: 'تعذّر تحميل التعليقات' });
  }
}

async function POST(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email, uid } = authResult.user;
  
  if (!email || email.length === 0) {
    return res.status(400).json({ error: 'يجب تسجيل الدخول بحساب Google' });
  }
  
  const { contextId, contextType, text, parentId } = req.body || {};
  
  const textError = validateText(text);
  if (textError) return res.status(400).json({ error: textError });
  if (!contextId || contextId.length > 200) return res.status(400).json({ error: 'سياق غير صالح' });
  
  try {
    const [banned, cd, isAdminUser, userDoc] = await Promise.all([
      isBanned(email).catch(() => false),
      checkCooldown(email).catch(() => ({ allowed: true })),
      isAdmin(uid).catch(() => false),
      db.collection('users').doc(email).get().catch(() => null),
    ]);
    
    if (banned) return res.status(403).json({ error: 'تم حظر حسابك' });
    if (!cd.allowed) return res.status(429).json({ error: `انتظر ${cd.wait} ثانية` });
    
    const isReply = parentId && parentId.length > 0;
    if (!isReply) {
      // Count ONLY top-level comments (parentId == null) — replies don't
      // count toward the per-chapter limit. Previously the query counted
      // replies too, so a user with 1 comment + 1 reply was blocked.
      const userComments = await db.collection('comments')
        .where('contextId', '==', contextId)
        .where('authorEmail', '==', email)
        .where('parentId', '==', null)
        .limit(11)
        .get();
      if (userComments.size >= 10) {
        return res.status(400).json({ error: 'وصلت للحد الأقصى (10 تعليقات لكل فصل)' });
      }
    }
    
    const userData = userDoc && userDoc.exists ? userDoc.data() : {};
    
    const comment = {
      contextId,
      contextType: contextType || 'manga',
      text: text.trim(),
      authorEmail: email,
      authorName: userData.name || email.split('@')[0],
      authorAvatar: userData.avatarBase64 || '',
      isAdmin: isAdminUser,
      parentId: parentId || null,
      createdAt: Date.now(),
      likes: [],
      dislikes: [],
      editedAt: null,
    };
    
    const docRef = await db.collection('comments').add(comment);
    await updateCooldown(email);
    res.status(201).json({ id: docRef.id, ...comment });
  } catch (e) {
    console.error('POST comment error:', e.message);
    res.status(500).json({ error: 'تعذّر إضافة التعليق. حاول مرة أخرى.' });
  }
}

async function PUT(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  if (!email || email.length === 0) return res.status(400).json({ error: 'يجب تسجيل الدخول' });
  
  const { id } = req.query;
  const { text } = req.body || {};
  const textError = validateText(text);
  if (textError) return res.status(400).json({ error: textError });
  
  try {
    const docRef = db.collection('comments').doc(id);
    const doc = await docRef.get();
    if (!doc.exists) return res.status(404).json({ error: 'التعليق غير موجود' });
    if (doc.data().authorEmail !== email) return res.status(403).json({ error: 'لا يمكنك تعديل تعليق شخص آخر' });
    await docRef.update({ text: text.trim(), editedAt: Date.now() });
    res.json({ id, text: text.trim(), editedAt: Date.now() });
  } catch (e) {
    res.status(500).json({ error: 'تعذّر التعديل' });
  }
}

async function DELETE(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email, uid } = authResult.user;
  
  if (!email || email.length === 0) return res.status(400).json({ error: 'يجب تسجيل الدخول' });
  
  const { id } = req.query;
  try {
    const docRef = db.collection('comments').doc(id);
    const doc = await docRef.get();
    if (!doc.exists) return res.status(404).json({ error: 'التعليق غير موجود' });
    const data = doc.data();
    const isAdminUser = await isAdmin(uid).catch(() => false);
    if (data.authorEmail !== email && !isAdminUser) return res.status(403).json({ error: 'لا يمكنك حذف تعليق شخص آخر' });
    await docRef.delete();
    db.collection('comments').where('parentId', '==', id).get()
      .then(snap => { const b = db.batch(); snap.forEach(d => b.delete(d.ref)); b.commit(); })
      .catch(() => {});
    res.json({ success: true });
  } catch (e) {
    res.status(500).json({ error: 'تعذّر الحذف' });
  }
}
