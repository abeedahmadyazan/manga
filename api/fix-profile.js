const { db } = require('./_lib');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  
  // Force update yznabyd@gmail.com profile
  try {
    await db.collection('users').doc('yznabyd@gmail.com').set({
      name: 'YZ',
      username: '@yznabyd',
      lastUpdated: Date.now()
    }, { merge: true });
    
    // Read back to verify
    const doc = await db.collection('users').doc('yznabyd@gmail.com').get();
    res.json({
      success: true,
      message: 'Profile fixed',
      currentData: doc.exists ? {
        name: doc.data().name,
        username: doc.data().username,
        lastUpdated: doc.data().lastUpdated
      } : null
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
};
