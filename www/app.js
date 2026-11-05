(()=>{'use strict';
const $=id=>document.getElementById(id);
const state={secret:'73+27',tab:'home',mode:'camera',lens:'back',files:[],active:false,saving:false,lastSavedAt:0,lastError:'',unprocessedFiles:0,synced:false,startedAt:0,currentId:null,preview:null};
try{const v=localStorage.getItem('calculator_secret_v2');if(v&&/^[0-9.+*/-]{3,40}$/.test(v))state.secret=v}catch{}
const isAndroid=()=>!!(window.Capacitor&&window.Capacitor.isNativePlatform&&window.Capacitor.isNativePlatform());
const native=(method,args={})=>{
 if(!isAndroid())return Promise.reject(new Error('Snimanje radi samo u instaliranoj Android aplikaciji. Ovo je web pregled.'));
 return window.Capacitor.nativePromise('SecureRecorder',method,args);
};
let expression='',solved=false,unlocked=false,toastTimer=null,pollBusy=false;
const esc=v=>String(v??'').replace(/[&<>"']/g,s=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[s]));
const tformat=s=>{s=Math.max(0,Math.floor(s));return [s/3600|0,(s%3600)/60|0,s%60].map(v=>String(v).padStart(2,'0')).join(':')};
const toast=m=>{$('toast').textContent=m;$('toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('show'),4300)};
function calcRender(history){$('calcHistory').textContent=history||'';$('calcResult').textContent=expression||'0'}
function evalCalc(v){if(!/^[\d.+*/-]+$/.test(v)||v.length>65)return null;let toks=v.match(/\d+(?:\.\d+)?|[+*/-]/g);if(!toks||toks.join('')!==v)return null;try{const n=Function('"use strict";return ('+v+')')();return Number.isFinite(n)?String(Number(n.toPrecision(11))):null}catch{return null}}
function calcPress(k){
 if(k==='C'){expression='';solved=false;calcRender();return}
 if(k==='DEL'){expression=expression.slice(0,-1);solved=false;calcRender();return}
 if(k==='='){
  if(!expression)return;
  const old=expression;
  if(old===state.secret){unlock();return}
  const n=evalCalc(old);if(n===null){$('calcHistory').textContent='Neispravan izraz';return}
  expression=n;solved=true;calcRender(old.replaceAll('*','×').replaceAll('/','÷')+' =');return;
 }
 if(k==='%'){let n=Number(expression);if(expression&&Number.isFinite(n)&&/^[-]?\d+\.?\d*$/.test(expression)){expression=String(n/100);calcRender()}return}
 if(k==='sign'){if(/^[-]?\d+(\.\d+)?$/.test(expression)){expression=String(-Number(expression));calcRender()}return}
 if(/^\d$/.test(k)||k==='.'){if(solved){expression='';solved=false};let part=expression.split(/[+*/-]/).pop()||'';if(k==='.'&&part.includes('.'))return;if(k==='.'&&!part)expression+='0';expression+=k;calcRender();return}
 if('+-*/'.includes(k)){if(!expression){if(k==='-')expression='-';calcRender();return};solved=false;if(/[+*/-]$/.test(expression))expression=expression.slice(0,-1);expression+=k;calcRender()}
}
function unlock(){unlocked=true;$('calcPage').classList.remove('active');$('vaultPage').classList.add('active');tab('home');refresh()}
function lock(){unlocked=false;closeFile();$('calcPage').classList.add('active');$('vaultPage').classList.remove('active');expression='';solved=false;calcRender()}
$('keypad').addEventListener('click',e=>{let b=e.target.closest('[data-key]');if(b)calcPress(b.dataset.key)});
document.addEventListener('keydown',e=>{if(unlocked||['INPUT','TEXTAREA'].includes(document.activeElement?.tagName))return;let k=e.key;if(/^[0-9.]$/.test(k)||['+','-','*','/','%'].includes(k)){calcPress(k);e.preventDefault()}else if(k==='Enter'||k==='='){calcPress('=');e.preventDefault()}else if(k==='Escape'){calcPress('C');e.preventDefault()}else if(k==='Backspace'){calcPress('DEL');e.preventDefault()}});
$('calcMenu').onclick=()=>toast('Calculator • privatni prikaz');
function tab(name){state.tab=name;const labels={home:'Kontrolni centar',vault:'Privatni sef',settings:'Postavke'};for(let k of Object.keys(labels))$('screen'+k[0].toUpperCase()+k.slice(1)).style.display=k===name?'block':'none';$('pageHeading').textContent=labels[name];document.querySelectorAll('.nav-btn').forEach(x=>x.classList.toggle('active',x.dataset.tab===name));if(name==='settings')$('secretInput').value=state.secret;if(name==='vault')renderFiles()}
document.querySelectorAll('.nav-btn').forEach(x=>x.addEventListener('click',()=>tab(x.dataset.tab)));
$('openVaultQuick').onclick=()=>tab('vault');$('seeAll').onclick=()=>tab('vault');$('hideQuick').onclick=lock;$('lockNow').onclick=lock;
function setMode(m){if(state.active||state.saving){toast('Pričekaj završetak snimanja i spremanja.');return}state.mode=m;$('cameraMode').classList.toggle('selected',m==='camera');$('screenMode').classList.toggle('selected',m==='screen');$('lensRow').style.display=m==='camera'?'flex':'none';$('recordDescription').textContent=m==='screen'?'Android će zatražiti odobrenje snimanja ekrana pri svakom pokretanju.':'Odaberi prednju ili zadnju kameru. Snimanje se nastavlja u pozadini uz sistemsku obavijest.'}
$('cameraMode').onclick=()=>setMode('camera');$('screenMode').onclick=()=>setMode('screen');
function setLens(l){if(state.active){toast('Za promjenu kamere prvo zaustavi snimanje.');return}state.lens=l;$('lensBack').classList.toggle('selected',l==='back');$('lensFront').classList.toggle('selected',l==='front')}
$('lensBack').onclick=()=>setLens('back');$('lensFront').onclick=()=>setLens('front');
function renderRecord(){
 const s=state.active?Math.max(0,(Date.now()-state.startedAt)/1000):0;
 $('recordTimer').textContent=tformat(s);$('recordPanel').classList.toggle('is-recording',state.active);
 $('recordState').textContent=state.saving?'ŠIFRIRANJE SNIMKA':state.active?'SNIMANJE U TOKU':'SPREMNO ZA SNIMANJE';
 $('recordTitle').textContent=state.saving?'Spremanje u sef...':state.active?'Snimanje aktivno':'Pokreni snimanje';
 if(state.active)$('recordDescription').textContent='Snimanje traje uz obavijest 正在录制视频. Automatski prestaje poslije 20 minuta neaktivnosti.';
 $('recordButton').classList.toggle('stop',state.active);
 $('recordButton').innerHTML=state.saving?'Pričekaj, snimak se šifrira...':state.active?'■ &nbsp; Zaustavi snimanje':'● &nbsp; Započni snimanje';
 $('recordButton').disabled=state.saving;
}
$('recordButton').onclick=async()=>{
 if(!isAndroid()){toast('Ovo je pregled dizajna. Stvarno snimanje dostupno je u instaliranoj Android aplikaciji.');return}
 $('recordButton').disabled=true;
 try{const stopping=state.active;if(stopping)await native('stop');else await native(state.mode==='camera'?'startCamera':'startScreen',state.mode==='camera'?{lens:state.lens}:{});await refresh();toast(stopping?'Snimanje se zaustavlja; snimak se pohranjuje u sef.':'Pokretanje zatraženo. Android će prikazati status snimanja.')}catch(e){toast('Greška: '+(e.message||e))}finally{$('recordButton').disabled=state.saving}
};
function videoIcon(){return '<svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="5" width="13" height="14" rx="2"/><path d="m16 10 5-3v10l-5-3"/></svg>'}
function fileMarkup(f){return `<div class="file-row tap-row" data-file-id="${esc(f.id)}" tabindex="0" role="button"><div class="file-icon">${videoIcon()}</div><div class="file-main"><div class="file-title">${esc(f.name)}</div><div class="file-meta">${f.type==='screen'?'Ekran':(f.lens==='front'?'Prednja kamera':'Zadnja kamera')} · ${tformat(f.durationSec)} · ${new Date(f.createdAt).toLocaleDateString('bs-BA')}</div><div class="size-label">${Number(f.sizeBytes/1048576).toFixed(1)} MB · šifrirano</div></div><span class="lock-mini">◆</span><span class="file-action">›</span></div>`}
function renderFiles(){let a=state.files;let filtered=a.filter(f=>f.name.toLowerCase().includes(($('fileSearch').value||'').toLowerCase()));$('fileCountHome').textContent=a.length+' zapisa';$('vaultFileCount').textContent=a.length+' datoteka';$('recentHome').innerHTML=a.length?a.slice(0,3).map(fileMarkup).join(''):'<div class="empty">Još nema snimaka. Pokreni snimanje u Android aplikaciji.</div>';$('vaultList').innerHTML=filtered.length?filtered.map(fileMarkup).join(''):'<div class="empty">Nema zapisa za prikaz.</div>'}
$('fileSearch').addEventListener('input',renderFiles);
$('recoverRecords').onclick=async()=>{
 if(!isAndroid())return toast('Oporavak je dostupan u Android aplikaciji.');
 const btn=$('recoverRecords');btn.disabled=true;btn.textContent='Tražim preostale snimke...';
 try{const r=await native('recover');await refresh();toast(r.recovered?`Obnovljeno: ${r.recovered}. Preostalo: ${r.remaining}.`:`Nema obnovljivih MP4 snimaka. Preostale neispravne datoteke: ${r.remaining}.`)}
 catch(e){toast('Oporavak nije uspio: '+(e.message||e))}
 finally{btn.disabled=false;btn.textContent='Provjeri i obnovi pronađene snimke'}
};
async function openFile(id){let f=state.files.find(x=>x.id===id);if(!f)return;state.currentId=id;$('modalTitle').textContent=f.name;$('modalInfo').textContent='Privatni pregled: video se privremeno dešifrira u privatnu memoriju aplikacije. Nije vidljiv u galeriji.';$('modalType').textContent=f.type==='screen'?'Ekran':(f.lens==='front'?'Prednja kamera':'Zadnja kamera');$('modalDuration').textContent=tformat(f.durationSec);$('overlay').classList.add('open');$('overlay').setAttribute('aria-hidden','false');$('videoPlayer').style.display='none';$('videoPlayer').removeAttribute('src');try{const out=await native('preview',{id});if(state.currentId!==id)return;let url=out.path;if(window.Capacitor?.convertFileSrc)url=window.Capacitor.convertFileSrc(out.path);state.preview=url;$('videoPlayer').src=url;$('videoPlayer').style.display='block';await $('videoPlayer').play().catch(()=>{});}catch(e){toast('Pregled nije dostupan: '+(e.message||e))}}
for(let host of ['recentHome','vaultList']){$(host).addEventListener('click',e=>{let x=e.target.closest('[data-file-id]');if(x)openFile(x.dataset.fileId)});$(host).addEventListener('keydown',e=>{let x=e.target.closest('[data-file-id]');if(x&&e.key==='Enter')openFile(x.dataset.fileId)})}
function closeFile(){state.currentId=null;$('videoPlayer').pause();$('videoPlayer').removeAttribute('src');$('videoPlayer').load();$('videoPlayer').style.display='none';$('overlay').classList.remove('open');$('overlay').setAttribute('aria-hidden','true');if(isAndroid())native('purgePreview').catch(()=>{})}
$('closeModal').onclick=closeFile;$('overlay').onclick=e=>{if(e.target===$('overlay'))closeFile()};
$('exportModal').onclick=async()=>{const id=state.currentId;if(!id)return;if(!confirm('Izvesti MP4 kopiju u javnu galeriju? Kopija više neće biti šifrirana.'))return;try{let r=await native('export',{id});toast('Izvezeno u galeriju: '+r.name);closeFile()}catch(e){toast('Izvoz nije uspio: '+(e.message||e))}};
$('saveSecret').onclick=()=>{let x=$('secretInput').value.replace(/\s/g,'').replaceAll('×','*').replaceAll('÷','/');if(!/^[\d.+*/-]{3,40}$/.test(x)||evalCalc(x)===null){toast('Unesi važeću kombinaciju, npr. 73+27');return}state.secret=x;try{localStorage.setItem('calculator_secret_v2',x)}catch{}toast('Kombinacija sačuvana.')};
$('resetDemo').textContent='Obriši sve snimke (uz potvrdu)';
$('resetDemo').onclick=async()=>{if(!state.files.length){toast('Nema snimaka za brisanje.');return}if(!confirm('Trajno obrisati sve šifrirane snimke iz sefa?'))return;try{await native('deleteAll');toast('Snimci obrisani.');await refresh()}catch(e){toast(String(e.message||e))}};
$('autolockSetting').onchange=()=>toast('Automatsko zaključavanje ažurirano.');
let lastTouch=0;function signalActivity(){if(!isAndroid())return;let now=Date.now();if(now-lastTouch<5000)return;lastTouch=now;native('touch').catch(()=>{})}
document.addEventListener('pointerdown',signalActivity,{passive:true});document.addEventListener('keydown',signalActivity);
document.addEventListener('visibilitychange',()=>{if(document.hidden){if(unlocked&&$('autolockSetting').checked)lock();if($('overlay').classList.contains('open'))closeFile()}else refresh()});
function showCaptureInfo(message){
 for(const id of ['captureWarning','vaultWarning']){
   const el=$(id);el.hidden=!message;el.textContent=message||'';
 }
}
async function refresh(){
 if(!isAndroid())return;
 if(pollBusy)return;
 pollBusy=true;
 try{
   const [s,files]=await Promise.all([native('status'),native('list')]);
   const oldSaved=state.lastSavedAt;
   state.active=!!s.recording;
   state.saving=!!s.saving;
   state.startedAt=s.startedAt||0;
   state.files=Array.isArray(files.files)?files.files:[];
   state.lastSavedAt=Number(s.lastSavedAt||0);
   state.lastError=s.lastError||'';
   state.unprocessedFiles=Number(s.unprocessedFiles||0);
   if(state.synced && state.lastSavedAt>oldSaved)toast('Snimak uspješno spremljen u sef: '+(s.lastSavedName||''));
   state.synced=true;
   let notice='';
   if(state.lastError)notice='Greška snimanja/spremanja: '+state.lastError;
   else if(state.saving)notice='Snimanje je zaustavljeno. Šifriranje i spremanje u sef još traju. Nemoj zatvarati aplikaciju prisilno.';
   if(state.unprocessedFiles && !state.active && !state.saving){
     notice+=(notice?'\n':'')+'Preostale privatne datoteke: '+state.unprocessedFiles+'. Klikni „Provjeri i obnovi pronađene snimke” u sefu.';
   }
   showCaptureInfo(notice);
   renderFiles();renderRecord();
 }catch(e){
   const message='Ne mogu učitati stanje snimanja / sef: '+(e.message||e);
   showCaptureInfo(message);
 }finally{pollBusy=false}
}

setMode('camera');setLens('back');renderRecord();renderFiles();calcRender();if(isAndroid())$('environmentNote').textContent='Za snimanje je potrebna vidljiva Android obavijest. Za ekran se dozvola traži svaki put.';setInterval(()=>{if(unlocked){renderRecord();refresh()}},1600);if(!isAndroid()){$('recordDescription').textContent='HTML pregled dizajna. Stvarno snimanje i AES sef rade u instaliranoj Android aplikaciji.'}
})();
