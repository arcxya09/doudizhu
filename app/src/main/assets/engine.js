/* Pure offline rules and AI. Card ids 0..51 = 3..2, 52/53 = jokers. */
(function(root){
'use strict';
const rank=c=>c<52?3+Math.floor(c/4):c-36;
const sort=cs=>cs.slice().sort((a,b)=>rank(a)-rank(b)||a-b);
function groups(cs){const g={};for(const c of sort(cs))(g[rank(c)]??=[]).push(c);return g;}
function consecutive(rs){return rs.every((r,i)=>r<15&&(!i||r===rs[i-1]+1));}
function classify(cs){
 if(!cs.length||new Set(cs).size!==cs.length||cs.some(c=>!Number.isInteger(c)||c<0||c>53))return null;
 const g=groups(cs),rs=Object.keys(g).map(Number),n=cs.length,counts=rs.map(r=>g[r].length);
 const m=(type,key,span=1)=>({type,key,span,n});
 if(n===2&&rs[0]===16&&rs[1]===17)return m('rocket',17);
 if(rs.length===1){if(n===1)return m('single',rs[0]);if(n===2)return m('pair',rs[0]);if(n===3)return m('triple',rs[0]);if(n===4)return m('bomb',rs[0]);}
 if(n===4&&counts.includes(3))return m('tripleSingle',rs[counts.indexOf(3)]);
 if(n===5&&counts.includes(3)&&counts.includes(2))return m('triplePair',rs[counts.indexOf(3)]);
 if(n>=5&&counts.every(c=>c===1)&&consecutive(rs))return m('straight',rs.at(-1),rs.length);
 if(n>=6&&counts.every(c=>c===2)&&consecutive(rs))return m('pairs',rs.at(-1),rs.length);
 if(n>=6&&counts.every(c=>c===3)&&consecutive(rs))return m('plane',rs.at(-1),rs.length);
 // Airplane wings cannot reuse body ranks; single wings may include a pair, but not both jokers.
 for(const wing of [1,2]){
  const k=n/(3+wing);if(!Number.isInteger(k)||k<2)continue;
  for(let start=3;start+k-1<=14;start++){
   const body=Array.from({length:k},(_,i)=>start+i);
   if(!body.every(r=>g[r]?.length===3))continue;
   const rest=rs.filter(r=>!body.includes(r));
   if(wing===1&&!(g[16]&&g[17])&&rest.reduce((s,r)=>s+g[r].length,0)===k)return m('planeSingles',start+k-1,k);
   if(wing===2&&rest.length===k&&rest.every(r=>g[r].length===2))return m('planePairs',start+k-1,k);
  }
 }
 if(n===6&&counts.includes(4)&&!(g[16]&&g[17]))return m('fourSingles',rs[counts.indexOf(4)]);
 if(n===8&&counts.filter(c=>c===4).length===1&&counts.filter(c=>c===2).length===2)return m('fourPairs',rs[counts.indexOf(4)]);
 return null;
}
function beats(a,b){if(!a)return false;if(!b)return true;if(b.type==='rocket')return false;if(a.type==='rocket')return true;if(a.type==='bomb'&&b.type!=='bomb')return true;return a.type===b.type&&a.n===b.n&&a.span===b.span&&a.key>b.key;}
function choose(xs,k,fn,start=0,acc=[]){if(k===0){fn(acc);return;}for(let i=start;i<=xs.length-k;i++)choose(xs,k-1,fn,i+1,acc.concat(xs[i]));}
function moves(hand,target=null){
 const g=groups(hand),rs=Object.keys(g).map(Number),out=[],seen=new Set();
 function add(cs){const p=classify(cs);if(!beats(p,target))return;const key=sort(cs).map(rank).join(',');if(seen.has(key))return;seen.add(key);out.push({cards:sort(cs),...p});}
 for(const r of rs){for(let n=1;n<=g[r].length;n++)add(g[r].slice(0,n));
  if(g[r].length>=3)for(const s of rs.filter(s=>s!==r)){add(g[r].slice(0,3).concat(g[s].slice(0,1)));if(g[s].length>=2)add(g[r].slice(0,3).concat(g[s].slice(0,2)));}
 }
 if(g[16]&&g[17])add([52,53]);
 for(const mult of [1,2,3])for(let start=3;start<=14;start++){
  let body=[];
  for(let end=start;end<=14&&g[end]?.length>=mult;end++){
   body=body.concat(g[end].slice(0,mult));const k=end-start+1;
   if(k<(mult===1?5:mult===2?3:2))continue;add(body);
   if(mult!==3)continue;
   const others=rs.filter(r=>r<start||r>end);
   if(4*k<=hand.length)choose(others.flatMap(r=>g[r]),k,w=>add(body.concat(w)));
   if(5*k<=hand.length)choose(others.filter(r=>g[r].length>=2),k,w=>add(body.concat(w.flatMap(r=>g[r].slice(0,2)))));
  }
 }
 for(const r of rs.filter(r=>g[r].length===4)){
  const others=rs.filter(s=>s!==r);
  choose(others.flatMap(s=>g[s]),2,w=>add(g[r].concat(w)));
  choose(others.filter(s=>g[s].length>=2),2,w=>add(g[r].concat(w.flatMap(s=>g[s].slice(0,2)))));
 }
 return out;
}
const remove=(hand,cs)=>hand.filter(c=>!cs.includes(c));
function shapeCost(hand){
 const g=groups(hand);let cost=0;
 for(const [r,cs]of Object.entries(g)){cost+=cs.length===1?1.5:cs.length===2?1.15:cs.length===3?1:0.6;if(+r>=15)cost-=.35;}
 for(let r=3;r<=10;r++){let len=0;while(r+len<=14&&g[r+len])len++;if(len>=5){cost-=len*.55;r+=len-1;}}
 return cost;
}
function ai(hand,target,ctx={},difficulty='normal',rng=Math.random){
 const ms=moves(hand,target);if(!ms.length)return null;
 const win=ms.find(m=>m.n===hand.length);if(win)return win;
 const teammate=target&&ctx.player!==ctx.landlord&&ctx.lastPlayer!==ctx.landlord;
 if(teammate&&(difficulty!=='easy'||rng()<.8))return null;
 if(difficulty==='easy'){if(target&&rng()<.16)return null;const ordinary=ms.filter(m=>m.type!=='bomb'&&m.type!=='rocket');return (ordinary.length?ordinary:ms)[Math.floor(rng()*(ordinary.length||ms.length))];}
 const counts=ctx.counts||[17,17,17],enemySoon=counts.some((n,p)=>p!==ctx.player&&(ctx.player===ctx.landlord||p===ctx.landlord)&&n<=2);
 let best=null,bestScore=Infinity;
 for(const m of ms){const left=remove(hand,m.cards);let score=shapeCost(left)*3+m.key*.08;
  if(m.type==='bomb'||m.type==='rocket')score+=target?7:11;
  if(difficulty==='hard'){
   const before=groups(hand),after=groups(left);
   for(const r of Object.keys(before))if(before[r].length===4&&after[r]&&after[r].length<4)score+=4;
   if(enemySoon&&target)score-=m.key*.45;
   if(enemySoon&&!target&&m.type==='single')score+=counts.some((n,p)=>p!==ctx.player&&n===1)?8:0;
   if(!target&&ctx.player!==ctx.landlord&&counts[(ctx.player+1)%3]<=2&&(ctx.player+1)%3!==ctx.landlord)score+=m.key*.2;
  }
  if(score<bestScore){bestScore=score;best=m;}
 }
 return best;
}
function bid(hand,current,difficulty='normal',rng=Math.random){const g=groups(hand);let strength=(g[17]?2.5:0)+(g[16]?1.6:0)+(g[15]?.length||0)*.8+Object.values(g).filter(x=>x.length===4).length*2+(g[14]?.length||0)*.25;
 if(difficulty==='easy')strength+=rng()*3-1.5;
 const value=strength>=6?3:strength>=4?2:strength>=2.3?1:0;return value>current?value:0;}
function deal(rng=Math.random){const deck=Array.from({length:54},(_,i)=>i);for(let i=53;i>0;i--){const j=Math.floor(rng()*(i+1));[deck[i],deck[j]]=[deck[j],deck[i]];}return {hands:[sort(deck.slice(0,17)),sort(deck.slice(17,34)),sort(deck.slice(34,51))],bottom:deck.slice(51)};}
function newGame(difficulty='normal',rng=Math.random){const d=deal(rng);return {...d,version:1,difficulty,phase:'bid',turn:Math.floor(rng()*3),bidCount:0,highBid:0,bidder:-1,landlord:-1,last:null,lastPlayer:-1,passes:0,multiplier:1,played:[0,0,0],history:[],winner:-1,settled:false};}
function actBid(s,value){if(s.phase!=='bid'||![0,1,2,3].includes(value)||value>0&&value<=s.highBid)throw Error('叫分无效');const p=s.turn;s.history.push({p,text:value?`叫 ${value} 分`:'不叫'});if(value){s.highBid=value;s.bidder=p;}s.bidCount++;
 if(value===3||s.bidCount===3){if(s.bidder<0){s.phase='redeal';return;}s.landlord=s.bidder;s.hands[s.landlord]=sort(s.hands[s.landlord].concat(s.bottom));s.turn=s.landlord;s.phase='play';}else s.turn=(p+1)%3;
}
function play(s,cards){if(s.phase!=='play')throw Error('未开始出牌');const p=s.turn;
 if(!cards.length){if(!s.last)throw Error('首家必须出牌');s.history.push({p,text:'不出'});s.passes++;if(s.passes===2){s.last=null;s.passes=0;}s.turn=(p+1)%3;return;}
 if(cards.some(c=>!s.hands[p].includes(c)))throw Error('手牌不存在');const m=classify(cards);if(!beats(m,s.last))throw Error('牌型不合法或未压过上家');
 s.hands[p]=remove(s.hands[p],cards);s.last={...m,cards:sort(cards)};s.lastPlayer=p;s.passes=0;s.played[p]++;s.history.push({p,cards:sort(cards),text:labels[m.type]});if(m.type==='bomb'||m.type==='rocket')s.multiplier*=2;
 if(!s.hands[p].length){s.phase='over';s.winner=p;const spring=p===s.landlord?s.played.every((n,i)=>i===s.landlord||n===0):s.played[s.landlord]===1;if(spring){s.multiplier*=2;s.spring=true;}}else s.turn=(p+1)%3;
}
const labels={single:'单张',pair:'对子',triple:'三张',tripleSingle:'三带一',triplePair:'三带二',straight:'顺子',pairs:'连对',plane:'飞机',planeSingles:'飞机带单',planePairs:'飞机带对',fourSingles:'四带二',fourPairs:'四带两对',bomb:'炸弹',rocket:'王炸'};
const api={rank,sort,groups,classify,beats,moves,ai,bid,deal,newGame,actBid,play,remove,labels};if(typeof module!=='undefined')module.exports=api;root.DDZ=api;
})(typeof globalThis!=='undefined'?globalThis:this);
