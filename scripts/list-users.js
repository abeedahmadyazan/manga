// List all users in Firestore to inspect birthDate + lastBirthDateChange + lastNameChange.
// Usage: node list-users.js <service-account.json>
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');

const sa = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const project = sa.project_id;

function base64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function makeJwt() {
  const header = { alg: 'RS256', typ: 'JWT', kid: sa.private_key_id };
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/cloud-platform',
    aud: sa.token_uri, iat: now, exp: now + 3600,
  };
  const enc = base64url(JSON.stringify(header)) + '.' + base64url(JSON.stringify(payload));
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(enc); signer.end();
  return enc + '.' + base64url(signer.sign(sa.private_key));
}
function httpsGet(host, path, headers) {
  return new Promise((resolve, reject) => {
    https.get({ host, path, headers }, (res) => {
      let data = ''; res.on('data', c => data += c); res.on('end', () => resolve({ status: res.statusCode, body: data }));
    }).on('error', reject);
  });
}

function fieldVal(f) {
  if (!f) return undefined;
  if (f.stringValue !== undefined) return f.stringValue;
  if (f.integerValue !== undefined) return parseInt(f.integerValue, 10);
  if (f.booleanValue !== undefined) return f.booleanValue;
  if (f.timestampValue !== undefined) return f.timestampValue;
  if (f.nullValue !== undefined) return null;
  return JSON.stringify(f);
}

(async () => {
  const jwt = makeJwt();
  const tb = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${encodeURIComponent(jwt)}`;
  const tr = await new Promise((res, rej) => {
    const r = https.request({ host: 'oauth2.googleapis.com', path: '/token', method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(tb) } },
      (x) => { let d=''; x.on('data',c=>d+=c); x.on('end',()=>res({status:x.statusCode,body:d})); });
    r.on('error', rej); r.write(tb); r.end();
  });
  const at = JSON.parse(tr.body).access_token;

  // list all user docs
  const r = await httpsGet('firestore.googleapis.com',
    `/v1/projects/${project}/databases/(default)/documents/users`,
    { 'Authorization': `Bearer ${at}` });
  if (r.status !== 200) { console.error('list failed:', r.status, r.body.substring(0,300)); process.exit(1); }
  const docs = JSON.parse(r.body).documents || [];
  console.log(`Found ${docs.length} users:\n`);
  const now = Date.now();
  for (const doc of docs) {
    const f = doc.fields || {};
    const email = fieldVal(f.email) || doc.name.split('/').pop();
    const name = fieldVal(f.name) || '';
    const birthDate = fieldVal(f.birthDate) ?? '';
    const lastBirth = fieldVal(f.lastBirthDateChange) || 0;
    const lastNameCh = fieldVal(f.lastNameChange) || fieldVal(f.lastUsernameChange) || 0;
    const country = fieldVal(f.country) || '';
    const birthAgo = lastBirth ? `${Math.round((now - lastBirth) / 60000)} min ago` : 'never';
    const nameAgo = lastNameCh ? `${Math.round((now - lastNameCh) / 60000)} min ago` : 'never';
    console.log(`📧 ${email}`);
    console.log(`   name: "${name}" | country: "${country}"`);
    console.log(`   birthDate: "${birthDate}" | lastBirthDateChange: ${birthAgo}`);
    console.log(`   lastNameChange: ${nameAgo}`);
    console.log('');
  }
})().catch(e => { console.error('ERROR:', e); process.exit(1); });
