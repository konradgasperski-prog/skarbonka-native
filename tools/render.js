// Renders watch-face assets from the simulator code (sim.html) into PNGs.
const {chromium}=require('playwright');
const fs=require('fs');
const OUT=process.argv[2]||'assets';
fs.mkdirSync(OUT,{recursive:true});
(async()=>{
 const b=await chromium.launch();
 const pg=await b.newPage({viewport:{width:800,height:800}});
 pg.on('pageerror',e=>console.log('PAGEERR',e.message));
 await pg.goto('file://'+process.cwd()+'/sim.html');
 await pg.evaluate(()=>{window.requestAnimationFrame=()=>0;});
 await pg.evaluate(async()=>{await document.fonts.load('700 100px PoppinsL');await document.fonts.load('500 30px PoppinsL');});
 await pg.waitForTimeout(300);
 const res=await pg.evaluate(()=>{
  const W=450,out={};
  const down=(src)=>{const k=document.createElement('canvas');k.width=W;k.height=W;const g=k.getContext('2d');g.imageSmoothingQuality='high';g.drawImage(src,0,0,W,W);return k.toDataURL('image/png');};
  function scene(pi,isAod){
   aod=isAod;tx=ty=0;const p=P[pi],e=1,b=1,lt=0.35;
   x.setTransform(1,0,0,1,0,0);x.globalAlpha=1;x.globalCompositeOperation='source-over';x.clearRect(0,0,S,S);
   x.save();
   if(isAod){x.fillStyle='#000';x.fillRect(0,0,S,S);}
   else{x.fillStyle=p[1];x.fillRect(0,0,S,S);const gx=R+lt*S*0.12,gy=R;const bg=x.createRadialGradient(gx,gy,0,gx,gy,S*0.62);bg.addColorStop(0,rgba(p[3],0.75*b));bg.addColorStop(0.45,rgba(p[3],0.25*b));bg.addColorStop(1,rgba(p[3],0));x.fillStyle=bg;x.fillRect(0,0,S,S);}
   const L=S*0.95,Wd=S*0.34,ang=-Math.PI/4;let shapes=[];
   [[-0.26,-0.02],[0.02,0.02],[0.3,0.06]].forEach((q,i)=>{const z=i!==1?1:0;shapes.push({t:'pill',z,cx:R+q[1]*S,cy:R+q[0]*S*1.05,L,W:Wd,ang});});
   if(isAod){shapes.filter(s=>!s.z).forEach((s,i)=>aodShape(s,i+1,0.12,p));shapes.filter(s=>s.z).forEach((s,i)=>aodShape(s,i*2,0.12,p));}
   else{shapes.filter(s=>!s.z).forEach(s=>glass(s,shapes,lt-0.2,b,p));shapes.filter(s=>s.z).forEach(s=>shadow(s));shapes.filter(s=>s.z).forEach((s,i)=>glass(s,shapes,lt+(i?0.2:-0.2),b,p));}
   x.restore();
  }
  for(let i=0;i<8;i++){scene(i,false);out['bg_'+i]=down(c);scene(i,true);out['aod_'+i]=down(c);}
  // stars overlay (transparent)
  aod=false;x.setTransform(1,0,0,1,0,0);x.clearRect(0,0,S,S);
  const sp={0:'',1:'#000',2:'#d8e2ff',3:'#ffffff'};
  drawStars(1234,['','#000','#d8e2ff','#fff'],1,false,0);drawStars(1234,['','#000','#d8e2ff','#fff'],1,true,0);
  out['stars']=down(c);
  // glass digits
  const F=90,sc=0.95,hc=1.12,pad=10;
  const gc=document.createElement('canvas'),g=gc.getContext('2d');
  g.font='700 '+F+'px PoppinsL';g.textBaseline='alphabetic';
  const m0=g.measureText('0'),asc=m0.actualBoundingBoxAscent,desc=m0.actualBoundingBoxDescent;
  const gh=Math.ceil((asc+desc)*hc)+pad*2;
  const meta={};
  const glyph=(ch,kind)=>{
   g.font='700 '+F+'px PoppinsL';
   const adv=g.measureText(ch).width;const gw=Math.ceil(adv*sc)+pad*2;
   gc.width=gw;gc.height=gh;g.font='700 '+F+'px PoppinsL';g.textBaseline='alphabetic';
   const put=(ctx)=>{ctx.setTransform(sc,0,0,hc,pad,pad+asc*hc);ctx.font='700 '+F+'px PoppinsL';ctx.textBaseline='alphabetic';ctx.fillText(ch,0,0);ctx.setTransform(1,0,0,1,0,0);};
   const mk=()=>{const k=document.createElement('canvas');k.width=gw;k.height=gh;return k;};
   const mask=mk(),mg=mask.getContext('2d');mg.fillStyle='#fff';put(mg);
   const ring=(r,col)=>{const k=mk(),q=k.getContext('2d');q.drawImage(mask,0,0);q.globalCompositeOperation='destination-out';q.filter=mf('erode',r);q.drawImage(mask,0,0);q.filter='none';q.globalCompositeOperation='source-in';q.fillStyle=col;q.fillRect(0,0,gw,gh);return k;};
   g.clearRect(0,0,gw,gh);
   if(kind==='glass'){
    // soft shadow + glow
    g.save();g.shadowColor='rgba(0,0,0,0.35)';g.shadowBlur=10;g.shadowOffsetY=3;g.globalAlpha=0.35;g.drawImage(mask,0,0);g.restore();
    // body
    const body=mk(),bq=body.getContext('2d');bq.drawImage(mask,0,0);bq.globalCompositeOperation='source-in';
    const gr=bq.createLinearGradient(0,pad,0,gh-pad);gr.addColorStop(0,'rgba(255,255,255,0.42)');gr.addColorStop(0.45,'rgba(255,255,255,0.14)');gr.addColorStop(1,'rgba(220,230,255,0.30)');
    bq.fillStyle=gr;bq.fillRect(0,0,gw,gh);
    g.globalCompositeOperation='destination-out';g.drawImage(mask,0,0);g.globalCompositeOperation='source-over';
    g.drawImage(body,0,0);
    // inner soft band
    const ib=ring(6,'rgba(255,255,255,0.18)');g.save();g.filter='blur(2px)';g.drawImage(ib,0,0);g.restore();
    // bright rim, stronger on top-left
    const rim=mk(),rq=rim.getContext('2d');rq.drawImage(ring(2.5,'#fff'),0,0);rq.globalCompositeOperation='source-in';
    const rg=rq.createLinearGradient(0,0,gw,gh);rg.addColorStop(0,'rgba(255,255,255,1)');rg.addColorStop(0.5,'rgba(255,255,255,0.45)');rg.addColorStop(1,'rgba(255,255,255,0.85)');rq.fillStyle=rg;rq.fillRect(0,0,gw,gh);
    g.drawImage(rim,0,0);
   } else {
    g.save();g.shadowColor='rgba(170,180,255,0.7)';g.shadowBlur=5;g.drawImage(ring(1.6,'#fff'),0,0);g.restore();
   }
   meta[(kind==='glass'?'g_':'a_')+ch]={w:gw,h:gh};
   return gc.toDataURL('image/png');
  };
  const names={':':'colon'};
  '0123456789:'.split('').forEach(ch=>{out['dg_'+(names[ch]||ch)]=glyph(ch,'glass');out['da_'+(names[ch]||ch)]=glyph(ch,'aod');});
  out.__meta=JSON.stringify({gh,meta});
  return out;
 });
 for(const [k,v] of Object.entries(res)){
  if(k==='__meta'){fs.writeFileSync(OUT+'/meta.json',v);continue;}
  fs.writeFileSync(OUT+'/'+k+'.png',Buffer.from(v.split(',')[1],'base64'));
 }
 // preview: full simulator frame
 await pg.evaluate(()=>{aod=false;wakeTs=-1e6;ts=0;lastT=null;draw(performance.now());});
 const pv=await pg.evaluate(()=>{const k=document.createElement('canvas');k.width=450;k.height=450;const g=k.getContext('2d');g.beginPath();g.arc(225,225,225,0,7);g.clip();g.drawImage(c,0,0,450,450);return k.toDataURL('image/png');});
 fs.writeFileSync(OUT+'/preview.png',Buffer.from(pv.split(',')[1],'base64'));
 await b.close();
 console.log('done',Object.keys(res).length);
})();
