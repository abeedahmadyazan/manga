module.exports = (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'null');
  res.setHeader('Access-Control-Allow-Headers', 'Authorization,Content-Type,X-App-Version');
  
  // Log what we received (for debugging)
  const info = {
    status: 'ok',
    timestamp: Date.now(),
    receivedVersion: req.headers['x-app-version'] || 'none',
    hasAuth: !!req.headers.authorization,
    method: req.method,
  };
  
  res.json(info);
};
