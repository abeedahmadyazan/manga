// Normalize existing birthDate values that were saved with Arabic-Indic digits.
// Converts "٢٠٠٩-٠٩-٢٤" → "2009-09-24" in Firestore users collection.
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');

const sa = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const project = sa.project_id;

function base64url(b){return Buffer.from(b).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');}
function makeJwt(){
  const h={alg:'RS256',typ:'JWT',kid:sa.private_key_id};
  const n=Math.floor(Date.now()/1000);
  const p={iss:sa.client_email,scope:'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/cloud-platform',aud:sa.token_uri,iat:n,exp:n+3600};
  const e=base64url(JSON.stringify(h))+'.'+base64url(JSON.stringify(p));
  const s=crypto.createSign('RSA-SHA256');s.update(e);s.end();
  return e+'.'+base64url(s.sign(sa.private_key));
}
function req(method,host,path,headers,body){
  return new Promise((res,rej)=>{
    const o={method,host,path,headers:headers||{}};
    const r=https.request(o,(x)=>{let d='';x.on('data',c=>d+=c);x.on('end',()=>res({status:x.statusCode,body:d}));});
    r.on('error',rej);if(body)r.write(body);r.end();
  });
}
function fieldVal(f){if(!f)return undefined;if(f.stringValue!==undefined)return f.stringValue;if(f.integerValue!==undefined)return parseInt(f.integerValue,10);return JSON.stringify(f);}
function normalizeDigits(s){return s.replace(/[\u0660-\u0669\u06F0-\u06F9]/g,(c)=>{const base=c.charCodeAt(0)>=0x06F0?0x06F0:0x0660;return String(c.charCodeAt(0)-base);});}

(async()=>{
  const jwt=makeJwt();
  const tb=`grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${encodeURIComponent(jwt)}`;
  const tr=await req('POST','oauth2.googleapis.com','/token',{'Content-Type':'application/x-www-form-urlencoded','Content-Length':Buffer.byteLength(tb)},tb);
  const at=JSON.parse(tr.body).access_token;
  console.log('✅ token obtained');

  const r=await req('GET','firestore.googleapis.com',`/v1/projects/${project}/databases/(default)/documents/users`,{'Authorization':`Bearer ${at}`});
  const docs=JSON.parse(r.body).documents||[];
  console.log(`Found ${docs.length} users`);

  let fixed=0;
  for(const doc of docs){
    const f=doc.fields||{};
    const email=fieldVal(f.email)||doc.name.split('/').pop();
    const birthRaw=f.birthDate && f.birthDate.stringValue;
    if(!birthRaw) continue;
    const norm=normalizeDigits(birthRaw);
    if(norm!==birthRaw){
      console.log(`📧 ${email}: "${birthRaw}" → "${norm}"`);
      // patch the document: set birthDate to the normalized value
      const patchBody=JSON.stringify({fields:{birthDate:{stringValue:norm}}});
      const pr=await req('PATCH','firestore.googleapis.com',
        `/v1/${doc.name}?updateMask.fieldPaths=birthDate`,
        {'Authorization':`Bearer ${at}`,'Content-Type':'application/json','Content-Length':Buffer.byteLength(patchBody)},
        patchBody);
      if(pr.status===200){console.log('   ✅ fixed');fixed++;}
      else{console.log('   ❌ failed:',pr.status,pr.body.substring(0,200));}
    }
  }
  console.log(`\nDone. Fixed ${fixed} user(s).`);
})().catch(e=>{console.error('ERROR:',e);process.exit(1);});
