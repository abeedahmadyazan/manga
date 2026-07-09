module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  
  const checks = {};
  
  // Check env vars
  checks.envVars = {
    FIREBASE_PROJECT_ID: !!process.env.FIREBASE_PROJECT_ID,
    FIREBASE_CLIENT_EMAIL: !!process.env.FIREBASE_CLIENT_EMAIL,
    FIREBASE_PRIVATE_KEY: !!process.env.FIREBASE_PRIVATE_KEY,
    PRIVATE_KEY_LENGTH: process.env.FIREBASE_PRIVATE_KEY ? process.env.FIREBASE_PRIVATE_KEY.length : 0,
    PRIVATE_KEY_HAS_NEWLINES: process.env.FIREBASE_PRIVATE_KEY ? process.env.FIREBASE_PRIVATE_KEY.includes('\\n') : false,
    PRIVATE_KEY_HAS_REAL_NEWLINES: process.env.FIREBASE_PRIVATE_KEY ? process.env.FIREBASE_PRIVATE_KEY.includes('\n') : false,
  };
  
  // Try Firebase init
  try {
    const { db } = require('./_lib');
    // Try a simple Firestore read
    const snapshot = await db.collection('admins').limit(1).get();
    checks.firestoreRead = 'OK (' + snapshot.size + ' admin docs)';
  } catch (e) {
    checks.firestoreError = e.message;
    checks.firestoreErrorCode = e.code;
  }
  
  res.json(checks);
};
