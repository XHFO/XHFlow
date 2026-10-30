(function(scope){
  'use strict';
  const SCHEMA='xh-tv-wled-layout/v1',OUTPUT_LIMIT=480;
  const COLOR_FIELDS={saturation:{label:'饱和度',min:0,max:2,default:1},gain:{label:'整体增益',min:0,max:2,default:1},offset:{label:'亮度偏移',min:-64,max:64,default:0},gamma:{label:'Gamma',min:.5,max:3,default:1},redGain:{label:'红色增益',min:0,max:2,default:1},greenGain:{label:'绿色增益',min:0,max:2,default:1},blueGain:{label:'蓝色增益',min:0,max:2,default:1}};
  function colorDefaults(){return Object.fromEntries(Object.entries(COLOR_FIELDS).map(([key,field])=>[key,field.default]));}
  function normalizeColor(raw){
    if(raw===undefined)raw={};if(!raw||typeof raw!=='object'||Array.isArray(raw))throw new Error('颜色校正配置格式无效。');
    const out={...raw};for(const [key,field]of Object.entries(COLOR_FIELDS)){const value=raw[key]===undefined?field.default:raw[key];if(typeof value!=='number'||!Number.isFinite(value)||value<field.min||value>field.max)throw new Error(field.label+'必须在 '+field.min+'–'+field.max+' 之间。');out[key]=value;}return out;
  }
  function runtimeDefaults(){return {sleepOff:true,sourceFilter:{enabled:false,allowed:[],offWhenExcluded:false}};}
  function normalizeRuntime(raw){
    if(raw===undefined)raw={};if(!raw||typeof raw!=='object'||Array.isArray(raw))throw new Error('运行条件配置格式无效。');
    const sleepOff=raw.sleepOff===undefined?true:raw.sleepOff,filter=raw.sourceFilter===undefined?{}:raw.sourceFilter;
    if(typeof sleepOff!=='boolean'||!filter||typeof filter!=='object'||Array.isArray(filter))throw new Error('休眠和输入源条件格式无效。');
    const enabled=filter.enabled===undefined?false:filter.enabled,allowed=filter.allowed===undefined?[]:filter.allowed,offWhenExcluded=filter.offWhenExcluded===undefined?false:filter.offWhenExcluded;
    if(typeof enabled!=='boolean'||typeof offWhenExcluded!=='boolean'||!Array.isArray(allowed)||allowed.length>128||allowed.some(id=>typeof id!=='string'||!id.trim()||id.length>512))throw new Error('输入源筛选必须使用有效的接口标识列表。');
    const unique=[...new Set(allowed.map(id=>id.trim()))];if(enabled&&!unique.length)throw new Error('启用输入源筛选时，请至少选择一个实际确认的接口。');
    return {...raw,sleepOff,sourceFilter:{...filter,enabled,allowed:unique,offWhenExcluded}};
  }
  function confirmedInputs(raw){
    if(!Array.isArray(raw))return [];const seen=new Set(),items=[];
    for(const item of raw){if(!item||typeof item.id!=='string'||!item.id.trim()||item.id.length>512||seen.has(item.id))continue;seen.add(item.id);items.push({id:item.id,label:typeof item.label==='string'&&item.label.trim()?item.label:item.id});}return items;
  }
  function conditionReady(value,inputs){
    let normalized;try{normalized=normalizeRuntime(value);}catch(error){return {ok:false,message:error.message};}
    if(!normalized.sourceFilter.enabled)return {ok:true,message:''};
    if(!inputs.length)return {ok:false,message:'未取得实际接口列表，暂不能保存已启用的输入源筛选。'};
    const supported=new Set(inputs.map(item=>item.id));if(normalized.sourceFilter.allowed.some(id=>!supported.has(id)))return {ok:false,message:'已保存的选项中有本次尚未确认的接口。请重新选择实际列表中的接口。'};
    return {ok:true,message:''};
  }
  function requestedEnabled(status){return status&&typeof status.enabled==='boolean'?status.enabled:null;}
  const SUSPENSIONS={display_sleep:'显示设备休眠中，已暂停同步。',input_unknown:'当前输入源尚未确认，已暂停同步。',input_not_allowed:'当前输入源不在允许列表，已暂停同步。',wled_power_pending:'正在等待灯带电源状态确认。',wled_power_error:'灯带电源控制未确认，已暂停同步。',config_invalid:'运行配置无效，已暂停同步。',initializing:'正在准备画面采集。',capture_error:'画面采集暂不可用，已暂停同步。'};
  function stable(value){
    if(Array.isArray(value))return '['+value.map(stable).join(',')+']';
    if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+stable(value[key])).join(',')+'}';
    return JSON.stringify(value);
  }
  function address(value){
    const text=String(value||'').trim(),parts=text.split('.');
    if(!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(text)||parts.some(part=>Number(part)>255))throw new Error('请填写 WLED 的局域网 IPv4 地址，例如 192.168.1.50。');
    const bytes=parts.map(Number),privateAddress=bytes[0]===10||(bytes[0]===172&&bytes[1]>=16&&bytes[1]<=31)||(bytes[0]===192&&bytes[1]===168);
    if(!privateAddress)throw new Error('WLED 地址必须是 10.x.x.x、172.16–31.x.x 或 192.168.x.x 范围内的局域网 IPv4。');
    return bytes.join('.');
  }
  function controller(raw){
    if(!raw||!Number.isInteger(raw.ledCount)||raw.ledCount<1||raw.ledCount>10000)throw new Error('配置中的灯珠总数必须为 1–10000 的整数。');
    return {address:address(raw.address),name:String(raw.name||'WLED').slice(0,256),ledCount:raw.ledCount};
  }
  function normalize(raw,M){
    if(!raw||raw.schema!==SCHEMA||raw.coordinateSystem!=='screen-front-normalized'||!raw.layout)throw new Error('请选择由本工具导出的电视灯带布局 JSON。');
    const device=controller(raw.controller),layout=M.clone(raw.layout);
    if(layout.version!==1||!layout.gap||!layout.cut||!layout.edges||!M.SIDES.every(side=>layout.edges[side]&&typeof layout.edges[side].full==='boolean'))throw new Error('布局结构不完整或版本不支持。');
    if(typeof layout.cut.position!=='number'||!Number.isFinite(layout.cut.position))throw new Error('断开位置无效。');
    for(const side of M.SIDES){const edge=layout.edges[side];if(typeof edge.count!=='number'||typeof edge.start!=='number'||typeof edge.end!=='number')throw new Error('各边数量和覆盖范围必须使用数字。');}
    if(typeof layout.gap.start!=='number'||typeof layout.gap.end!=='number')throw new Error('缺口范围必须使用数字。');
    const brightness=raw.output&&raw.output.brightness!==undefined?raw.output.brightness:.6;
    if(typeof brightness!=='number'||!Number.isFinite(brightness)||brightness<0||brightness>1)throw new Error('输出亮度必须在 0–1 之间。');
    const rawOutput=raw.output||{},rawSoftening=rawOutput.softening||{},rawBlackBars=rawOutput.blackBars||{};
    if(typeof rawOutput!=='object'||Array.isArray(rawOutput)||typeof rawSoftening!=='object'||Array.isArray(rawSoftening)||typeof rawBlackBars!=='object'||Array.isArray(rawBlackBars))throw new Error('灯光效果配置格式无效。');
    const softeningEnabled=rawSoftening.enabled===undefined?true:rawSoftening.enabled,softeningStrength=rawSoftening.strength===undefined?.35:rawSoftening.strength,blackBarsEnabled=rawBlackBars.enabled===undefined?true:rawBlackBars.enabled;
    if(typeof softeningEnabled!=='boolean'||typeof blackBarsEnabled!=='boolean')throw new Error('柔化和跳过黑边选项必须为 true 或 false。');
    if(typeof softeningStrength!=='number'||!Number.isFinite(softeningStrength)||softeningStrength<0||softeningStrength>1)throw new Error('柔化强度必须在 0–1 之间。');
    const autoStart=raw.preferences&&raw.preferences.autoStart!==undefined?raw.preferences.autoStart:true;
    if(typeof autoStart!=='boolean')throw new Error('自动恢复同步选项必须为 true 或 false。');
    const out=M.exportLayout(layout,device.ledCount);
    out.controller=device;out.output={...M.clone(rawOutput),brightness,softening:{...M.clone(rawSoftening),enabled:softeningEnabled,strength:softeningStrength},blackBars:{...M.clone(rawBlackBars),enabled:blackBarsEnabled},color:normalizeColor(rawOutput.color)};out.preferences={...M.clone(raw.preferences||{}),autoStart};out.runtime=normalizeRuntime(raw.runtime);
    return out;
  }
  function normalizePreview(raw){
    if(!raw||typeof raw.available!=='boolean')throw new Error('取色预览格式无效。');
    if(!raw.available)return {available:false,ageMs:Number.isFinite(raw.ageMs)?Math.max(0,raw.ageMs):0,panelWidth:0,panelHeight:0,activeRegion:{left:0,top:0,right:1,bottom:1},points:[],source:'screen-edges'};
    if(raw.source!=='screen-edges'||!Number.isFinite(raw.ageMs)||raw.ageMs<0||!Number.isFinite(raw.panelWidth)||raw.panelWidth<=0||!Number.isFinite(raw.panelHeight)||raw.panelHeight<=0)throw new Error('取色预览缺少有效画面信息。');
    const region=raw.activeRegion;
    if(!region||!['left','top','right','bottom'].every(key=>Number.isFinite(region[key])&&region[key]>=0&&region[key]<=1)||region.left>=region.right||region.top>=region.bottom)throw new Error('取色预览的有效画面范围无效。');
    if(!Array.isArray(raw.points)||![20,40].includes(raw.points.length))throw new Error('取色预览应包含 20 或 40 个取色点。');
    const points=raw.points.map(point=>{
      if(!point||!['top','right','bottom','left'].includes(point.edge)||![point.position,point.x,point.y].every(value=>Number.isFinite(value)&&value>=0&&value<=1)||!/^#[0-9a-f]{6}$/i.test(point.color))throw new Error('取色点坐标或颜色无效。');
      return {edge:point.edge,position:point.position,x:point.x,y:point.y,color:point.color};
    });
    const perEdge=points.length/4;if(!['top','right','bottom','left'].every(edge=>points.filter(point=>point.edge===edge).length===perEdge))throw new Error('取色预览的四边必须各有 5 或 10 个取色点。');
    return {available:points.length>0,ageMs:raw.ageMs,panelWidth:raw.panelWidth,panelHeight:raw.panelHeight,activeRegion:{...region},points,source:'screen-edges'};
  }
  function previewFresh(value,receivedAt,now,online,running,hidden){return !!(value&&value.available&&online&&running&&!hidden&&value.ageMs+Math.max(0,now-receivedAt)<=1500);}
  const model={stable,address,controller,normalize,normalizeColor,colorDefaults,COLOR_FIELDS,normalizeRuntime,runtimeDefaults,confirmedInputs,conditionReady,requestedEnabled,normalizePreview,previewFresh};scope.TVConsoleModel=model;
  if(typeof module!=='undefined'&&module.exports)module.exports=model;
  if(typeof document==='undefined')return;

  const $=id=>document.getElementById(id),P=scope.LayoutPreview,M=scope.LayoutModel;
  const token=new URLSearchParams(location.search).get('token')||'';
  let online=false,busy=false,initializing=false,savedSignature=null,savedDocument=null,pendingApply=false,lastStatus=null,polling=false,initialized=false,brightnessValue=.6,softeningValue=.35,colorValues=colorDefaults(),runtimeValues=runtimeDefaults(),inputsSupported=[],sourceOptionsKey='',outputExtras={},preferenceExtras={},command='',previewValue=null,previewReceivedAt=0,previewBusy=false,previewError='';
  const AUTOSAVE_DELAY=650,CACHE_KEY='xhflow.editor-draft.v1';
  let saving=false,saveTimer=null,savePhase='',saveError='',saveConflict=false,conflictRemote=null,knownFingerprint='',hasBaseline=false,hasEdits=false,editRevision=0,suppressEdits=0,observedSignature=null,gestureActive=false,addressEditing=false,uncertainWrite=null,verifiedAddress='',cacheError=false;
  function cachedDraft(){try{const value=JSON.parse(scope.localStorage.getItem(CACHE_KEY)||'null');return value&&value.version===1?value:null;}catch(error){return null;}}
  function rememberDraft(){
    try{const current=inspection();if(!current.valid||token&&!hasBaseline&&!hasEdits)return;if(token&&!dirty()){scope.localStorage.removeItem(CACHE_KEY);cacheError=false;return;}scope.localStorage.setItem(CACHE_KEY,JSON.stringify({version:1,document:current.value,baseSignature:savedSignature,updatedAt:Date.now()}));cacheError=false;}catch(error){cacheError=true;}
  }
  function cancelAutosave(){if(saveTimer!==null){clearTimeout(saveTimer);saveTimer=null;}}
  function scheduleAutosave(){
    cancelAutosave();
    if(!token||!online||!initialized||initializing||busy||saving||saveConflict||saveError||!hasEdits||(!dirty()&&!(pendingApply&&requestedEnabled(lastStatus)===true))||gestureActive||addressEditing||document.hidden)return;
    const current=inspection();if(!current.valid||!conditionReady(runtimeValues,inputsSupported).ok||P.getController().ledCount>OUTPUT_LIMIT)return;
    saveTimer=setTimeout(()=>{saveTimer=null;save();},AUTOSAVE_DELAY);
  }
  function edited(){
    if(suppressEdits)return;
    const current=inspection(),signature=current.valid?current.signature:null;
    if(signature!==null&&signature===observedSignature)return;
    observedSignature=signature;hasEdits=true;editRevision++;saveError='';rememberDraft();render();scheduleAutosave();
  }
  async function conflict(message,snapshot){
    saveConflict=true;saveError='';cancelAutosave();
    try{conflictRemote=snapshot||await editorSnapshot();}catch(error){conflictRemote=null;}
    feedback(message||'电视配置已在其他页面更改。本页调整已保留，请选择要使用的设置。');render();rememberDraft();
  }
  function feedback(text){$('consoleFeedback').textContent=text;}
  function draft(){const value=P.getMapping();value.controller.address=address($('wledAddress').value);value.output={...M.clone(outputExtras),brightness:brightnessValue,softening:{...(outputExtras.softening||{}),enabled:$('softeningEnabled').checked,strength:softeningValue},blackBars:{...(outputExtras.blackBars||{}),enabled:$('blackBarsEnabled').checked},color:{...(outputExtras.color||{}),...colorValues}};value.preferences={...M.clone(preferenceExtras),autoStart:$('autoStart').checked};value.runtime=M.clone(runtimeValues);return normalize(value,M);}
  function inspection(){try{const value=draft();return {valid:true,value,signature:stable(value)};}catch(error){return {valid:false,error:error.message,signature:null};}}
  function dirty(){const current=inspection();return saveConflict||!current.valid||current.signature!==savedSignature;}
  function render(){
    const current=inspection(),changed=saveConflict||!current.valid||current.signature!==savedSignature,running=!!(lastStatus&&lastStatus.running),requested=requestedEnabled(lastStatus),conditions=conditionReady(runtimeValues,inputsSupported),compatible=P.getController().ledCount<=OUTPUT_LIMIT;
    $('save').disabled=!online||busy||saving||saveConflict||!current.valid||!conditions.ok||!compatible||(!changed&&!!savedDocument&&!(requested===true&&pendingApply));
    $('save').textContent=saving?'正在保存…':'重试保存';$('save').hidden=!saveError||saveConflict;
    $('saveConflict').hidden=!saveConflict;$('useLocal').disabled=!online||busy||saving||!current.valid||!conditions.ok||!compatible;$('useRemote').disabled=!online||busy||saving;
    $('export').disabled=busy||!current.valid;$('import').disabled=busy;
    const canStart=!!savedDocument&&!changed&&current.valid&&conditions.ok&&compatible;
    $('syncSwitch').disabled=!online||busy||(requested===null&&!running)||(requested!==true&&!running&&!canStart);
    $('syncSwitch').setAttribute('aria-checked',String(online&&requested===true));$('syncSwitch').setAttribute('aria-label',online?'XH光流总开关':'XH光流总开关，电视未连接');$('syncSwitch').setAttribute('aria-busy',String(!!command));
    const requestedLabel=requested===null?'总开关状态未知':requested===true?'已开启':'已关闭';
    $('syncSwitchLabel').textContent=!online?'总开关状态未知':command==='stopping'?'正在关闭…':command==='starting'?'正在开启…':command==='applying'?'正在应用…':requestedLabel;
    $('applyChanges').hidden=!online||requested!==true||!pendingApply||saving;$('applyChanges').disabled=!online||busy||saving||!canStart;
    $('pendingApplyNotice').hidden=!online||!pendingApply;$('pendingApplyNotice').textContent=requested===true?'电视中有已保存但尚未应用的设置。'+(changed?'本页调整将自动保存并应用。':'可重试应用已保存的设置。'):'已保存的设置将在下次开启同步时生效。';
    $('saveHint').textContent=cacheError&&(!token||!online)?'浏览器暂不能保留本页调整，请保持页面打开或导出配置。':!token?'演示中的有效配置自动保存在此浏览器，不会控制真实灯带。':saveConflict?'本页与电视设置不一致，自动保存已暂停。':saveError?saveError:!current.valid?'请补全输入；电视继续使用上次有效设置。':!conditions.ok?conditions.message:requested===true?'调整停下后自动保存并应用；连续拖动时不会中断编辑。':'调整自动保存到电视；总开关关闭时保持关闭。';
    $('probe').disabled=!online||busy;$('reconnect').disabled=busy;
    for(const id of ['wledAddress','brightness','autoStart','softeningEnabled','softeningStrength','blackBarsEnabled'])$(id).disabled=busy;
    for(const key of Object.keys(COLOR_FIELDS)){for(const suffix of ['', '-number']){const node=$('color-'+key+suffix);node.disabled=busy;if(document.activeElement!==node&&Number.isFinite(colorValues[key]))node.value=String(colorValues[key]);}}$('resetColor').disabled=busy;
    try{normalizeColor(colorValues);$('colorValidation').textContent='';}catch(error){$('colorValidation').textContent=error.message;}
    renderConditions();
    document.querySelector('.workspace').inert=busy||(initializing&&!initialized);
    $('brightnessOutput').textContent=Number((brightnessValue*100).toFixed(2))+'%';
    $('softeningOutput').textContent=Number((softeningValue*100).toFixed(2))+'%';$('softeningStrength').disabled=busy||!$('softeningEnabled').checked;
    const saveState=$('saveState');saveState.className='save-state '+(saveConflict||saveError||cacheError?'is-error':!token&&current.valid?'is-saved':changed?'is-dirty':'is-saved');
    if(saveConflict)saveState.textContent='设置冲突 · 等待选择';
    else if(saving)saveState.textContent=savePhase==='applying'?'正在应用…':'正在自动保存…';
    else if(!token)saveState.textContent=cacheError?'本地保存未成功 · 可导出配置':current.valid?'已自动保存到此浏览器':'等待补全输入 · 本地演示';
    else if(!online)saveState.textContent=hasEdits?'等待重连 · 本页调整已保留':'电视未连接';
    else if(saveError)saveState.textContent='保存或应用未确认 · 可重试';
    else if(!current.valid||!conditions.ok||!compatible)saveState.textContent='等待补全设置';
    else if(changed)saveState.textContent=addressEditing?'完成地址输入后自动保存':hasEdits?'等待自动保存…':'请连接灯带并完成设置';
    else if(pendingApply)saveState.textContent='已保存 · '+(requested===true?'等待应用':'总开关开启后生效');
    else saveState.textContent=requested===true?'已保存并应用到电视':'已自动保存到电视';
    $('outputLimit').hidden=compatible;$('outputLimit').textContent=compatible?'':'当前控制器有 '+P.getController().ledCount+' 颗灯珠。本测试版最多输出 '+OUTPUT_LIMIT+' 颗；可编辑与导出，暂不能保存到电视或开始同步。';
    $('save').title=!compatible?'超出本测试版 480 颗输出上限':!current.valid?current.error:!conditions.ok?conditions.message:!online?'连接电视服务后可保存':'';
    $('syncSwitch').title=!online?'连接电视后确认同步状态':requested===true||running?'关闭 XH光流；网页配置保持可用':changed?'当前调整正在等待自动保存':!savedDocument?'请先完成灯带配置':'';
    $('runtimeBadge').textContent=!online?'未连接':running?'正在输出':requested===true?'已开启 · 已暂停':requested===false?'已关闭':'状态未知';
    $('runtimeBadge').classList.toggle('is-running',online&&running);renderLivePreview();
  }
  function renderConditions(){
    $('sleepOff').checked=runtimeValues.sleepOff;$('sleepOff').disabled=busy;
    const filter=runtimeValues.sourceFilter;$('sourceOffWhenExcluded').checked=filter.offWhenExcluded;$('sourceOffWhenExcluded').disabled=busy||!filter.enabled;$('sourceFilterEnabled').checked=filter.enabled;$('sourceFilterEnabled').disabled=busy||(!filter.enabled&&!inputsSupported.length);
    const key=stable(inputsSupported),wrap=$('sourceOptions');
    if(key!==sourceOptionsKey){sourceOptionsKey=key;wrap.replaceChildren();inputsSupported.forEach((item,index)=>{const label=document.createElement('label');label.className='source-option';const input=document.createElement('input');input.type='checkbox';input.id='source-option-'+index;input.value=item.id;input.dataset.sourceId=item.id;const text=document.createElement('span');text.textContent=item.label;label.append(input,text);wrap.append(label);input.addEventListener('change',event=>{const allowed=new Set(runtimeValues.sourceFilter.allowed);if(event.target.checked)allowed.add(item.id);else allowed.delete(item.id);runtimeValues.sourceFilter.allowed=[...allowed];render();edited();});});}
    for(const node of wrap.querySelectorAll('input')){node.checked=filter.allowed.includes(node.value);node.disabled=busy||!filter.enabled;}
    $('sourceListHint').textContent=inputsSupported.length?(online?'接口列表来自电视系统实际确认，改动将自动保存。':'上次读取的接口列表；重连后自动保存有效调整。'):'未取得接口列表，暂不能开启筛选；不会猜测或预填端口名称。';
    const validation=conditionReady(runtimeValues,inputsSupported);$('sourceValidation').textContent=validation.ok?'':validation.message;
  }
  function setBusy(value){busy=value;render();}
  function serviceState(connected,message){
    online=connected;if(!connected)cancelAutosave();
    $('serviceMode').textContent=connected?'XH光流 · 局域网控制台':token?'电视未连接':'本地演示';
    $('serviceMessage').textContent=message||'所有连接、排布和同步设置保存在这台电视中。';
    $('reconnect').hidden=connected||!token;
    if(!connected){$('displayCondition').textContent='显示状态未知（电视未连接）';$('inputCondition').textContent='当前输入源未知（电视未连接）';$('runtimeState').textContent='电视服务未连接，同步状态未知；可编辑并导出布局。';$('runtimeMetrics').textContent='';$('backgroundState').textContent='后台保护状态未知';$('backgroundHint').textContent='电视未连接，重新连接后确认守护与前台保护状态。';const protection=document.querySelector('.background-protection');protection.classList.toggle('is-protected',false);protection.classList.toggle('is-limited',false);}
    render();
  }
  function renderLivePreview(){
    const running=!!(lastStatus&&lastStatus.running),now=Date.now(),fresh=!previewError&&previewFresh(previewValue,previewReceivedAt,now,online,running,document.hidden);
    $('liveBadge').textContent=fresh?'实时取色 · 排布预览':'预览已暂停';$('liveBadge').classList.toggle('is-running',fresh);
    $('liveState').textContent=!online?'连接电视并开启同步后显示当前取色；此处可先编辑排布。':document.hidden?'页面在后台，实时预览已暂停。':!running?(requestedEnabled(lastStatus)===true?'同步已开启，当前暂停输出；取色颜色已清空。':'同步已关闭，取色颜色已清空。'):previewError?'实时取色暂不可用：'+previewError:!previewValue||!previewValue.available?'正在等待电视返回当前画面的取色数据。':!fresh?'取色超过 1.5 秒未更新，颜色已清空。':'屏幕内为边缘取色生成的渐变示意，非画面截图；灯带为本页排布预览，未应用颜色校正、输出亮度与柔化。';
    if(previewValue&&previewValue.available){const region=previewValue.activeRegion,percent=value=>Number((value*100).toFixed(1))+'%';$('liveRegion').textContent=(fresh?'当前':'上次')+'有效画面 '+percent(region.right-region.left)+' × '+percent(region.bottom-region.top)+' · 上 '+percent(region.top)+' / 下 '+percent(1-region.bottom)+' / 左 '+percent(region.left)+' / 右 '+percent(1-region.right);$('liveAge').textContent=previewValue.points.length+' 个取色点 · 每边 '+previewValue.points.length/4+' 点 · 数据年龄 '+Math.max(0,Math.round(previewValue.ageMs+now-previewReceivedAt))+' ms';}
    else{$('liveRegion').textContent='有效画面范围尚未提供';$('liveAge').textContent='实时取色尚未更新';}
    P.setLivePreview(fresh?previewValue:null);
  }
  async function refreshPreview(){
    renderLivePreview();
    if(!token||!online||busy||initializing||previewBusy||document.hidden||!lastStatus||!lastStatus.running)return;
    previewBusy=true;
    try{previewValue=normalizePreview(await api('/api/preview'));previewReceivedAt=Date.now();previewError='';}
    catch(error){previewError=error.message;}
    finally{previewBusy=false;renderLivePreview();}
  }
  async function api(path,method='GET',body,extraHeaders={}){
    if(!token)throw new Error('缺少电视访问令牌，请从电视显示的配置地址进入。');
    if(method==='POST'&&body===undefined)body={};
    const abort=new AbortController(),timer=setTimeout(()=>abort.abort(),10000);
    try{
      const headers={'X-TVWLED-Token':token,...extraHeaders};if(body!==undefined)headers['Content-Type']='application/json';
      const response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:abort.signal,cache:'no-store',credentials:'same-origin'});
      const text=await response.text();let data;
      try{data=text?JSON.parse(text):null;}catch(error){throw new Error('电视返回了无法识别的数据。');}
      if(!response.ok||data&&data.ok===false){const error=new Error(data&&(data.error||data.message)||('电视服务返回 '+response.status));error.status=response.status;throw error;}
      return data;
    }catch(error){if(error.name==='AbortError')throw new Error('电视响应超时，请确认仍在同一局域网。');throw error;}
    finally{clearTimeout(timer);}
  }
  function applyDocument(value){
    const normalized=normalize(value,M);
    // Validate the complete document before mutating any visible control.
    suppressEdits++;try{P.setController(normalized.controller);P.setConfig(normalized.layout);}finally{suppressEdits--;}
    $('wledAddress').value=normalized.controller.address;brightnessValue=normalized.output.brightness;$('brightness').value=String(brightnessValue*100);$('autoStart').checked=normalized.preferences.autoStart;
    outputExtras=M.clone(normalized.output);preferenceExtras=M.clone(normalized.preferences);softeningValue=normalized.output.softening.strength;$('softeningEnabled').checked=normalized.output.softening.enabled;$('softeningStrength').value=String(softeningValue*100);$('blackBarsEnabled').checked=normalized.output.blackBars.enabled;colorValues=M.clone(normalized.output.color);runtimeValues=M.clone(normalized.runtime);
    observedSignature=stable(normalized);return normalized;
  }
  // A configuration and its conditional-write fingerprint must come from one snapshot.
  // Never attach the fingerprint from a later status response to an earlier document.
  async function editorSnapshot(status){
    let before=status||await api('/api/status');
    if(before.supportsEditorSnapshot===true){
      const snapshot=await api('/api/editor');
      if(!snapshot||!Object.prototype.hasOwnProperty.call(snapshot,'configuration')||typeof snapshot.savedConfigFingerprint!=='string')throw new Error('电视返回的编辑配置快照无效，请更新应用后重试。');
      const value=snapshot.configuration===null?null:normalize(snapshot.configuration,M),fingerprint=snapshot.savedConfigFingerprint;
      if(value?!/^[a-f0-9]{64}$/.test(fingerprint):fingerprint!=='')throw new Error('电视配置快照缺少对应指纹，已暂停自动保存。');
      return {value,fingerprint};
    }
    for(let attempt=0;attempt<3;attempt++){
      const value=await api('/api/config'),after=await api('/api/status');
      const first=before.savedConfigFingerprint||'',second=after.savedConfigFingerprint||'';
      if(first===second)return {value:value?normalize(value,M):null,fingerprint:second};
      before=after;
    }
    throw new Error('电视设置正在被其他页面修改，暂无法取得一致配置；自动保存已暂停，请稍后重试。');
  }
  function baseline(snapshot){
    savedDocument=snapshot.value;savedSignature=savedDocument?stable(savedDocument):null;knownFingerprint=snapshot.fingerprint;hasBaseline=true;
  }
  function runtime(status){
    if(!status||typeof status.running!=='boolean')throw new Error('电视返回的运行状态无效。');
    if((!lastStatus||!lastStatus.running)&&status.running){previewValue=null;previewReceivedAt=0;previewError='';}
    lastStatus=status;if(typeof status.pendingApply==='boolean')pendingApply=status.pendingApply;
    inputsSupported=confirmedInputs(status.inputsSupported);
    const displayLabels={on:'亮屏',off:'熄屏',doze:'低功耗显示',doze_suspend:'低功耗休眠',on_suspend:'显示挂起',vr:'VR 显示',unknown:'未知'},displayLabel=displayLabels[status.displayState]||'未知';
    $('displayCondition').textContent='显示：'+displayLabel+(typeof status.displayInteractive==='boolean'?' · '+(status.displayInteractive?'可交互':'不可交互'):'');
    const source=status.inputSource;$('inputCondition').textContent=source&&source.known===true&&typeof source.id==='string'?'当前输入：'+(typeof source.label==='string'&&source.label.trim()?source.label:source.id):'当前输入源未知（尚未确认）';
    const state=String(status.state||'');
    const labels={idle:'待开始',starting:'正在启动',running:'正在采集画面并发送灯光',stopped:'已停止同步',error:'同步发生错误'};
    let text=status.running?(labels[state]||'正在采集画面并发送灯光'):(labels[state]||state||'已停止同步');
    if(status.enabled===true&&!status.running){const reason=String(status.suspendedReason||'');text=SUSPENSIONS[reason]||(reason?'同步已开启，暂停原因：'+reason:'同步已开启，正在等待输出。');}else if(status.enabled===false&&!status.running)text='同步已关闭。';
    if(status.error)text+=' · '+status.error;
    if(status.connection)text+=' · '+status.connection;
    $('runtimeState').textContent=text;
    const metrics=[];
    if(status.running){if(Number.isFinite(status.captureMs))metrics.push('采集 '+Number(status.captureMs).toFixed(0)+' ms');if(Number.isFinite(status.samplePoints))metrics.push(status.samplePoints+' 个取色点');}
    $('runtimeMetrics').textContent=metrics.join(' · ');
    const protection=document.querySelector('.background-protection'),mode=status.keepAliveMode,revival=status.revivalConnected===true,guard=status.guardConnected===true,foreground=status.foregroundRegistered===true;
    protection.classList.toggle('is-protected',mode==='revival'&&revival||mode==='guard'&&guard||mode==='foreground'&&foreground);protection.classList.toggle('is-limited',mode==='limited');
    $('backgroundState').textContent=mode==='revival'?(revival?'系统恢复保护已连接':'系统恢复保护尚未连接'):mode==='guard'?(guard?'辅助守护已连接':'辅助守护尚未连接'):mode==='foreground'?(foreground?'前台保护已注册':'前台保护尚未确认'):mode==='limited'?'后台保护受限':'后台保护状态待确认';
    const details=(typeof status.revivalConnected==='boolean'?(revival?'系统恢复已连接':status.revivalAuthorized===true?'系统恢复已授权，尚未连接':'系统恢复未授权')+' · ':'')+(typeof status.guardConnected==='boolean'?(guard?'辅助守护已连接':'辅助守护未连接'):'守护状态未提供')+' · '+(typeof status.foregroundRegistered==='boolean'?(foreground?'前台已注册':'前台未注册'):'前台状态未提供');
    $('backgroundHint').textContent=mode==='revival'&&revival?(status.autoStart===false?'自动恢复已关闭，仅保护本次手动运行。 ':'系统重新绑定后恢复上次状态；总开关关闭时仅恢复网页后台。 ')+details:mode==='limited'?'请在电视 App 中打开“管理后台守护”，启用系统恢复保护。 '+details:details;
    render();
  }
  async function refresh(){
    if(!token||polling||initializing||busy||saving||document.hidden)return;
    if(!online){await initialize();return;}
    polling=true;
    try{const status=await api('/api/status');runtime(status);}
    catch(error){serviceState(false,'电视服务暂时不可用：'+error.message+' 有效调整已保留，重连后继续保存。');}
    finally{polling=false;}
  }
  async function initialize(){
    if(initializing)return;
    if(!token){const cached=cachedDraft();if(cached){try{applyDocument(cached.document);}catch(error){}}initialized=true;observedSignature=inspection().signature;rememberDraft();serviceState(false,'当前为本地演示，配置自动保存在此浏览器；从电视的专属地址进入后可自动保存到电视。');return;}
    const preserveDraft=hasBaseline||hasEdits,previousSignature=savedSignature,wasDirty=hasEdits&&dirty();initializing=true;cancelAutosave();render();$('serviceMode').textContent='正在连接电视';
    try{
      const status=await api('/api/status'),snapshot=await editorSnapshot(status);
      const remote=snapshot.value,remoteSignature=remote?stable(remote):null;
      const cached=!preserveDraft?cachedDraft():null;
      if(!preserveDraft){
        baseline(snapshot);verifiedAddress=remote?remote.controller.address:'';
        if(remote)applyDocument(remote);
        if(cached){
          try{const restored=normalize(cached.document,M);if(stable(restored)!==remoteSignature){applyDocument(restored);hasEdits=true;editRevision++;if(cached.baseSignature!==remoteSignature){saveConflict=true;conflictRemote={value:remote,fingerprint:knownFingerprint};}}}catch(error){}
        }
      }else if(!wasDirty){
        baseline(snapshot);verifiedAddress=remote?remote.controller.address:'';if(remote)applyDocument(remote);
      }else if(previousSignature===remoteSignature||(uncertainWrite&&uncertainWrite.signature===remoteSignature)){
        baseline(snapshot);uncertainWrite=null;
      }else{saveConflict=true;conflictRemote=snapshot;}
      if(!remote)pendingApply=false;
      saveError='';serviceState(true);runtime(status);
      feedback(saveConflict?'电视设置已经改变，本页调整已保留；请选择要使用的设置。':preserveDraft?'已重新连接，保留本页调整并继续自动保存。':remote?'已读取电视设置，后续调整将自动保存。':'请连接 WLED 并校准排布，完成后自动保存。');
    }catch(error){serviceState(false,'无法连接电视：'+error.message+' 有效调整保留在此浏览器，稍后自动重连。');}
    finally{initialized=true;initializing=false;observedSignature=inspection().signature;render();rememberDraft();scheduleAutosave();}
  }
  async function confirmApplied(fingerprint){
    if(typeof fingerprint!=='string'||!fingerprint)throw new Error('电视未提供已保存配置的指纹，暂无法确认应用结果。');
    for(let attempt=0;attempt<8;attempt++){
      const status=await api('/api/status');runtime(status);
      if(status.savedConfigFingerprint!==fingerprint)throw new Error('电视保存的配置已经改变，请重新读取并核对。');
      if(status.enabled===false)throw new Error('同步已关闭，已保存的设置将在下次开启时生效。');
      if(status.enabled===true&&status.appliedConfigFingerprint===fingerprint&&status.pendingApply===false)return status;
      if(attempt<7)await new Promise(resolve=>setTimeout(resolve,400));
    }
    throw new Error('电视尚未确认使用新配置；设置已保存，请稍后查看或手动应用。');
  }
  async function save(){
    cancelAutosave();
    let current=inspection();if(!online||busy||saving||saveConflict||!current.valid||!conditionReady(runtimeValues,inputsSupported).ok||P.getController().ledCount>OUTPUT_LIMIT)return;
    if(current.signature===savedSignature&&!(pendingApply&&requestedEnabled(lastStatus)===true)){saveError='';render();return;}
    const revision=editRevision;saving=true;savePhase='saving';saveError='';render();let wrote=false;
    try{
      const before=await api('/api/status');runtime(before);
      if((before.savedConfigFingerprint||'')!==knownFingerprint){
        const snapshot=await editorSnapshot(before),signature=snapshot.value?stable(snapshot.value):null;
        if(uncertainWrite&&signature===uncertainWrite.signature){baseline(snapshot);uncertainWrite=null;}
        else{await conflict('电视设置已被其他页面修改，本页调整没有覆盖它。',snapshot);return;}
      }
      if(current.value.controller.address!==verifiedAddress){
        const target=current.value.controller.address,result=await api('/api/probe','POST',{address:target});
        const device=controller({address:target,name:result.name||'WLED',ledCount:result.ledCount});
        if(editRevision!==revision||inspection().signature!==current.signature)return;
        suppressEdits++;try{P.setController(device);$('wledAddress').value=device.address;}finally{suppressEdits--;}
        verifiedAddress=target;current=inspection();observedSignature=current.signature;
        $('probeResult').textContent=device.name+' · '+device.ledCount+' 颗灯珠'+(result.version?' · '+result.version:'');
        if(!current.valid||device.ledCount>OUTPUT_LIMIT){feedback('已读取新控制器。请按实际灯珠数量校准排布；完成后自动保存。');return;}
      }
      if(current.signature!==savedSignature){
        uncertainWrite={signature:current.signature,value:current.value};
        const submitted=await api('/api/config','POST',current.value,before.supportsConditionalSave===true?{'If-Match':knownFingerprint||'none'}:{});
        const snapshot=await editorSnapshot(),persisted=snapshot.value;
        if(!persisted||stable(persisted)!==current.signature||submitted&&submitted.savedConfigFingerprint&&submitted.savedConfigFingerprint!==snapshot.fingerprint){await conflict('提交后的回读与本页设置不一致，自动保存已暂停。',snapshot);return;}
        baseline(snapshot);pendingApply=true;wrote=true;uncertainWrite=null;
      }
      const status=await api('/api/status');runtime(status);
      if((status.savedConfigFingerprint||'')!==knownFingerprint){await conflict('本次保存后电视设置又被其他页面改变，本页调整已保留。');return;}
      if(status.enabled===true&&editRevision===revision&&inspection().signature===savedSignature&&pendingApply){
        if(status.supportsSafeApply===true){
          savePhase='applying';render();const applied=await api('/api/apply','POST',{}, {'If-Match':knownFingerprint});
          if(applied.requested!==false){
            try{await confirmApplied(knownFingerprint);}catch(error){
              const latest=await api('/api/status');runtime(latest);if(latest.enabled!==false)throw error;
            }
          }else runtime(await api('/api/status'));
        }else{saveError='已保存。电视版本暂不支持安全自动应用，请更新应用或手动应用已保存的设置。';}
      }
      if(!saveError)feedback(requestedEnabled(lastStatus)===true?(pendingApply?'已自动保存，最新调整将继续应用。':'已自动保存，并确认电视使用新设置。'):'已自动保存到电视，总开关保持关闭。');
    }catch(error){
      if(error.status===409){await conflict('电视设置已改变，自动保存已暂停。本页调整已保留。');}
      else{saveError=(wrote?'设置已保存，应用未确认：':'自动保存未确认：')+error.message;feedback(saveError);if(!error.status)serviceState(false,'连接暂不可用，有效调整已保留，重连后继续保存。');}
    }finally{saving=false;savePhase='';render();rememberDraft();if(editRevision!==revision&&!saveConflict){saveError='';scheduleAutosave();}}
  }
  async function start(){
    if(!online||busy||saving||!savedDocument||dirty()||!conditionReady(runtimeValues,inputsSupported).ok||P.getController().ledCount>OUTPUT_LIMIT)return;
    command=requestedEnabled(lastStatus)===true?'applying':'starting';setBusy(true);
    try{
      const before=await api('/api/status'),snapshot=await editorSnapshot(before);runtime(before);
      if(snapshot.fingerprint!==knownFingerprint||(snapshot.value?stable(snapshot.value):null)!==savedSignature){await conflict('电视设置已在其他页面改变，请先核对设置，再开启 XH光流。',snapshot);return;}
      await api('/api/start','POST',{},knownFingerprint?{'If-Match':knownFingerprint}:{});const applied=await confirmApplied(knownFingerprint);
      feedback(applied.running?'已确认电视使用当前保存的配置，正在同步。':'已应用当前保存的配置；条件不满足时暂停输出，请查看当前状态。');
    }catch(error){if(error.status===409)await conflict('电视设置已在其他页面改变，请先核对设置，再开启 XH光流。');else feedback('开启或应用未确认：'+error.message);}
    finally{command='';setBusy(false);scheduleAutosave();}
  }
  async function stop(){
    if(!online||busy)return;
    command='stopping';setBusy(true);
    try{await api('/api/stop','POST');runtime(await api('/api/status'));feedback('已请求停止同步；WLED 实时输入超时后恢复原状态。');}
    catch(error){feedback('停止未确认：'+error.message);}
    finally{command='';setBusy(false);scheduleAutosave();}
  }
  async function probe(){
    if(!online||busy||saving)return;
    addressEditing=false;cancelAutosave();
    let target;try{target=address($('wledAddress').value);}catch(error){feedback(error.message);return;}
    setBusy(true);$('probeResult').textContent='正在检测 WLED…';
    try{
      const result=await api('/api/probe','POST',{address:target});
      const previous=P.getController().ledCount,next=controller({address:target,name:result.name||'WLED',ledCount:result.ledCount});
      suppressEdits++;try{P.setController(next);$('wledAddress').value=next.address;}finally{suppressEdits--;}verifiedAddress=next.address;
      $('probeResult').textContent=next.name+' · '+next.ledCount+' 颗灯珠'+(result.version?' · '+result.version:'');
      feedback(previous!==next.ledCount?'已读取新的灯珠总数，逐边数量保持原值。请重新校准，或点击“按轮廓分配”。':'WLED 连接正常，后续有效调整将自动保存到电视。');
    }catch(error){$('probeResult').textContent='检测未成功：'+error.message;feedback('检测不会改变实际灯光，请核对 WLED 地址。');}
    finally{setBusy(false);edited();}
  }
  function exportFile(){
    try{
      const value=draft(),blob=new Blob([JSON.stringify(value,null,2)],{type:'application/json'}),url=URL.createObjectURL(blob),link=document.createElement('a');
      link.href=url;link.download='tv-wled-config.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
      feedback('已导出完整连接与布局配置。导出不会保存或应用到电视。');
    }catch(error){feedback('无法导出：'+error.message);}
  }
  $('wledAddress').addEventListener('focus',()=>{addressEditing=true;cancelAutosave();});
  $('wledAddress').addEventListener('input',()=>{addressEditing=true;suppressEdits++;try{P.setController({address:$('wledAddress').value.trim()});}finally{suppressEdits--;}$('probeResult').textContent='完成地址输入后自动检测并保存。';edited();});
  function finishAddress(){addressEditing=false;edited();scheduleAutosave();}
  $('wledAddress').addEventListener('blur',finishAddress);$('wledAddress').addEventListener('change',finishAddress);$('wledAddress').addEventListener('keydown',event=>{if(event.key==='Enter'){finishAddress();}});
  $('brightness').addEventListener('input',()=>{brightnessValue=$('brightness').value===''?NaN:Number($('brightness').value)/100;edited();});$('autoStart').addEventListener('change',edited);$('softeningEnabled').addEventListener('change',edited);$('blackBarsEnabled').addEventListener('change',edited);$('softeningStrength').addEventListener('input',()=>{softeningValue=Number($('softeningStrength').value)/100;edited();});
  for(const key of Object.keys(COLOR_FIELDS))for(const suffix of ['', '-number'])$('color-'+key+suffix).addEventListener('input',event=>{colorValues[key]=event.target.value===''?NaN:Number(event.target.value);edited();});
  $('resetColor').addEventListener('click',()=>{colorValues=colorDefaults();edited();feedback('已重置高级颜色校正，其他设置保持不变；调整将自动保存。');});
  $('sourceOffWhenExcluded').addEventListener('change',event=>{if(!runtimeValues.sourceFilter.enabled){render();return;}runtimeValues.sourceFilter.offWhenExcluded=event.target.checked;edited();});
  $('sleepOff').addEventListener('change',event=>{runtimeValues.sleepOff=event.target.checked;edited();});$('sourceFilterEnabled').addEventListener('change',event=>{if(event.target.checked&&!inputsSupported.length){render();return;}runtimeValues.sourceFilter.enabled=event.target.checked;edited();});
  scope.addEventListener('layoutchange',()=>{render();edited();});
  $('save').addEventListener('click',()=>{saveError='';return save();});$('export').addEventListener('click',exportFile);$('probe').addEventListener('click',probe);$('syncSwitch').addEventListener('click',()=>requestedEnabled(lastStatus)===true||lastStatus&&lastStatus.running?stop():start());$('applyChanges').addEventListener('click',start);$('reconnect').addEventListener('click',initialize);
  $('useRemote').addEventListener('click',async()=>{
    if(busy||saving||!online)return;setBusy(true);
    try{const snapshot=await editorSnapshot();baseline(snapshot);saveConflict=false;conflictRemote=null;saveError='';hasEdits=false;uncertainWrite=null;if(savedDocument){applyDocument(savedDocument);verifiedAddress=savedDocument.controller.address;}runtime(await api('/api/status'));rememberDraft();feedback('已使用电视当前设置。');}
    catch(error){feedback('读取电视设置未成功：'+error.message);}finally{setBusy(false);}
  });
  $('useLocal').addEventListener('click',async()=>{
    if(busy||saving||!online)return;
    try{baseline(await editorSnapshot());saveConflict=false;conflictRemote=null;saveError='';hasEdits=true;await save();}
    catch(error){feedback('暂未覆盖电视设置：'+error.message);}
  });
  $('import').addEventListener('click',()=>$('importFile').click());
  $('importFile').addEventListener('change',async event=>{
    const file=event.target.files[0];event.target.value='';if(!file)return;
    if(file.size>4*1024*1024){feedback('JSON 文件超过 4 MB，请选择本工具导出的配置。');return;}
    try{applyDocument(JSON.parse(await file.text()));observedSignature=null;edited();feedback('已载入完整配置，有效调整将自动保存。');}
    catch(error){feedback('导入失败：'+error.message);}
  });
  document.addEventListener('pointerdown',event=>{const target=event.target;if(target&&target.closest&&(target.closest('#diagram')||target.matches&&target.matches('input[type="range"]'))){gestureActive=true;cancelAutosave();}});
  function finishGesture(){if(gestureActive){gestureActive=false;scheduleAutosave();}}
  document.addEventListener('pointerup',finishGesture);document.addEventListener('pointercancel',finishGesture);
  scope.addEventListener('beforeunload',event=>{if(token&&hasEdits&&dirty()){event.preventDefault();event.returnValue='';}});
  scope.TVConsole={getDocument:draft,isDirty:dirty,refresh,initialize,refreshPreview,save,getSaveState:()=>({saving,pending:saveTimer!==null,conflict:saveConflict,error:saveError,revision:editRevision})};
  document.addEventListener('visibilitychange',()=>{rememberDraft();renderLivePreview();if(!document.hidden){refresh();refreshPreview();scheduleAutosave();}});scope.addEventListener('pagehide',rememberDraft);
  initialize();if(token){setInterval(refresh,2000);setInterval(refreshPreview,500);}renderLivePreview();
})(typeof globalThis!=='undefined'?globalThis:window);
