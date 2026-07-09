const { db } = require('../_lib');
const { authenticate, isAdmin, isBanned, validateText } = require('../_auth');
const { checkCooldown, updateCooldown } = require('../_rateLimit');

// GET /api/comments?contextId=xxx
async function GET(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  
  const { contextId } = req.query;
  if (!contextId || contextId.length > 200) {
    return res.status(400).json({ error: 'سياق غير صالح' });
  }
  
  try {
    const snapshot = await db.collection('comments')
      .where('contextId', '==', contextId)
      .orderBy('createdAt', 'desc')
      .limit(500)
      .get();
    
    const comments = [];
    snapshot.forEach(doc => comments.push({ id: doc.id, ...doc.data() }));
    res.json({ comments });
  } catch (e) {
    res.status(500).json({ error: 'تعذّر تحميل التعليقات' });
  }
}

// POST /api/comments
async function POST(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email, uid } = authResult.user;
  
  const { contextId, contextType, text, parentId } = req.body || {};
  const textError = validateText(text);
  if (textError) return res.status(400).json({ error: textError });
  if (!contextId || contextId.length > 200) return res.status(400).json({ error: 'سياق غير صالح' });
  
  if (await isBanned(email)) return res.status(403).json({ error: 'تم حظر حسابك' });
  
  const cd = await checkCooldown(email);
  if (!cd.allowed) return res.status(429).json({ error: `انتظر ${cd.wait} ثانية` });
  
  const isReply = parentId && parentId.length > 0;
  if (!isReply) {
    const userComments = await db.collection('comments')
      .where('contextId', '==', contextId)
      .where('authorEmail', '==', email)
      .get();
    if (userComments.size >= 2) return res.status(400).json({ error: 'وصلت للحد الأقصى (2 لكل فصل)' });
  }
  
  const userDoc = await db.collection('users').doc(email).get();
  const userData = userDoc.exists ? userDoc.data() : {};
  const isAdminUser = await isAdmin(uid);
  
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
}

// PUT /api/comments?id=xxx
async function PUT(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  const { id } = req.query;
  const { text } = req.body || {};
  const textError = validateText(text);
  if (textError) return res.status(400).json({ error: textError });
  
  const docRef = db.collection('comments').doc(id);
  const doc = await docRef.get();
  if (!doc.exists) return res.status(404).json({ error: 'التعليق غير موجود' });
  
  const data = doc.data();
  if (data.authorEmail !== email) return res.status(403).json({ error: 'لا يمكنك تعديل تعليق شخص آخر' });
  
  await docRef.update({ text: text.trim(), editedAt: Date.now() });
  res.json({ id, text: text.trim(), editedAt: Date.now() });
}

// DELETE /api/comments?id=xxx
async function DELETE(req, res) {
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email, uid } = authResult.user;
  
  const { id } = req.query;
  const docRef = db.collection('comments').doc(id);
  const doc = await docRef.get();
  if (!doc.exists) return res.status(404).json({ error: 'التعليق غير موجود' });
  
  const data = doc.data();
  const isAdminUser = await isAdmin(uid);
  if (data.authorEmail !== email && !isAdminUser) {
    return res.status(403).json({ error: 'لا يمكنك حذف تعليق شخص آخر' });
  }
  
  await docRef.delete();
  // Also delete replies
  const replies = await db.collection('comments').where('parentId', '==', id).get();
  const batch = db.batch();
  replies.forEach(d => batch.delete(d.ref));
  await batch.commit();
  
  res.json({ success: true });
}

module.exports = async (req, res) => {
  // Set CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  
  if (req.method === 'GET') return GET(req, res);
  if (req.method === 'POST') return POST(req, res);
  if (req.method === 'PUT') return PUT(req, res);
  if (req.method === 'DELETE') return DELETE(req, res);
  res.status(405).json({ error: 'Method not allowed' });
};
