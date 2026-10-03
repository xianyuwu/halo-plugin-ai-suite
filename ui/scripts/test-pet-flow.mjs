import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { transform } from '../node_modules/esbuild/lib/main.js';
import { ref, computed, reactive } from '../node_modules/vue/dist/vue.runtime.esm-bundler.js';
const file=fs.readFileSync('ui/src/views/WidgetView.vue','utf8');
let source=file.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*;\s*$/mg,'');
source+='\nglobalThis.qa={setBackgroundZoom,backgroundZoom,backgroundPan,resetBackgroundView,cleanupStrokeStyle,colorImageAspect,requestExpressionGenerate,confirmExpressionRegenerate,closePetModal,openPetEditor,pollPetJob,petModalPhase,petModalOpen,expressionPet,showExpressionRegenerateDialog,petJobStatus,petJobId,petJobKind,pets,checkPetModel,petModelState,petModelReady,petModelMessage,petExpressionMode,pendingExpressionState,requestSingleExpression,openCandidateReview,candidateOperation,candidateReviewState,candidateReviewBusy,petProgress,petElapsedSeconds,petProgressLabel,form,petAvatarCrop,petAvatarXPercent,petAvatarYPercent,petAvatarZoom,selectedPetManifest,resetPetAvatarCrop,petPresets};';
const code=(await transform(source,{loader:'ts',format:'cjs'})).code;
let requests=[]; let timers=[];
const pet={id:'test-pet',name:'Test',style:'soft-3d',backgroundStatus:'approved',images:{idle:'idle',blink:'blink',happy:'happy',sad:'sad',thinking:'thinking'}};
const ctx={ref,computed,reactive,watch:()=>{},onMounted:()=>{},onBeforeUnmount:()=>{},ICON_PRESETS:[],TRIGGER_SHAPES:[],Toast:{success:()=>{},error:()=>{}},window:{location:{origin:'http://test'},addEventListener:()=>{},removeEventListener:()=>{}},URL,Date,console,
 setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout:()=>{},
 fetch:async(url,init)=>{requests.push({url,init});let data=url.endsWith('/model-status')?{available:true,message:'ready'}:url.endsWith('/regenerate')?{success:true,jobId:'job'}:url.includes('/jobs/')?{job:{status:'done',petId:pet.id}}:url.endsWith('/list')?{pets:[pet]}:{presets:[],policies:[]};return {ok:true,headers:{get:()=> 'application/json'},json:async()=>data};}};
vm.createContext(ctx);vm.runInContext(code,ctx);const q=ctx.qa;
q.openPetEditor(pet);for(let i=0;i<8;i++)await Promise.resolve();assert.equal(q.petModalPhase.value,'complete');
q.requestExpressionGenerate();assert.equal(q.showExpressionRegenerateDialog.value,true);assert.equal(requests.filter(r=>r.init?.method==='POST').length,0);
q.showExpressionRegenerateDialog.value=false;await q.confirmExpressionRegenerate();assert.equal(requests.filter(r=>r.init?.method==='POST').length,0);
q.closePetModal();assert.equal(q.petModalOpen.value,false);assert.equal(requests.filter(r=>r.init?.method==='POST').length,0);
q.openPetEditor(pet);for(let i=0;i<8;i++)await Promise.resolve();q.requestExpressionGenerate();await q.confirmExpressionRegenerate();assert.equal(requests.filter(r=>r.url.endsWith('/regenerate')).length,1);assert.equal(q.petJobStatus.value,'pending');
q.requestExpressionGenerate();assert.equal(requests.filter(r=>r.url.endsWith('/regenerate')).length,1);
await timers.pop()();assert.equal(q.petModalPhase.value,'complete');assert.equal(q.petJobStatus.value,'done');
q.closePetModal();assert.equal(requests.filter(r=>r.url.endsWith('/regenerate')).length,1);
q.openPetEditor({...pet,images:{idle:'idle'}});assert.equal(q.petModalPhase.value,'expressions');for(let i=0;i<8;i++)await Promise.resolve();q.requestExpressionGenerate();for(let i=0;i<8;i++)await Promise.resolve();assert.equal(requests.filter(r=>r.url.endsWith('/regenerate')).length,2);
q.openPetEditor({...pet,backgroundStatus:'pending'});assert.equal(q.petModalPhase.value,'background');q.requestExpressionGenerate();assert.equal(requests.filter(r=>r.url.endsWith('/regenerate')).length,2);
console.log('PASS: existing results preview, cancel/finish issue zero requests, confirmed regeneration issues one request, pending duplicate blocked, success enters complete, first generation unchanged, unapproved background blocked.');

const originalFetch=ctx.fetch;
ctx.fetch=async()=>({ok:true,headers:{get:()=> 'application/json'},json:async()=>({available:false,reason:'missing',message:'请配置生图模型'})});
q.openPetEditor(pet);for(let i=0;i<8;i++)await Promise.resolve();
assert.equal(q.petModelReady.value,false);assert.equal(q.petModelMessage.value,'请配置生图模型');
q.requestExpressionGenerate();assert.equal(q.showExpressionRegenerateDialog.value,false);
ctx.fetch=async()=>{throw new Error('offline');};await q.checkPetModel();assert.equal(q.petModelState.value,'unavailable');
ctx.fetch=originalFetch;await q.checkPetModel();assert.equal(q.petModelReady.value,true);
console.log('PASS: missing model blocks regeneration, failed check remains blocked, retry restores ready state.');

let approvedPet={...pet,expressionMode:'motion',images:{...pet.images,happy:'approved-happy'},expressionCandidates:{}};
const candidatePet={...pet,expressionCandidates:{happy:{imageUrl:'candidate-v1',originalUrl:'candidate-v1',mode:'motion',masterUrl:'idle',masterRevision:0}}};
ctx.fetch=async(url,init)=>{requests.push({url,init});return {ok:true,headers:{get:()=> 'application/json'},json:async()=>url.endsWith('/model-status')?{available:true,message:'ready'}:url.endsWith('/expressions/generate')?{success:true,jobId:'candidate-job'}:url.endsWith('/approve')?{success:true,pet:approvedPet}:url.endsWith('/list')?{pets:[approvedPet]}:{presets:[],policies:[]}};};
q.openPetEditor(pet);for(let i=0;i<8;i++)await Promise.resolve();q.petExpressionMode.value='motion';
let posts=requests.filter(r=>r.init?.method==='POST').length;
q.requestSingleExpression('happy');assert.equal(q.showExpressionRegenerateDialog.value,true);assert.equal(requests.filter(r=>r.init?.method==='POST').length,posts);
await q.confirmExpressionRegenerate();let generation=requests.filter(r=>r.url.endsWith('/expressions/generate'));assert.equal(generation.length,1);assert.equal(JSON.parse(generation[0].init.body).state,'happy');assert.equal(JSON.parse(generation[0].init.body).mode,'motion');
q.requestSingleExpression('sad');assert.equal(requests.filter(r=>r.url.endsWith('/expressions/generate')).length,1);
q.petJobStatus.value='';q.showExpressionRegenerateDialog.value=false;q.openPetEditor(candidatePet);for(let i=0;i<8;i++)await Promise.resolve();
q.openCandidateReview('happy');assert.equal(q.candidateReviewState.value,'happy');assert.equal(q.petModalPhase.value,'background');
await q.candidateOperation('approve');assert.equal(q.expressionPet.value.images.happy,'approved-happy');assert.equal(q.candidateReviewState.value,null);assert.equal(q.petModalPhase.value,'complete');
const approval=requests.find(r=>r.url.endsWith('/approve'));assert.equal(JSON.parse(approval.init.body).candidateUrl,'candidate-v1');
console.log('PASS: single-frame confirmation issues one state-specific request, duplicate pending requests blocked, review approval carries candidate version and returns to result list.');

q.petJobStatus.value='pending';q.petJobKind.value='candidates';q.expressionPet.value=pet;q.petJobId.value='progress-job';
let progressPet={...pet,expressionCandidates:{blink:{imageUrl:'new-blink',mode:'motion'}}};
ctx.fetch=async(url)=>({ok:true,headers:{get:()=> 'application/json'},json:async()=>url.includes('/jobs/')?{job:{status:'pending',progress:{stage:'model',currentState:'happy',completed:1,total:4,elapsedSeconds:42}}}:url.endsWith('/list')?{pets:[progressPet]}:{presets:[],policies:[]}});
q.pollPetJob();await timers.pop()();
assert.equal(q.petProgress.completed,1);assert.equal(q.petElapsedSeconds.value,42);assert.match(q.petProgressLabel.value,/开心.*2\/4/);
assert.equal(q.expressionPet.value.expressionCandidates.blink.imageUrl,'new-blink');
ctx.fetch=async()=>({ok:true,headers:{get:()=> 'application/json'},json:async()=>({job:{status:'failed',error:'original failure',progress:{stage:'model',currentState:'happy',completed:1,total:4,elapsedSeconds:43}}})});
// Failure list refresh needs its normal fixture.
const progressFetch=ctx.fetch;ctx.fetch=async(url)=>url.includes('/jobs/')?progressFetch(url):({ok:true,headers:{get:()=> 'application/json'},json:async()=>url.endsWith('/list')?{pets:[progressPet]}:{presets:[],policies:[]}});
await timers.pop()();assert.equal(q.petJobStatus.value,'failed');assert.equal(q.petProgress.completed,1);assert.equal(q.petElapsedSeconds.value,43);
console.log('PASS: polling displays actual frame count/time, refreshes persisted candidates, and preserves progress after failure.');

q.petJobStatus.value='';q.pets.value=[pet];q.form.widgetTriggerType='pet';q.form.widgetPetId=pet.id;
q.petAvatarXPercent.value=60;q.petAvatarYPercent.value=40;q.petAvatarZoom.value=250;
assert.equal(q.petAvatarCrop.value.size,0.4);assert.equal(q.selectedPetManifest.value.avatarCrop.centerX,0.6);
const stored=q.form.widgetPetAvatarCrops;q.form.widgetPetId='other';assert.equal(q.petAvatarCrop.value.centerX,0.5);
q.form.widgetPetId=pet.id;assert.equal(q.petAvatarCrop.value.centerX,0.6);assert.equal(q.form.widgetPetAvatarCrops,stored);
q.resetPetAvatarCrop();assert.equal(q.petAvatarCrop.value.centerX,0.5);
q.form.widgetTriggerType='icon';assert.equal(q.selectedPetManifest.value,null);
console.log('PASS: pet avatar crop is scoped, restored on switching, resettable, and absent in static mode.');

// Lost jobs terminate polling and reload results, without starting a paid request.
ctx.fetch=async(url,init)=>{requests.push({url,init});return {ok:true,headers:{get:()=> 'application/json'},json:async()=>url.includes('/jobs/')?{success:false,code:'JOB_GONE',message:'任务已失效'}:url.endsWith('/list')?{pets:[pet]}:{presets:[],policies:[]}};};
q.petJobId.value='lost';q.petJobStatus.value='pending';q.pollPetJob();
posts=requests.filter(r=>r.init?.method==='POST').length;
await timers.pop()();assert.equal(q.petJobStatus.value,'failed');
assert.equal(requests.filter(r=>r.init?.method==='POST').length,posts);
ctx.fetch=async()=>{throw new Error('offline');};q.petJobId.value='offline';q.petJobStatus.value='pending';q.pollPetJob();
for(let i=0;i<5;i++)await timers.pop()();assert.equal(q.petJobStatus.value,'failed');
console.log('PASS: lost jobs and five consecutive connection failures stop polling without duplicate generation.');

// A selected pet previews its public snapshot while the draft awaits approval.
ctx.fetch=originalFetch;q.petJobStatus.value='';q.form.widgetTriggerType='pet';q.form.widgetPetId='test-pet';
q.pets.value=[{...pet,backgroundStatus:'pending',images:{idle:'draft'},publishedVersion:{name:'published',style:'pixel',pixelGridSize:96,images:{idle:'published-idle'},expressionRegion:{centerX:.4,centerY:.3,width:.2,height:.2}}}];
assert.equal(q.selectedPetManifest.value.images.idle,'published-idle');assert.equal(q.selectedPetManifest.value.imageRendering,'pixelated');
q.pets.value=[{...pet,backgroundStatus:'pending',images:{idle:'draft'}}];q.form.widgetPetPreset='';assert.equal(q.selectedPetManifest.value,null);
console.log('PASS: selected preview keeps the approved version and never exposes an unapproved first mother.');

q.colorImageAspect.value='2 / 1';
let brush=q.cleanupStrokeStyle({x:.5,y:.5,radius:.04});
assert.equal(brush.width,'4%');assert.equal(brush.height,'8%');
q.colorImageAspect.value='1 / 2';brush=q.cleanupStrokeStyle({x:.5,y:.5,radius:.04});
assert.equal(brush.width,'8%');assert.equal(brush.height,'4%');
q.colorImageAspect.value='1';brush=q.cleanupStrokeStyle({x:.5,y:.5,radius:.06});
assert.equal(brush.width,'12%');assert.equal(brush.height,'12%');
console.log('PASS: eraser preview stays circular and matches the short-side radius on square, landscape and portrait images.');

q.resetBackgroundView();q.setBackgroundZoom(2,100,80);
assert.equal(q.backgroundZoom.value,2);assert.equal(q.backgroundPan.x,-100);assert.equal(q.backgroundPan.y,-80);
q.setBackgroundZoom(1,100,80);assert.equal(q.backgroundPan.x,0);assert.equal(q.backgroundPan.y,0);
q.setBackgroundZoom(100,100,80);assert.equal(q.backgroundZoom.value,6);
q.setBackgroundZoom(.01,100,80);assert.equal(q.backgroundZoom.value,.5);
q.resetBackgroundView();assert.equal(q.backgroundZoom.value,1);assert.equal(q.backgroundPan.x,0);
console.log('PASS: cursor-centered zoom is reversible, bounded and resettable without changing image coordinates.');
