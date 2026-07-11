const { db, admin } = require('../_lib');
const { authenticate } = require('../_auth');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'null');
  res.setHeader('Access-Control-Allow-Methods', 'POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  
  const authResult = await authenticate(req);
  if (authResult.error) return res.status(authResult.error.status).json({ error: authResult.error.message });
  const { email } = authResult.user;
  
  const { id } = req.query;
  const { type } = req.body || {};
  if (type !== 'like' && type !== 'dislike') return res.status(400).json({ error: 'نوع غير صالح' });
  
  const docRef = db.collection('comments').doc(id);
  const doc = await docRef.get();
  if (!doc.exists) return res.status(404).json({ error: 'التعليق غير موجود' });
  
  const data = doc.data();
  const likes = data.likes || [];
  const dislikes = data.dislikes || [];
  
  if (type === 'like') {
    if (likes.includes(email)) {
      await docRef.update({ likes: admin.firestore.FieldValue.arrayRemove(email) });
    } else {
      await docRef.update({
        likes: admin.firestore.FieldValue.arrayUnion(email),
        dislikes: admin.firestore.FieldValue.arrayRemove(email),
      });
    }
  } else {
    if (dislikes.includes(email)) {
      await docRef.update({ dislikes: admin.firestore.FieldValue.arrayRemove(email) });
    } else {
      await docRef.update({
        dislikes: admin.firestore.FieldValue.arrayUnion(email),
        likes: admin.firestore.FieldValue.arrayRemove(email),
      });
    }
  }
  
  const updated = await docRef.get();
  const updatedData = updated.data();
  res.json({ likes: updatedData.likes || [], dislikes: updatedData.dislikes || [] });
};
