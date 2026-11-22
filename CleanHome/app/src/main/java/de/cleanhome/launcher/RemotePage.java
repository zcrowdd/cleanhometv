package de.cleanhome.launcher;

/** Die Steuerseite fuer das Handy (Vercel-Look, ohne externe Dateien). */
final class RemotePage {
    private RemotePage() { }

    static final String HTML = """
<!DOCTYPE html>
<html lang="de"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#000000">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<title>CleanHome</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#000;color:#ededed;font-family:-apple-system,BlinkMacSystemFont,Inter,Segoe UI,Roboto,sans-serif;
 background-image:linear-gradient(#111 1px,transparent 1px),linear-gradient(90deg,#111 1px,transparent 1px);background-size:40px 40px}
.wrap{max-width:480px;margin:0 auto;padding:22px 16px 40px}
header{display:flex;align-items:center;gap:10px;margin-bottom:22px}
.logo{width:22px;height:22px;background:#ededed;clip-path:polygon(50% 8%,95% 90%,5% 90%)}
h1{font-size:18px;font-weight:600;margin:0;letter-spacing:-.02em}
.by{margin-left:auto;font:11px ui-monospace,Menlo,monospace;color:#666;letter-spacing:.12em}
.np{border:1px solid #2e2e2e;border-radius:12px;padding:14px;background:#0a0a0a;margin-bottom:14px;min-height:20px}
.np .t{font-weight:600}.np .a{color:#a1a1a1;font-size:13px;margin-top:2px}
.lbl{font:11px ui-monospace,Menlo,monospace;color:#666;letter-spacing:.14em;margin:20px 2px 8px}
.row{display:grid;gap:8px}.c3{grid-template-columns:repeat(3,1fr)}.c4{grid-template-columns:repeat(4,1fr)}
button{font:inherit;color:#ededed;background:#0a0a0a;border:1px solid #2e2e2e;border-radius:10px;padding:16px 6px;font-size:15px;
 transition:transform .08s,background .15s}
button:active{transform:scale(.96);background:#1a1a1a}
button.pri{background:#ededed;color:#000;border-color:#ededed;font-weight:600}
.apps{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.app{padding:14px 8px;text-align:left;display:flex;flex-direction:column;gap:10px;min-height:78px}
.app i{width:26px;height:26px;border-radius:7px;display:flex;align-items:center;justify-content:center;font-style:normal;
 font-weight:700;font-size:13px;color:#fff}
.app span{font-size:13px;line-height:1.2;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%) translateY(20px);background:#ededed;color:#000;
 padding:9px 16px;border-radius:999px;font-size:13px;opacity:0;transition:.25s;pointer-events:none}
#toast.on{opacity:1;transform:translateX(-50%)}
</style></head><body><div class="wrap">
<header><div class="logo"></div><h1>CleanHome</h1><div class="by">MADE BY ZCROWD</div></header>
<div class="np" id="np"><div class="t">Verbinde …</div></div>
<div class="row c3">
 <button onclick="api('media',{a:'prev'})">⏮</button>
 <button class="pri" onclick="api('media',{a:'toggle'})">⏯</button>
 <button onclick="api('media',{a:'next'})">⏭</button>
</div>
<div class="lbl">LAUTSTÄRKE</div>
<div class="row c3">
 <button onclick="api('vol',{d:'down'})">−</button>
 <button onclick="api('vol',{d:'mute'})">🔇</button>
 <button onclick="api('vol',{d:'up'})">+</button>
</div>
<div class="lbl">LICHT</div>
<div class="row c4">
 <button onclick="api('light',{m:'auto'},'Licht automatisch')">Auto</button>
 <button onclick="api('light',{m:'bright'},'Licht hell')">Hell</button>
 <button onclick="api('light',{m:'dim'},'Licht gedimmt')">Dimmen</button>
 <button onclick="api('light',{m:'off'},'Licht aus')">Aus</button>
</div>
<div class="lbl">FERNSEHER</div>
<div class="row c3">
 <button onclick="api('home',{},'Home')">⌂ Home</button>
 <button onclick="api('saver',{},'Ruhebildschirm')">☾ Ruhe</button>
 <button onclick="api('pc',{},'PC wird geweckt')">🖥 PC an</button>
</div>
<div class="lbl">SCHLAFMODUS</div>
<div class="row c3">
 <button onclick="api('night',{on:'1'},'Schlafmodus an · Lichter aus')">☾ An</button>
 <button onclick="api('night',{on:'0'},'Schlafmodus aus')">Aus</button>
 <button onclick="copyNight()">Kurzbefehl</button>
</div>
<div class="lbl">APPS</div>
<div class="apps" id="apps"></div>
</div><div id="toast"></div>
<script>
const K=new URLSearchParams(location.search).get('k')||'';
function toast(t){const e=document.getElementById('toast');e.textContent=t;e.classList.add('on');clearTimeout(e._t);e._t=setTimeout(()=>e.classList.remove('on'),1400)}
async function api(c,p={},msg){p.k=K;try{const r=await fetch('/api/'+c+'?'+new URLSearchParams(p));const j=await r.json();if(msg)toast(msg);if(navigator.vibrate)navigator.vibrate(12);return j}catch(e){toast('Keine Verbindung zum Stick')}}
function copyNight(){const on=location.origin+'/api/night?on=1&k='+K,off=location.origin+'/api/night?on=0&k='+K;
 const t='Fokus Schlafen AN:\n'+on+'\n\nFokus Schlafen AUS:\n'+off;
 if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(()=>toast('Links kopiert'))}
 else{prompt('Links für den iPhone-Kurzbefehl (kopieren):',t)}}
function hex(c){return '#'+(c&0xFFFFFF).toString(16).padStart(6,'0')}
async function loadApps(){const j=await api('apps');if(!j)return;const box=document.getElementById('apps');box.innerHTML='';
 j.apps.forEach(a=>{const b=document.createElement('button');b.className='app';
  b.innerHTML='<i style="background:'+hex(a.c)+'">'+(a.n[0]||'?').toUpperCase()+'</i><span></span>';
  b.querySelector('span').textContent=a.n;b.onclick=()=>api('launch',{p:a.p},a.n+' startet');box.appendChild(b)})}
async function status(){const j=await api('status');if(!j)return;const np=document.getElementById('np');
 np.innerHTML='';const t=document.createElement('div');t.className='t';const a=document.createElement('div');a.className='a';
 if(j.title){t.textContent=(j.playing?'▶ ':'❚❚ ')+j.title;a.textContent=j.artist||j.app||''}else{t.textContent='Nichts läuft';a.textContent=j.front||''}
 np.appendChild(t);np.appendChild(a)}
loadApps();status();setInterval(status,4000);
</script></body></html>
""";
}
