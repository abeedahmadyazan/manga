const { db } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  const { commentId, commentText, reason, reportedEmail, reportedName } = req.body || {};
  if (!reason || reason.length > 200) return res.status(400).json({ error: 'سبب غير صالح' });
  if (!commentId) return res.status(400).json({ error: 'معرّف التعليق مطلوب' });
  
  // Check if already reported
  const existing = await db.collection('reports')
    .where('commentId', '==', commentId)
    .where('reportedByEmail', '==', email)
    .get();
  if (!existing.empty) return res.status(400).json({ error: 'لقد بلّغت عن هذا التعليق مسبقاً' });
  
  const userDoc = await db.collection('users').doc(email).get();
  const userData = userDoc.exists ? userDoc.data() : {};
  
  const report = {
    commentId,
    commentText: commentText || '',
    reason: reason.trim(),
    reportedEmail: reportedEmail || '',
    reportedName: reportedName || '',
    reportedByEmail: email,
    reportedByName: userData.name || email.split('@')[0],
    createdAt: Date.now(),
    resolved: false,
    resolvedBy: null,
    resolvedAt: null,
  };
  
  const docRef = await db.collection('reports').add(report);
  res.status(201).json({ id: docRef.id, ...report });
};
