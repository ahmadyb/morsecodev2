package com.morsecode.app.core.webshare

/**
 * The WebShare single page app, embedded in the APK (no build step, no CDN, no internet).
 *
 * Rules honoured here:
 *   - dark by default, light mode is an exact structural mirror (body[data-mode="light"])
 *   - the accent follows the phone's selected swatch (/api/hello -> accent)
 *   - sidebar carries live per-category counts
 *   - photos/videos: real thumbnails, NO filename under tiles, folder sidebar, day headers
 *     that appear exactly once, per-tile selection with accent checkmarks, transfer summary popup
 *   - music: table + bottom player that keeps playing across tab switches, one red cancel control
 *   - files: sticky address bar whose every segment is independently clickable, folders show "-"
 *   - exactly one upload progress UI at a time
 *   - mobile browsers get a native-feeling gallery/file manager, not a desktop table
 */
object WebShareAssets {

    const val PAGE: String = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>MorseCode - WebShare</title>
<style>
:root{
  --bg:#0B0B0B; --surface:#141414; --card:#1A1A1A; --card-2:#1F1F1F; --card-3:#262626;
  --line:rgba(255,255,255,.07); --line-2:rgba(255,255,255,.14);
  --text:#F5F5F5; --text-2:#A3A3A3; --muted:#6B6B6B;
  --sun:#FACC15; --sun-2:#EAB308; --sun-deep:#A16207; --lime:#84CC16; --lime-2:#65A30D;
  --ember:#EA580C; --ember-2:#F97316;
  --accent:#FACC15; --accent-2:#EAB308; --accent-ink:#2A1702; --accent-text:#FACC15;
  --accent-soft:rgba(250,204,21,.14); --accent-line:rgba(250,204,21,.36);
  --lime-soft:rgba(132,204,22,.14); --lime-line:rgba(132,204,22,.36);
  --ember-soft:rgba(234,88,12,.14); --ember-line:rgba(234,88,12,.36);
  --green:#22C55E; --green-2:#16A34A; --red:#EF4444; --purple:#8B5CF6; --blue:#38BDF8;
  --bar:linear-gradient(90deg,#FACC15 0%,#F59E0B 30%,#EA580C 55%,#84CC16 100%);
  --bar-short:linear-gradient(90deg,#FACC15 0%,#F97316 45%,#22C55E 100%);
  --gradient-brand:linear-gradient(135deg,#FACC15,#EA580C);
  --gradient-action:linear-gradient(135deg,#FACC15,#EAB308);
  --gradient-success:linear-gradient(135deg,#22C55E,#16A34A);
}
body[data-mode="light"]{
  --bg:#EEEDE7; --surface:#FFFFFF; --card:#FFFFFF; --card-2:#F5F4EE; --card-3:#E8E7E0;
  --line:rgba(15,23,42,.10); --line-2:rgba(15,23,42,.18);
  --text:#0F172A; --text-2:#475569; --muted:#94A3B8;
  --accent:#EAB308; --accent-2:#CA8A04; --accent-ink:#FFFFFF; --accent-text:#A16207;
  --accent-soft:rgba(234,179,8,.16); --accent-line:rgba(202,138,4,.45);
  --lime-soft:rgba(101,163,13,.12); --lime-line:rgba(101,163,13,.30);
  --ember-soft:rgba(234,88,12,.12); --ember-line:rgba(234,88,12,.30);
  --green:#16A34A; --red:#DC2626; --purple:#7C3AED; --blue:#0284C7;
}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Ubuntu,sans-serif;font-size:14px}
a{color:inherit}
.app{display:flex;flex-direction:column;min-height:100vh}
header{display:flex;align-items:center;gap:14px;padding:12px 18px;background:var(--surface);
  border-bottom:1px solid var(--line);position:sticky;top:0;z-index:40}
.brand{display:flex;align-items:center;gap:10px}
.logo{width:34px;height:34px;border-radius:10px;background:var(--gradient-brand);display:flex;
  align-items:center;justify-content:center;color:#1A1002;font-weight:800;font-size:17px}
.urlbar{flex:1;min-width:0;display:flex;align-items:center;gap:10px}
.urltext{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--text-2);
  background:var(--card-2);border:1px solid var(--line);border-radius:10px;padding:6px 10px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:340px}
.devname{font-weight:600}
.devsub{font-size:11px;color:var(--muted);font-family:ui-monospace,monospace}
.pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:4px 11px;font-size:11px;
  font-weight:700;letter-spacing:.02em}
.pill.ok{background:var(--lime-soft);color:var(--green);border:1px solid var(--lime-line)}
.pill.warn{background:var(--ember-soft);color:var(--ember-2);border:1px solid var(--ember-line)}
.pill.accent{background:var(--accent-soft);color:var(--accent-text);border:1px solid var(--accent-line)}
.pill.mut{background:var(--card-3);color:var(--text-2);border:1px solid var(--line)}
.btn{display:inline-flex;align-items:center;gap:8px;border:none;cursor:pointer;border-radius:999px;
  padding:9px 16px;font-weight:700;font-size:13px;background:var(--gradient-action);color:var(--accent-ink)}
.btn.ghost{background:var(--card-3);color:var(--text)}
.btn.green{background:var(--gradient-success);color:#fff}
.btn.red{background:rgba(239,68,68,.16);color:var(--red);border:1px solid rgba(239,68,68,.4)}
.btn.small{padding:6px 12px;font-size:12px}
.btn:disabled{opacity:.5;cursor:default}
.layout{display:flex;flex:1;min-height:0}
aside{width:224px;flex:0 0 224px;background:var(--surface);border-right:1px solid var(--line);padding:14px 10px}
.navitem{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:12px;cursor:pointer;
  color:var(--text-2);font-weight:600}
.navitem .count{margin-left:auto;font-family:ui-monospace,monospace;font-size:11px;color:var(--muted)}
.navitem.on{background:var(--accent-soft);color:var(--accent-text);border:1px solid var(--accent-line)}
.navitem.on .count{color:var(--accent-text)}
.folders{margin-top:18px}
.folders h4{margin:0 0 8px 12px;font-size:11px;letter-spacing:.08em;color:var(--muted);text-transform:uppercase}
.folder{display:flex;align-items:center;gap:8px;padding:7px 12px;border-radius:10px;cursor:pointer;
  font-size:13px;color:var(--text-2)}
.folder.on,.folder:hover{background:var(--card-2);color:var(--text)}
.folder .count{margin-left:auto;font-size:11px;color:var(--muted);font-family:ui-monospace,monospace}
main{flex:1;min-width:0;padding:18px 22px 90px}
.sectionhead{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:14px}
h2{margin:0;font-size:19px}
input[type=search],select,input[type=text]{background:var(--card-2);border:1px solid var(--line);
  color:var(--text);border-radius:10px;padding:9px 12px;font-size:13px;outline:none}
input[type=search]{min-width:220px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:16px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px}
.stat .n{font-size:24px;font-weight:800;color:var(--accent-text);font-family:ui-monospace,monospace}
.stat .l{font-size:11px;letter-spacing:.06em;color:var(--muted);text-transform:uppercase;margin-top:4px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px}
.storagebar{height:8px;border-radius:99px;background:var(--card-3);overflow:hidden;margin:10px 0 6px}
.storagebar>i{display:block;height:100%;background:var(--bar)}
.drop{border:2px dashed var(--accent-line);border-radius:16px;padding:22px;text-align:center;
  background:var(--accent-soft);cursor:pointer;margin-top:14px}
.drop.hot{background:var(--lime-soft);border-color:var(--lime-line)}
.drop b{display:block;font-size:15px;margin-bottom:4px}
.drop span{color:var(--text-2);font-size:12.5px}
.grid{display:grid;gap:6px;grid-template-columns:repeat(auto-fill,minmax(128px,1fr))}
.tile{position:relative;aspect-ratio:1/1;border-radius:12px;overflow:hidden;background:var(--card-2);
  cursor:pointer;border:1px solid var(--line)}
.tile img{width:100%;height:100%;object-fit:cover;display:block}
.tile .ph{width:100%;height:100%;display:flex;align-items:center;justify-content:center;color:var(--muted);font-size:26px}
.tile .dur{position:absolute;right:6px;bottom:6px;background:rgba(0,0,0,.65);color:#fff;font-size:10.5px;
  border-radius:6px;padding:2px 6px;font-family:ui-monospace,monospace}
.tile .play{position:absolute;left:8px;top:8px;width:26px;height:26px;border-radius:99px;
  background:rgba(0,0,0,.55);color:#fff;display:flex;align-items:center;justify-content:center;font-size:12px}
.tile.sel{outline:3px solid var(--accent);outline-offset:-3px}
.tile .check{position:absolute;right:6px;top:6px;width:22px;height:22px;border-radius:99px;
  background:var(--accent);color:var(--accent-ink);font-weight:800;display:none;align-items:center;justify-content:center;font-size:12px}
.tile.sel .check{display:flex}
.tile .bar{position:absolute;left:0;right:0;bottom:0;height:4px;background:rgba(0,0,0,.4)}
.tile .bar>i{display:block;height:100%;width:0%;background:var(--bar-short)}
.dayhdr{margin:16px 0 8px;font-size:12.5px;font-weight:700;color:var(--text-2);letter-spacing:.04em}
table{width:100%;border-collapse:collapse;font-size:13px}
th{text-align:left;font-size:11px;letter-spacing:.07em;text-transform:uppercase;color:var(--muted);
  padding:8px 10px;border-bottom:1px solid var(--line)}
td{padding:9px 10px;border-bottom:1px solid var(--line);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
tr.sel td{background:var(--accent-soft)}
tr:hover td{background:var(--card-2)}
.rowname{display:flex;align-items:center;gap:9px;min-width:0}
.typeicon{width:30px;height:30px;border-radius:9px;background:var(--card-3);display:flex;align-items:center;
  justify-content:center;font-size:15px;flex:0 0 30px}
.addressbar{display:flex;align-items:center;gap:4px;flex-wrap:wrap;background:var(--card-2);
  border:1px solid var(--line);border-radius:12px;padding:8px 10px;position:sticky;top:62px;z-index:30;
  font-family:ui-monospace,monospace;font-size:12.5px;margin-bottom:12px}
.crumb{color:var(--accent-text);cursor:pointer;padding:2px 4px;border-radius:6px}
.crumb:hover{background:var(--accent-soft)}
.sep{color:var(--muted)}
.selbar{position:fixed;left:50%;transform:translateX(-50%);bottom:18px;display:none;align-items:center;gap:12px;
  background:var(--card);border:1px solid var(--accent-line);border-radius:999px;padding:10px 16px;z-index:60;
  box-shadow:0 12px 40px rgba(0,0,0,.45)}
.selbar.on{display:flex}
.selbar .n{font-weight:700;color:var(--accent-text);font-family:ui-monospace,monospace}
.player{position:fixed;left:0;right:0;bottom:0;background:var(--card);border-top:1px solid var(--line);
  padding:10px 16px;display:none;align-items:center;gap:14px;z-index:55}
.player.on{display:flex}
.player .art{width:46px;height:46px;border-radius:10px;background:var(--gradient-brand);display:flex;
  align-items:center;justify-content:center;color:#1A1002;font-size:20px;flex:0 0 46px}
.player .who{min-width:0}
.player .who b{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:220px}
.player .who span{font-size:11.5px;color:var(--text-2)}
.player .pbar{flex:1;height:6px;border-radius:99px;background:var(--card-3);overflow:hidden;min-width:90px}
.player .pbar>i{display:block;height:100%;width:0%;background:var(--bar)}
.ctrl{width:38px;height:38px;border-radius:99px;background:var(--card-3);display:flex;align-items:center;
  justify-content:center;cursor:pointer;border:1px solid var(--line);color:var(--text)}
.ctrl.main{background:var(--gradient-action);color:var(--accent-ink);border:none;width:44px;height:44px}
.ctrl.x{background:rgba(239,68,68,.16);color:var(--red);border-color:rgba(239,68,68,.4)}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.72);display:none;align-items:center;justify-content:center;
  z-index:80;padding:18px}
.modal.on{display:flex}
.modal .box{background:var(--card);border:1px solid var(--line-2);border-radius:20px;max-width:820px;width:100%;
  max-height:86vh;overflow:auto;padding:18px}
.toast{position:fixed;right:18px;bottom:18px;background:var(--card);border:1px solid var(--accent-line);
  border-radius:14px;padding:12px 16px;max-width:340px;z-index:90;display:none}
.toast.on{display:block}
.upload{display:none;margin-top:12px;background:var(--card-2);border:1px solid var(--line);border-radius:14px;padding:12px}
.upload.on{display:block}
.upload .track{height:6px;border-radius:99px;background:var(--card-3);overflow:hidden;margin-top:8px}
.upload .track>i{display:block;height:100%;width:0%;background:var(--bar)}
.upnext{margin-top:14px}
.upnext .item{display:flex;align-items:center;gap:10px;padding:8px 6px;border-radius:10px;cursor:pointer}
.upnext .item:hover{background:var(--card-2)}
.thumb160{width:44px;height:44px;border-radius:10px;overflow:hidden;background:var(--card-3);flex:0 0 44px;
  display:flex;align-items:center;justify-content:center}
.thumb160 img{width:100%;height:100%;object-fit:cover}
.videopage{display:none;position:fixed;inset:0;background:rgba(0,0,0,.94);z-index:85;flex-direction:column;color:#F5F5F5}
.videopage.on{display:flex}
.videopage .top{display:flex;align-items:center;gap:12px;padding:12px 16px;border-bottom:1px solid rgba(255,255,255,.1)}
.videopage .stage{flex:1;display:flex;align-items:center;justify-content:center;background:#000}
.videopage video{max-width:100%;max-height:100%}
.videopage .bottom{padding:12px 16px;border-top:1px solid rgba(255,255,255,.1);display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.videopage .track{flex:1;height:6px;border-radius:99px;background:rgba(255,255,255,.16);overflow:hidden;min-width:120px}
.videopage .track>i{display:block;height:100%;width:0%;background:var(--bar)}
.videopage .path{font-family:ui-monospace,monospace;font-size:11.5px;color:#A3A3A3;overflow:hidden;text-overflow:ellipsis}
@media (max-width:860px){
  aside{position:fixed;left:-260px;top:0;bottom:0;z-index:70;transition:left .2s}
  aside.open{left:0}
  .burger{display:flex !important}
  main{padding:14px 12px 120px}
  .urltext{display:none}
  table{font-size:12.5px}
  th:nth-child(3),td:nth-child(3){display:none}
  .player .who b{max-width:120px}
  .grid{grid-template-columns:repeat(auto-fill,minmax(108px,1fr))}
}
.burger{display:none;cursor:pointer;width:34px;height:34px;align-items:center;justify-content:center;
  border-radius:10px;background:var(--card-2);border:1px solid var(--line)}
</style>
</head><body data-mode="dark">
<div class="app">
  <header>
    <div class="burger" id="burger">☰</div>
    <div class="brand">
      <div class="logo">M</div>
      <div>
        <div class="devname" id="devname">MorseCode</div>
        <div class="devsub" id="devsub">connecting…</div>
      </div>
    </div>
    <div class="urlbar"><div class="urltext" id="urltext"></div></div>
    <span class="pill ok" id="conn">● Connected</span>
    <button class="btn" id="sendfiles">⬆ Send files</button>
  </header>
  <div class="layout">
    <aside id="sidebar">
      <div class="navitem on" data-nav="home">🏠 Home</div>
      <div class="navitem" data-nav="photos">🖼 Photos <span class="count" id="c-photos">0</span></div>
      <div class="navitem" data-nav="videos">🎬 Videos <span class="count" id="c-videos">0</span></div>
      <div class="navitem" data-nav="music">🎵 Music <span class="count" id="c-music">0</span></div>
      <div class="navitem" data-nav="docs">📄 Docs <span class="count" id="c-docs">0</span></div>
      <div class="navitem" data-nav="apps">📦 Apps <span class="count" id="c-apps">0</span></div>
      <div class="navitem" data-nav="files">🗂 Files</div>
      <div class="folders" id="folders"></div>
    </aside>
    <main>
      <div id="view"></div>
    </main>
  </div>
</div>

<div class="selbar" id="selbar">
  <span class="n" id="selcount">0 selected</span>
  <button class="btn small" id="btndownload">⬇ Download</button>
  <button class="btn small green" id="btnzip">🗜 Download as zip</button>
  <button class="btn small ghost" id="btnclear">Clear</button>
</div>

<div class="player" id="player">
  <div class="art" id="part">♫</div>
  <div class="who"><b id="ptitle">-</b><span id="partist">-</span></div>
  <div class="pbar"><i id="pfill"></i></div>
  <span class="devsub" id="ptime">0:00</span>
  <div class="ctrl" id="pprev">⏮</div>
  <div class="ctrl main" id="pplay">▶</div>
  <div class="ctrl" id="pnext">⏭</div>
  <div class="ctrl x" id="pstop">✕</div>
</div>

<div class="modal" id="modal"><div class="box" id="modalbox"></div></div>
<div class="videopage" id="videopage">
  <div class="top">
    <div class="ctrl" id="vback">←</div>
    <div style="min-width:0">
      <div class="path" id="vpath">-</div>
      <div id="vtitle" style="font-weight:700;font-size:14px"></div>
      <div class="devsub" id="vmeta"></div>
    </div>
    <div style="flex:1"></div>
    <button class="btn small" id="vdownload">⬇ Download</button>
  </div>
  <div class="stage" id="vstage"><div id="vholder"></div></div>
  <div class="bottom">
    <div class="ctrl main" id="vplay">▶</div>
    <div class="track"><i id="vfill"></i></div>
    <span class="devsub" id="vtime">0:00 / 0:00</span>
    <div class="ctrl" id="vrot">⟳</div>
    <div class="ctrl" id="vmute">🔊</div>
    <div class="ctrl" id="vcc">CC</div>
  </div>
</div>
<div class="toast" id="toast"></div>

<script>
var BOOT = __BOOT__;
var state = {category:"photos", mode:"grid", folder:"", search:"", sort:"newest",
  selected:{}, items:[], page:"home", fsPath:"/storage/emulated/0", folderSel:{}};
var audio = null;

function toast(msg){var t=document.getElementById("toast");t.textContent=msg;t.className="toast on";
  clearTimeout(window._tt);window._tt=setTimeout(function(){t.className="toast";},2600);}
function fmt(bytes){if(bytes<0)return "-";if(bytes<1024)return bytes+" B";
  var kb=bytes/1024;if(kb<1024)return kb.toFixed(1)+" KB";var mb=kb/1024;
  if(mb<1024)return mb.toFixed(1)+" MB";return (mb/1024).toFixed(2)+" GB";}
function dur(ms){var s=Math.floor((ms||0)/1000);var m=Math.floor(s/60);s=s%60;
  return m+":"+(s<10?"0":"")+s;}
function esc(s){return String(s).replace(/[&<>"]/g,function(c){
  return {"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;"}[c];});}
function api(path){return fetch(path,{cache:"no-store"}).then(function(r){return r.json();});}
function el(id){return document.getElementById(id);}
function dayLabel(ms){var d=new Date(ms);var now=new Date();var t0=new Date(now.getFullYear(),now.getMonth(),now.getDate()).getTime();
  var y0=t0-86400000;var t=new Date(d.getFullYear(),d.getMonth(),d.getDate()).getTime();
  if(t===t0)return "Today";if(t===y0)return "Yesterday";
  var months=["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
  return d.getDate()+" "+months[d.getMonth()]+" "+d.getFullYear();}
function icon(mime,name){
  if(name && name.toLowerCase().indexOf(".apk")>0) return "📦";
  if(!mime) return "📄";
  if(mime.indexOf("image/")===0) return "🖼";
  if(mime.indexOf("video/")===0) return "🎬";
  if(mime.indexOf("audio/")===0) return "🎵";
  if(mime.indexOf("zip")>0) return "🗜";
  if(mime.indexOf("pdf")>0) return "📕";
  if(mime.indexOf("text")===0) return "📝";
  return "📄";}

/* ---------------- boot ---------------- */
window.addEventListener("load", function(){
  api("/api/hello").then(function(j){
    el("devname").textContent=j.device||"MorseCode";
    el("devsub").textContent=(j.url||"") + " · WebShare session";
    el("urltext").textContent=j.url||"";
    document.body.setAttribute("data-mode", j.theme==="light"?"light":"dark");
    applyAccent(j.accent||"yellow");
    refreshCounts();
    render();
  });
  el("sendfiles").onclick=function(){pickUpload();};
  el("burger").onclick=function(){el("sidebar").classList.toggle("open");};
  Array.prototype.forEach.call(document.querySelectorAll(".navitem"), function(n){
    n.onclick=function(){
      var target=n.getAttribute("data-nav");
      state.page=target; state.selected={}; state.folder="";
      Array.prototype.forEach.call(document.querySelectorAll(".navitem"),function(x){x.className="navitem";});
      n.className="navitem on";
      render();
    };
  });
  el("btnclear").onclick=function(){state.selected={};renderSelection();render();};
  el("btndownload").onclick=function(){
    Object.keys(state.selected).forEach(function(id){
      var it=state.items.filter(function(x){return String(x.id)===String(id);})[0];
      if(!it)return;
      if(it.isApp){location.href="/download-file?path="+encodeURIComponent(it.uri);return;}
      location.href="/download?id="+it.id;
    });
    toast("Download started");
  };
  el("btnzip").onclick=function(){
    var ids=Object.keys(state.selected).join(",");
    if(!ids)return;
    location.href="/download-zip?ids="+ids;
    toast("Building zip on the phone…");
  };
  el("pstop").onclick=function(){stopAudio(true);};
});

function applyAccent(id){
  var map={yellow:["#FACC15","#EAB308","#2A1702"],green:["#84CC16","#65A30D","#12240A"],
           orange:["#EA580C","#F97316","#2A1204"],purple:["#8B5CF6","#7C3AED","#FFFFFF"],
           blue:["#38BDF8","#0284C7","#041C29"]};
  var a=map[id]||map.yellow;
  var root=document.documentElement.style;
  root.setProperty("--accent",a[0]); root.setProperty("--accent-2",a[1]);
  root.setProperty("--accent-ink",a[2]); root.setProperty("--accent-text",a[0]);
  root.setProperty("--accent-soft",hexA(a[0],.16)); root.setProperty("--accent-line",hexA(a[0],.40));
}
function hexA(hex,alpha){var r=parseInt(hex.substr(1,2),16),g=parseInt(hex.substr(3,2),16),b=parseInt(hex.substr(5,2),16);
  return "rgba("+r+","+g+","+b+","+alpha+")";}

function refreshCounts(){
  api("/api/counts").then(function(c){
    el("c-photos").textContent=c.photos||0; el("c-videos").textContent=c.videos||0;
    el("c-music").textContent=c.music||0; el("c-docs").textContent=c.docs||0;
    el("c-apps").textContent=c.apps||0;
  });
}

/* ---------------- router ---------------- */
function render(){
  var v=el("view");
  el("selbar").className=state.selectedCount?"selbar":"selbar";
  if(state.page==="home") return renderHome(v);
  if(state.page==="files") return renderFiles(v);
  return renderCategory(v, state.page);
}

function renderHome(v){
  api("/api/counts").then(function(c){
    var used=0,total=0;
    api("/api/info").then(function(info){});
    v.innerHTML =
      '<div class="stats">' +
        stat(c.photos,"Photos") + stat(c.videos,"Videos") + stat(c.music,"Music") +
        stat(c.docs,"Docs") + stat(c.apps,"Apps") +
      '</div>' +
      '<div class="card">' +
        '<div style="display:flex;justify-content:space-between;align-items:center">' +
          '<b>Storage</b><span class="devsub" id="usedline"></span></div>' +
        '<div class="storagebar"><i style="width:8%"></i></div>' +
        '<div class="devsub" style="font-size:11.5px">Files received over WebShare land in the phone\\'s chosen download folder.</div>' +
        '<div class="drop" id="drop"><b>Upload files to phone</b>' +
        '<span>Drag files here or click to send to phone</span>' +
        '<div style="margin-top:12px"><button class="btn" id="choosefiles">Choose files</button></div></div>' +
        '<div class="upload" id="upload"><b id="uname">-</b>' +
        '<div class="track"><i id="ufill"></i></div>' +
        '<div class="devsub" id="uprog" style="margin-top:6px"></div></div>' +
      '</div>';
    var drop=el("drop");
    drop.onclick=function(){pickUpload();};
    drop.ondragover=function(e){e.preventDefault();drop.className="drop hot";};
    drop.ondragleave=function(){drop.className="drop";};
    drop.ondrop=function(e){e.preventDefault();drop.className="drop";
      uploadFiles(e.dataTransfer.files);};
  });
}
function stat(n,l){return '<div class="stat"><div class="n">'+(n||0)+'</div><div class="l">'+l+'</div></div>';}

function renderCategory(v, category){
  state.category=category; state.mode="grid";
  if(category==="music") return renderMusic(v);
  if(category==="apps") return renderApps(v);
  var kind = category==="videos" ? "video" : "image";
  v.innerHTML = '<div class="sectionhead">' +
    '<h2>'+category.charAt(0).toUpperCase()+category.slice(1)+'</h2>' +
    '<button class="btn small" id="up">⬆ Upload</button>' +
    '<input type="search" id="q" placeholder="Search this category">' +
    '<select id="sort"><option value="newest">Newest first</option><option value="oldest">Oldest first</option>' +
    '<option value="name">Name</option><option value="size">Size</option></select>' +
    '<span class="pill accent" id="selinfo" style="display:none"></span>' +
    '<div style="flex:1"></div>' +
    '<button class="btn small green" id="zip">🗜 Download as zip</button></div>' +
    '<div id="folderbar"></div><div id="content"></div>';
  el("up").onclick=pickUpload;
  el("zip").onclick=el("btnzip").onclick;
  var q=el("q");
  q.oninput=function(){state.search=q.value.trim().toLowerCase();loadItems();};
  var s=el("sort");
  s.onchange=function(){state.sort=s.value;loadItems();};
  loadItems();
}

function loadItems(){
  var url="/api/files?category="+state.category+"&q="+encodeURIComponent(state.search)+"&sort="+state.sort;
  api(url).then(function(j){
    state.items=j.items||[];
    // day headers appear exactly once, in order
    var groups=[]; var last=null;
    state.items.forEach(function(it){
      var label=dayLabel(it.date||Date.now());
      if(label!==last){groups.push({label:label, items:[]}); last=label;}
      groups[groups.length-1].items.push(it);
    });
    var kind = state.category==="videos" ? "video" : (state.category==="docs"?"doc":"image");
    var html= groups.map(function(g){
      return '<div class="dayhdr">'+g.label+' '+g.items.length+' items</div><div class="grid">' +
        g.items.map(function(it){return tile(it, kind);}).join("") + '</div>';
    }).join("");
    if(!groups.length){ html='<div class="card" style="text-align:center;color:var(--text-2)">Nothing in this category yet.</div>'; }
    var content=el("content"); if(content) content.innerHTML=html;
    var folders={};
    state.items.forEach(function(it){
      var f=it.folder||(state.category==="photos"?"Camera":"Other");
      folders[f]=(folders[f]||0)+1;
    });
    var fbar=el("folderbar");
    if(fbar){
      var keys=Object.keys(folders).slice(0,8);
      fbar.innerHTML='<div class="folders" style="margin:0 0 8px 0;display:flex;gap:6px;flex-wrap:wrap">' +
        '<span class="pill '+(state.folder?"mut":"accent")+'" data-folder="">All '+state.category+'</span>' +
        keys.map(function(k){return '<span class="pill '+(state.folder===k?"accent":"mut")+'" data-folder="'+esc(k)+'">'+esc(k)+' '+folders[k]+'</span>';}).join("") +
        '</div>';
      Array.prototype.forEach.call(fbar.querySelectorAll("[data-folder]"),function(pill){
        pill.onclick=function(){state.folder=pill.getAttribute("data-folder");
          var css=state.folder?'[data-folder="'+state.folder+'"]':'';
          Array.prototype.forEach.call(document.querySelectorAll("#content .tile"),function(t){
            t.style.display=(!state.folder||t.getAttribute("data-folder")===state.folder)?"":"none";});
          loadItems();};
      });
    }
  });
}

function tile(it, kind){
  var sel=state.selected[it.id]?" sel":"";
  var inner;
  if(it.isApp){ inner='<div class="ph">📦</div>'; }
  else if(kind==="doc"||kind==="app"){ inner='<div class="ph">'+icon(it.mime,it.name)+'</div>'; }
  else { inner='<img loading="lazy" src="/thumbnail?id='+it.id+'&kind='+kind+'" alt="">'; }
  if(kind==="video") inner+='<div class="play">▶</div><div class="dur">'+dur(it.duration)+'</div>';
  return '<div class="tile'+sel+'" data-id="'+it.id+'" data-folder="'+esc(it.folder||"")+'">' + inner +
    '<div class="check">✓</div>' +
    '<div class="bar"><i data-fill="'+it.id+'"></i></div></div>';
}

/* ---------------- music ---------------- */
function renderMusic(v){
  state.category="music";
  api("/api/files?category=music&q="+encodeURIComponent(state.search)+"&sort="+state.sort).then(function(j){
    state.items=j.items||[];
    v.innerHTML='<div class="sectionhead"><h2>Music</h2>' +
      '<button class="btn small" id="up">⬆ Upload</button>' +
      '<input type="search" id="q" placeholder="Search this category">' +
      '<div style="flex:1"></div>' +
      '<button class="btn small green" id="zip">🗜 Download as zip</button></div>' +
      '<div class="card" style="padding:0;overflow:hidden"><table><thead><tr>' +
      '<th style="width:34px"></th><th>TITLE</th><th>ARTIST</th><th>DURATION</th><th>SIZE</th></tr></thead><tbody>' +
      state.items.map(function(it,i){
        return '<tr data-i="'+i+'" data-id="'+it.id+'"><td style="text-align:center" class="rw-thumb">' +
          '<div class="thumb160"><img loading="lazy" src="/thumbnail?id='+it.id+'&kind=audio" alt=""></div></td>' +
          '<td><div class="rowname">'+esc(it.name)+'</div></td>' +
          '<td style="color:var(--text-2)">'+esc(it.folder||"Unknown")+'</td>' +
          '<td style="color:var(--text-2)">'+dur(it.duration)+'</td>' +
          '<td style="color:var(--text-2)">'+fmt(it.size)+'</td></tr>';
      }).join("") + '</tbody></table></div>' +
      '<div class="upnext card" style="margin-top:12px"><b>UP NEXT - '+state.items.length+' SONGS</b>' +
      '<div id="upnextlist"></div></div>';
    el("up").onclick=pickUpload;
    el("zip").onclick=el("btnzip").onclick;
    el("q").oninput=function(){state.search=el("q").value.trim().toLowerCase();
      api("/api/files?category=music&q="+encodeURIComponent(state.search)).then(function(jj){
        state.items=jj.items||[]; renderMusic(el("view"));});};
    Array.prototype.forEach.call(document.querySelectorAll("tbody tr"),function(tr){
      tr.onclick=function(){playIndex(parseInt(tr.getAttribute("data-i"),10));};
    });
    var list=el("upnextlist");
    list.innerHTML=state.items.slice(0,40).map(function(it,i){
      return '<div class="item" data-i="'+i+'"><span style="width:22px;color:var(--muted);font-family:ui-monospace,monospace">'+(i+1)+'</span>' +
        '<div style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'+esc(it.name)+'</div>' +
        '<span class="devsub">'+dur(it.duration)+'</span></div>';
    }).join("");
    Array.prototype.forEach.call(list.querySelectorAll(".item"),function(n){
      n.onclick=function(){playIndex(parseInt(n.getAttribute("data-i"),10));};
    });
  });
}
function playIndex(i){
  var it=state.items[i]; if(!it)return;
  if(audio){audio.pause();audio=null;}
  audio=new Audio("/download?id="+it.id); audio.preload="auto";
  audio.play().catch(function(){toast("Tap play to start audio in this browser");});
  el("player").className="player on";
  el("ptitle").textContent=it.name; el("partist").textContent=(it.folder||"Music")+" · "+fmt(it.size);
  el("pplay").textContent="⏸";
  window._current=i;
  audio.ontimeupdate=function(){
    if(!audio)return;
    var p=(audio.currentTime/(audio.duration||1))*100;
    el("pfill").style.width=p+"%";
    el("ptime").textContent=dur(audio.currentTime*1000)+" / "+dur((audio.duration||0)*1000);
  };
  audio.onended=function(){playIndex((window._current||0)+1);};
}
function stopAudio(showToast){
  if(audio){audio.pause();audio=null;}
  el("player").className="player";
  if(showToast)toast("Playback stopped");
}
function hookPlayer(){
  el("pplay").onclick=function(){
    if(!audio){ if(window._current!=null) playIndex(window._current); return; }
    if(audio.paused){audio.play();el("pplay").textContent="⏸";} else {audio.pause();el("pplay").textContent="▶";}
  };
  el("pprev").onclick=function(){playIndex(Math.max(0,(window._current||0)-1));};
  el("pnext").onclick=function(){playIndex((window._current||0)+1);};
}
hookPlayer();

/* ---------------- apps ---------------- */
function renderApps(v){
  api("/api/files?category=apps").then(function(j){
    state.items=j.items||[];
    v.innerHTML='<div class="sectionhead"><h2>Apps</h2><div style="flex:1"></div>' +
      '<span class="pill mut">'+state.items.length+' installed</span></div>' +
      '<div class="grid" style="grid-template-columns:repeat(auto-fill,minmax(150px,1fr))">' +
      state.items.map(function(it,i){
        return '<div class="card" style="text-align:center">' +
          '<div style="font-size:22px">📦</div>' +
          '<div style="font-weight:700;font-size:12.5px;margin:8px 0 4px">'+esc(it.name)+'</div>' +
          '<div class="devsub" style="margin-bottom:8px">'+fmt(it.size)+'</div>' +
          '<button class="btn small" data-apk="'+i+'">Download APK</button></div>';
      }).join("") + '</div>';
    Array.prototype.forEach.call(document.querySelectorAll("[data-apk]"),function(b){
      b.onclick=function(){
        var it=state.items[parseInt(b.getAttribute("data-apk"),10)];
        location.href="/download-file?path="+encodeURIComponent(it.uri);
        toast("Extracting APK on the phone…");
      };
    });
  });
}

/* ---------------- files ---------------- */
function renderFiles(v){
  api("/api/fs?path="+encodeURIComponent(state.fsPath)).then(function(j){
    var segs=(j.segments||[]);
    var acc="";
    var crumbs=segs.map(function(s,i){
      acc += "/"+s;
      return '<span class="crumb" data-path="'+esc(acc)+'">'+esc(s)+'</span>' +
        (i<segs.length-1?'<span class="sep">/</span>':'');
    }).join("");
    var quick=(j.quick||[]).map(function(q){
      return '<div class="folder" data-quick="'+esc(q.path)+'">📁 '+esc(q.name)+'</div>';}).join("");
    v.innerHTML = '<div class="sectionhead"><h2>Files</h2><button class="btn small" id="up">⬆ Upload</button>' +
      '<div style="flex:1"></div><button class="btn small green" id="zip">🗜 Download folder as zip</button></div>' +
      '<div class="addressbar">'+crumbs+'</div>' +
      '<div class="layout" style="gap:14px;align-items:flex-start">' +
        '<div style="width:170px;flex:0 0 170px" class="card" id="quickcards">'+quick+'</div>' +
        '<div style="flex:1;min-width:0" class="card" style="padding:0">' +
        '<table><thead><tr><th style="width:36px"></th><th>NAME</th><th>SIZE</th><th>MODIFIED</th><th></th></tr></thead><tbody>' +
        (j.parent?'<tr data-parent="'+esc(j.parent)+'"><td>↩</td><td>..</td><td>-</td><td>-</td><td></td></tr>':'') +
        (j.items||[]).map(function(it,i){
          return '<tr data-i="'+i+'"><td style="text-align:center"><input type="checkbox" data-check="'+i+'" ' +
            (it.dir?'disabled':'')+'></td>' +
            '<td><div class="rowname"><div class="typeicon">'+(it.dir?"📁":icon(it.mime,it.name))+'</div>' +
            '<span style="overflow:hidden;text-overflow:ellipsis">'+esc(it.name)+'</span></div></td>' +
            '<td style="color:var(--text-2)">'+(it.dir?"-":fmt(it.size))+'</td>' +
            '<td style="color:var(--text-2)">'+new Date(it.modified).toLocaleDateString()+'</td>' +
            '<td style="text-align:right">' +
            (it.dir?'<button class="btn small ghost" data-open="'+esc(it.path)+'">Open</button>':
              '<button class="btn small ghost" data-dl="'+esc(it.path)+'">Download</button>') +
            '</td></tr>';
        }).join("") + '</tbody></table></div></div>';
    el("up").onclick=pickUpload;
    el("zip").onclick=function(){location.href="/download-folder?path="+encodeURIComponent(state.fsPath);};
    Array.prototype.forEach.call(document.querySelectorAll(".crumb"),function(c){
      c.onclick=function(){state.fsPath=c.getAttribute("data-path");renderFiles(el("view"));};
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-quick]"),function(q){
      q.onclick=function(){state.fsPath=q.getAttribute("data-quick");renderFiles(el("view"));};
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-open]"),function(b){
      b.onclick=function(){state.fsPath=b.getAttribute("data-open");renderFiles(el("view"));};
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-dl]"),function(b){
      b.onclick=function(){location.href="/download-file?path="+encodeURIComponent(b.getAttribute("data-dl"));};
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-parent]"),function(tr){
      tr.onclick=function(){state.fsPath=tr.getAttribute("data-parent");renderFiles(el("view"));};
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-check]"),function(cb){
      cb.onchange=function(){
        var it=(j.items||[])[parseInt(cb.getAttribute("data-check"),10)];
        if(!it)return;
        if(cb.checked)state.folderSel[it.path]=it; else delete state.folderSel[it.path];
        updateSelbar(true);
      };
    });
  });
}

/* ---------------- selection on tiles ---------------- */
document.addEventListener("click", function(e){
  var tile=e.target.closest? e.target.closest(".tile") : null;
  if(!tile) return;
  var id=tile.getAttribute("data-id");
  var it=state.items.filter(function(x){return String(x.id)===String(id);})[0];
  if(state.category==="videos" && !e.shiftKey){ openVideo(it); return; }
  if(state.selected[id]){ delete state.selected[id]; tile.className="tile"; }
  else { state.selected[id]=1; tile.className="tile sel"; }
  updateSelbar(false);
}, true);
document.addEventListener("dblclick", function(e){
  var tile=e.target.closest? e.target.closest(".tile") : null;
  if(!tile) return;
  var id=tile.getAttribute("data-id");
  var it=state.items.filter(function(x){return String(x.id)===String(id);})[0];
  if(it && state.category==="photos") openPhoto(it);
});

function updateSelbar(isFolder){
  var n=isFolder?Object.keys(state.folderSel).length:Object.keys(state.selected).length;
  var bar=el("selbar");
  bar.className = n>0 ? "selbar on" : "selbar";
  el("selcount").textContent=n+" selected";
  var info=el("selinfo");
  if(info){ info.style.display=n>0?"inline-flex":"none"; info.textContent=n+" selected"; }
}

function openPhoto(it){
  var box=el("modalbox");
  box.innerHTML='<div style="display:flex;align-items:center;gap:12px;margin-bottom:12px">' +
    '<b style="overflow:hidden;text-overflow:ellipsis">'+esc(it.name)+'</b>' +
    '<span class="devsub">'+it.width+' x '+it.height+' · '+fmt(it.size)+'</span>' +
    '<div style="flex:1"></div><button class="btn small" id="dlone">⬇ Download</button>' +
    '<button class="btn small ghost" id="xone">Close</button></div>' +
    '<div style="text-align:center"><img src="/download?id='+it.id+'" style="max-width:100%;max-height:66vh;border-radius:12px"></div>';
  el("modal").className="modal on";
  el("xone").onclick=function(){el("modal").className="modal";};
  el("dlone").onclick=function(){location.href="/download?id="+it.id;};
}

/* ---------------- video page ---------------- */
var vEl=null;
function openVideo(it){
  if(!it)return;
  el("videopage").className="videopage on";
  el("vpath").textContent="/"+ (it.folder||"storage") +"/"+it.name;
  el("vtitle").textContent=it.name;
  el("vmeta").textContent=(it.width||0)+" x "+(it.height||0)+" · "+fmt(it.size)+" · "+dur(it.duration);
  var holder=el("vholder");
  holder.innerHTML='<video id="vplayer" controls playsinline preload="metadata" src="/download?id='+it.id+'"></video>';
  vEl=el("vplayer");
  el("vdownload").onclick=function(){location.href="/download?id="+it.id;};
  el("vback").onclick=function(){ if(vEl){vEl.pause();} el("videopage").className="videopage"; };
  el("vplay").onclick=function(){ if(!vEl)return; if(vEl.paused){vEl.play();el("vplay").textContent="⏸";}
    else {vEl.pause();el("vplay").textContent="▶";} };
  el("vrot").onclick=function(){ window._rot=((window._rot||0)+90)%360;
    vEl.style.transform="rotate("+window._rot+"deg)"; };
  el("vmute").onclick=function(){ vEl.muted=!vEl.muted;
    el("vmute").textContent=vEl.muted?"🔇":"🔊"; };
  el("vcc").onclick=function(){ var tt=vEl.textTracks&&vEl.textTracks[0];
    if(tt){tt.mode = tt.mode==="showing" ? "hidden":"showing"; toast("Subtitles "+(tt.mode==="showing"?"on":"off"));}
    else toast("No subtitles in this file"); };
  vEl.ontimeupdate=function(){
    var p=(vEl.currentTime/(vEl.duration||1))*100;
    el("vfill").style.width=p+"%";
    el("vtime").textContent=dur(vEl.currentTime*1000)+" / "+dur((vEl.duration||0)*1000);
  };
}

/* ---------------- uploads (exactly one progress UI at a time) ---------------- */
function pickUpload(){
  var input=document.createElement("input");
  input.type="file"; input.multiple=true;
  input.onchange=function(){uploadFiles(input.files);};
  input.click();
}
function uploadFiles(files){
  if(!files||!files.length)return;
  var fd=new FormData();
  for(var i=0;i<files.length;i++){fd.append("file", files[i], files[i].name);}
  var box=el("upload");
  if(box){box.className="upload on";}
  var xhr=new XMLHttpRequest();
  xhr.open("POST","/upload",true);
  xhr.upload.onprogress=function(e){
    if(!box)return;
    var p=e.lengthComputable?(e.loaded/e.total)*100:0;
    var nm=el("uname"); if(nm)nm.textContent="Uploading "+files.length+" file(s)";
    var f=el("ufill"); if(f)f.style.width=p+"%";
    var pr=el("uprog"); if(pr)pr.textContent=fmt(e.loaded)+" / "+fmt(e.total)+"  ·  "+p.toFixed(0)+"%";
  };
  xhr.onload=function(){
    if(box){box.className="upload";}
    try{
      var j=JSON.parse(xhr.responseText||"{}");
      toast("Uploaded to the phone · "+(j.destination||"Download/MorseCode"));
    }catch(e){toast("Upload finished");}
    refreshCounts();
    render();
  };
  xhr.onerror=function(){ if(box){box.className="upload";} toast("Upload failed"); };
  xhr.send(fd);
}

/* ---------------- transfer summary popup ---------------- */
window.showTransferSummary=function(sent,failed,skipped,avg){
  var box=el("modalbox");
  box.innerHTML='<b style="font-size:16px">Transfer summary</b>' +
    '<p style="color:var(--text-2)">'+sent+' sent, '+failed+' failed, '+skipped+' skipped - Average speed '+avg+' MB/s</p>' +
    '<button class="btn" id="sumok">OK</button>';
  el("modal").className="modal on";
  el("sumok").onclick=function(){el("modal").className="modal";};
};
window.addEventListener("beforeunload", function(){ if(audio) audio.pause(); });
</script>
</body></html>"""
}
