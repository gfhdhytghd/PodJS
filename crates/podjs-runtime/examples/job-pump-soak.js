// Framework-neutral synthetic host fixture. The C ABI supplies real input and
// lifecycle events. Network errors are explicit synthetic completion facts.
const saved=JSON.parse(pod.kvGet('job-soak')||'null');
let inputs=saved?.inputs||0,page=saved?.page||0,chapter=saved?.chapter||0;
pod.emit(JSON.stringify({t:'soak.restored',inputs}));
function checkpoint(){if(pod.kvSet('job-soak',JSON.stringify({inputs,page,chapter}))!==0)throw Error('checkpoint failed');}
function events(){
  for(const e of JSON.parse(pod.takeEvents()||'[]')){
    if(e.t==='lifecycle'){if(e.state!=='active')checkpoint();continue;}
    if(e.t!=='service.result')continue;
    const {seq,rows,hops,injectStall}=e.value;
    if(!e.ok){pod.emit(JSON.stringify({t:'soak.ack',seq,ok:false,checksum:0}));continue;}
    if(injectStall){const end=Date.now()+150;while(Date.now()<end){};}
    let n=0,checksum=0;
    function step(){
      const values=JSON.parse(rows);checksum+=values[n%values.length];n++;
      if(n<hops)Promise.resolve().then(step);
      else pod.emit(JSON.stringify({t:'soak.ack',seq,ok:true,checksum}));
    }
    Promise.resolve().then(step);
  }
}
globalThis.__pocketPumpServices=events;
globalThis.frame=(action)=>{
  events();inputs++;
  if(action===1)page++;else if(action===2)chapter++;else if(action===3)page=Math.max(0,page-1);
  pod.emit(JSON.stringify({t:'soak.input',inputs}));
};
