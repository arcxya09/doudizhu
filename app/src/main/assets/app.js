'use strict';
const $=id=>document.getElementById(id),names=['我','小林','老周'],levels={easy:'简单',normal:'普通',hard:'困难'};
let state,selected=new Set(),timer=null,paused=false;
function read(key,fallback){try{return JSON.parse(localStorage.getItem(key))??fallback;}catch{return fallback;}}
let stats=read('ddz-stats',{games:0,wins:0,score:0,settledIds:[]});stats.settledIds??=[];
let difficulty=read('ddz-level','normal');if(!levels[difficulty])difficulty='normal';
function save(){try{localStorage.setItem('ddz-state',JSON.stringify(state));localStorage.setItem('ddz-stats',JSON.stringify(stats));localStorage.setItem('ddz-level',JSON.stringify(difficulty));}catch{}}
function fresh(){clearTimeout(timer);state=DDZ.newGame(difficulty);state.id=Date.now()+'-'+Math.random();selected.clear();save();render();schedule();}
function textCard(c){const r=DDZ.rank(c);return r===16?'小王':r===17?'大王':({11:'J',12:'Q',13:'K',14:'A',15:'2'}[r]||r)+['♠','♥','♣','♦'][c%4];}
function card(c,selectable=false){const e=document.createElement(selectable?'button':'span');e.className='card';if(c===null){e.classList.add('back');e.textContent='♠';return e;}const r=DDZ.rank(c);if(c<52&&[1,3].includes(c%4)||r===17)e.classList.add('red');if(r>=16)e.classList.add('joker');const t=document.createElement('span');t.textContent=r>=16?(r===16?'小王':'大王'):({11:'J',12:'Q',13:'K',14:'A',15:'2'}[r]||r);e.append(t);const suit=document.createElement('span');suit.className='suit';suit.textContent=r>=16?'★':['♠','♥','♣','♦'][c%4];e.append(suit);if(selectable){e.setAttribute('aria-label',textCard(c));e.setAttribute('aria-pressed',selected.has(c));e.disabled=state.phase!=='play'||state.turn!==0;if(selected.has(c))e.classList.add('selected');e.onclick=()=>{if(selected.has(c))selected.delete(c);else selected.add(c);renderHand();};}return e;}
function showCards(id,cs){$(id).replaceChildren(...cs.map(c=>card(c)));}
function renderHand(){$('hand').replaceChildren(...DDZ.sort(state.hands[0]).reverse().map(c=>card(c,true)));const m=DDZ.classify([...selected]);$('selection').textContent=selected.size?`已选 ${selected.size} 张 · ${m?DDZ.labels[m.type]:'请选择完整牌型'}`:'点击手牌选中，再点一次取消';}
function button(label,fn,primary=false,disabled=false){const b=document.createElement('button');b.textContent=label;b.className=primary?'primary':'';b.disabled=disabled;b.onclick=fn;$('actions').append(b);}
function render(){
 $('difficultyLabel').textContent=levels[state.difficulty]+'牌桌';$('stakes').textContent=`底分 ${state.highBid||'—'} · ${state.multiplier} 倍`;
 for(let p=0;p<3;p++){$('role'+p).textContent=state.landlord<0?'':state.landlord===p?'地主':'农民';if(p){$('count'+p).textContent=state.hands[p].length+' 张手牌';$('player'+p).classList.toggle('active',state.turn===p&&state.phase!=='over');const last=state.history.filter(x=>x.p===p).at(-1);$('status'+p).textContent=last?(last.cards?last.cards.map(textCard).join(' '):last.text):'等待开局';}}
 $('handCount').textContent=state.hands[0].length+' 张';$('record').textContent=`${stats.wins} 胜 / ${stats.games} 局 · ${stats.score} 分`;
 showCards('bottom',state.landlord<0?[null,null,null]:state.bottom);
 $('notice').textContent=state.phase==='bid'?(state.turn===0?'轮到你叫地主':names[state.turn]+'正在叫分…'):state.phase==='redeal'?'无人叫地主，重新发牌…':state.phase==='over'?'本局结束':state.turn===0?(state.last?'轮到你出牌':'轮到你自由出牌'):names[state.turn]+'正在思考…';
 showCards('last',state.last?.cards||[]);$('lastlabel').textContent=state.last?names[state.lastPlayer]+' · '+DDZ.labels[state.last.type]:'';
 $('log').textContent=state.history.slice(-2).map(x=>names[x.p]+'：'+x.text).join('　');renderHand();$('actions').replaceChildren();
 if(state.phase==='bid'&&state.turn===0){button('不叫',()=>humanBid(0));for(let i=1;i<=3;i++)button(i+' 分',()=>humanBid(i),i===3,i<=state.highBid);}
 else if(state.phase==='play'&&state.turn===0){button('不出',()=>humanPlay([]),false,!state.last);button('提示',hint);button('重选',()=>{selected.clear();renderHand();});button('出牌',()=>humanPlay([...selected]),true);}
 else if(state.phase==='over')button('再来一局',fresh,true);else button('等待对手…',()=>{},false,true);
}
function humanBid(n){if(state.turn!==0)return;DDZ.actBid(state,n);advance();}
function humanPlay(cs){if(state.turn!==0)return;try{DDZ.play(state,cs);selected.clear();advance();}catch(e){$('notice').textContent=e.message;}}
function context(){return {player:state.turn,landlord:state.landlord,lastPlayer:state.lastPlayer,counts:state.hands.map(h=>h.length)};}
function hint(){const m=DDZ.ai(state.hands[0],state.last,context(),'hard');selected=new Set(m?.cards||[]);renderHand();if(!m)$('notice').textContent=DDZ.moves(state.hands[0],state.last).length?'建议让队友继续出牌':'没有能压过的牌，可以不出';}
function advance(){if(state.phase==='over')settle();save();render();if(state.phase==='over')showResult();else schedule();}
function schedule(){clearTimeout(timer);if(paused||document.hidden||$('menu').open||state.phase==='over')return;if(state.phase==='redeal'){timer=setTimeout(fresh,1300);return;}if(state.turn===0)return;
 timer=setTimeout(()=>{const hand=state.hands[state.turn];if(state.phase==='bid')DDZ.actBid(state,DDZ.bid(hand,state.highBid,state.difficulty));else DDZ.play(state,DDZ.ai(hand,state.last,context(),state.difficulty)?.cards||[]);advance();},850);
}
function settle(){const won=(state.winner===state.landlord)===(state.landlord===0);state.delta=(won?1:-1)*state.highBid*state.multiplier*(state.landlord===0?2:1);if(!stats.settledIds.includes(state.id)){stats.games++;if(won)stats.wins++;stats.score+=state.delta;stats.settledIds.push(state.id);stats.settledIds=stats.settledIds.slice(-100);}state.settled=true;}
function showResult(){const won=state.delta>0;$('resultTitle').textContent=won?'好牌，赢了！':'下局再来';$('resultText').textContent=`${state.winner===state.landlord?'地主':'农民'}获胜${state.spring?' · '+(state.winner===state.landlord?'春天':'反春天'):''} · ${state.multiplier} 倍\n本局 ${state.delta>0?'+':''}${state.delta} 分`;$('reveal').replaceChildren(...[1,2].map(p=>{const e=document.createElement('p');e.textContent=names[p]+'剩余：'+(state.hands[p].map(textCard).join(' ')||'已出完');return e;}));if(!$('result').open)$('result').showModal();}
$('settings').onclick=()=>{clearTimeout(timer);$('level').value=difficulty;$('menu').showModal();};$('closeMenu').onclick=()=>$('menu').close();$('menu').addEventListener('close',schedule);$('level').onchange=()=>{difficulty=$('level').value;save();};$('restart').onclick=()=>{if(state.phase!=='over'&&!confirm('结束当前牌局并重新发牌？当前局不计战绩。'))return;$('menu').close();fresh();};$('again').onclick=()=>{$('result').close();fresh();};$('closeResult').onclick=()=>$('result').close();
window.ddzPause=()=>{paused=true;clearTimeout(timer);save();};window.ddzResume=()=>{paused=false;schedule();};document.addEventListener('visibilitychange',()=>{if(document.hidden){clearTimeout(timer);save();}else schedule();});window.addEventListener('pagehide',save);
state=read('ddz-state',null);if(!state||state.version!==1||!Array.isArray(state.hands)||state.hands.length!==3||!levels[state.difficulty])fresh();else{if(state.phase==='over')settle();save();render();schedule();}
