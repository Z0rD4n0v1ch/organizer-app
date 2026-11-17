// Проверка доступности: подписи у кнопок и полей, зоны нажатия от 40 px, контраст текста (WCAG 4.5:1 / 3:1).
// Запуск: раздать папку app/src/main/assets на 127.0.0.1:8766 и выполнить  node tools/a11y-audit.js [dark]
const {chromium}=require('playwright');
const dark=process.argv[2]==='dark';
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:390,height:844},colorScheme:dark?'dark':'light'});p.on('dialog',d=>d.accept());
await p.goto('http://127.0.0.1:8766/index.html');await p.waitForTimeout(900);
const views=['today','objects','catalog','more','hours','money','car','stock','notes','mine','tasks','calc'];
const agg={name:{},small:{},contrast:{}};
for(const v of views){ await p.evaluate(v=>document.querySelector('nav button[data-v="'+v+'"]').click(),v); await p.waitForTimeout(250);
 if(v==='objects'){ await p.click('#objList .obj'); await p.waitForTimeout(300); }
 const r=await p.evaluate(()=>{
  const vis=e=>{const s=getComputedStyle(e);const rc=e.getBoundingClientRect();return rc.width>0&&rc.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&rc.bottom>0&&rc.top<innerHeight*3};
  const lab=e=>e.getAttribute('aria-label')||e.textContent.trim()||e.title||(e.labels&&e.labels[0]&&e.labels[0].textContent.trim())||e.getAttribute('placeholder');
  const key=e=>(e.id?'#'+e.id:e.tagName.toLowerCase()+(e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\s+/).slice(0,2).join('.'):''));
  const out={name:[],small:[],contrast:[]};
  document.querySelectorAll('button,input,select,textarea,a[href]').forEach(e=>{ if(!vis(e)||e.type==='hidden'||e.closest('[hidden]')) return;
    if(!lab(e)) out.name.push(key(e));
    const rc=e.getBoundingClientRect(); if(!e.matches('.link,input[type=checkbox],input[type=radio],a')&&(rc.width<40||rc.height<40)) out.small.push(key(e)+' '+Math.round(rc.width)+'x'+Math.round(rc.height)); });
  const rgb=s=>{const m=s.match(/[\d.]+/g);return m?m.map(Number):[0,0,0,0]};
  const L=c=>{const f=x=>{x/=255;return x<=.03928?x/12.92:Math.pow((x+.055)/1.055,2.4)};return .2126*f(c[0])+.7152*f(c[1])+.0722*f(c[2])};
  const bgOf=e=>{for(let x=e;x;x=x.parentElement){const c=rgb(getComputedStyle(x).backgroundColor);if(c[3]===undefined||c[3]>0.5) return c;} return rgb(getComputedStyle(document.body).backgroundColor);};
  document.querySelectorAll('.view.on *').forEach(e=>{ if(!vis(e)||e.closest('[hidden]')) return; const tn=[...e.childNodes].some(n=>n.nodeType===3&&n.textContent.trim()); if(!tn) return;
    const s=getComputedStyle(e); if(+s.opacity<1) return; const fg=rgb(s.color), bg=bgOf(e); const a=L(fg),c=L(bg); const cr=(Math.max(a,c)+.05)/(Math.min(a,c)+.05);
    const big=parseFloat(s.fontSize)>=24||(parseFloat(s.fontSize)>=18.6&&+s.fontWeight>=700);
    if(cr<(big?3:4.5)) out.contrast.push(key(e)+' '+cr.toFixed(2)+' «'+e.textContent.trim().slice(0,24)+'»'); });
  return out; });
 for(const k of Object.keys(agg)) r[k].forEach(x=>{ const kk=x.replace(/ [\d.]+(x\d+)?( «.*»)?$/,''); agg[k][kk]=agg[k][kk]||(v+': '+x); });
}
for(const k of Object.keys(agg)){ console.log('== '+k+' ('+Object.keys(agg[k]).length+')'); Object.values(agg[k]).slice(0,40).forEach(x=>console.log('  '+x)); }
await b.close();})();
