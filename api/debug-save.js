const { db } = require('./_lib');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();

  if (req.method === 'POST') {
    const { name, email } = req.body || {};
    if (!email || !name) return res.status(400).json({ error: 'email and name required' });
    
    try {
      // Try to write
      await db.collection('users').doc(email).set({
        name: name,
        lastUpdated: Date.now()
      }, { merge: true });
      
      // Read back
      const doc = await db.collection('users').doc(email).get();
      res.json({
        success: true,
        savedName: name,
        readBack: doc.exists ? doc.data().name : 'not found'
      });
    } catch (e) {
      res.status(500).json({ error: e.message, stack: e.stack });
    }
    return;
  }

  if (req.method === 'GET') {
    try {
      const doc = await db.collection('users').doc('yznabyd@gmail.com').get();
      res.json({
        exists: doc.exists,
        name: doc.exists ? doc.data().name : null,
        allFields: doc.exists ? Object.keys(doc.data()) : []
      });
    } catch (e) {
      res.status(500).json({ error: e.message });
    }
    return;
  }
};
