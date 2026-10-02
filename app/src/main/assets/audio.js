/* Original synthesized pentatonic loop and cues: no audio downloads or third-party assets. */
(function(root){
 let ctx=null,loop=null,step=0,active=false;
 let prefs={music:true,effects:true,volume:45};try{Object.assign(prefs,JSON.parse(localStorage.getItem('ddz-audio'))||{});}catch{}
 const melody=[72,76,79,76,74,69,67,69,72,74,76,79,81,79,76,74,72,67,69,72,74,76,74,69,67,69,72,76,74,69,67,0];
 function note(midi,duration,volume,type='sine',delay=0){if(!ctx||ctx.state!=='running'||!midi)return;const o=ctx.createOscillator(),g=ctx.createGain(),t=ctx.currentTime+delay;o.type=type;o.frequency.value=440*Math.pow(2,(midi-69)/12);g.gain.setValueAtTime(0,t);g.gain.linearRampToValueAtTime(volume*prefs.volume/100,t+.025);g.gain.exponentialRampToValueAtTime(.0001,t+duration);o.connect(g);g.connect(ctx.destination);o.start(t);o.stop(t+duration+.03);o.onended=()=>{o.disconnect();g.disconnect();};}
 function tick(){if(!active||!prefs.music)return;note(melody[step%melody.length],.7,.12,'triangle');if(step%4===0){note([48,53,55,48][Math.floor(step/8)%4],1.8,.08);note([55,60,62,55][Math.floor(step/8)%4],1.8,.035);}step++;}
 function start(){clearInterval(loop);loop=null;if(active&&prefs.music){tick();loop=setInterval(tick,480);}}
 async function unlock(){try{ctx??=new (window.AudioContext||window.webkitAudioContext)();active=true;if(ctx.state==='suspended')await ctx.resume();if(!loop)start();}catch{}}
 function pause(){active=false;clearInterval(loop);loop=null;if(ctx?.state==='running')ctx.suspend().catch(()=>{});}
 function resume(){if(ctx)unlock();}
 function set(key,value){prefs[key]=value;try{localStorage.setItem('ddz-audio',JSON.stringify(prefs));}catch{}if(key==='music')start();}
 function effect(kind){if(!prefs.effects||!active)return;const tones={select:[84],play:[67,74],pass:[55],bid:[72,79],bomb:[43,55,67],turn:[79,84],win:[72,76,79,84],lose:[67,64,60],error:[54,52]};(tones[kind]||tones.play).forEach((n,i)=>note(n,.2,kind==='select'?.07:.16,'sine',i*.09));}
 document.addEventListener('pointerdown',unlock,{once:true});document.addEventListener('keydown',unlock,{once:true});root.Sound={prefs,set,unlock,pause,resume,effect};
})(globalThis);
