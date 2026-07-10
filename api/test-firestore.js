const { db } = require('./_lib');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  
  const result = {};
  
  // Test 1: Can we READ from Firestore?
  try {
    const doc = await db.collection('users').doc('yznabyd@gmail.com').get();
    result.readTest = {
      success: true,
      exists: doc.exists,
      data: doc.exists ? {
        name: doc.data().name,
        username: doc.data().username,
        hasAvatar: !!doc.data().avatarBase64,
        lastUpdated: doc.data().lastUpdated
      } : null
    };
  } catch (e) {
    result.readTest = { success: false, error: e.message };
  }
  
  // Test 2: Can we WRITE to Firestore?
  try {
    await db.collection('users').doc('test_write@test.com').set({
      test: true,
      timestamp: Date.now()
    });
    result.writeTest = { success: true };
    
    // Clean up
    await db.collection('users').doc('test_write@test.com').delete();
    result.cleanupTest = { success: true };
  } catch (e) {
    result.writeTest = { success: false, error: e.message };
  }
  
  res.json(result);
};
