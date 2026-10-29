(function(){
  'use strict';
  const M=window.LayoutModel;
  let BUDGET=126, controller={address:'192.168.1.50',name:'WLED 示例',ledCount:126};
  const $=id=>document.getElementById(id), NS='http://www.w3.org/2000/svg';
  const basis={top:'从左向右',right:'从上向下',bottom:'从右向左',left:'从下向上'};
  let config=M.defaults(), shapeSnapshots={},resetUndo=null,geometryTarget='screen',livePreview=null;
  const CANVAS={width:900,height:527.25},SCENE={x:24,y:24,w:852,h:479.25};
  // SVG geometry uses matching rx values for shapes, clips and selection outlines.
  // Continuous corners are a CSS-box enhancement; SVG keeps the round fallback.
  const DRAW_RADIUS={screen:8,caption:12,region:4,handle:4};
  function number(value,fallback){return typeof value==='number'&&Number.isFinite(value)?value:fallback;}
  function safeRestore(raw){
    const s=M.defaults();
    if(!raw||typeof raw!=='object')return s;
    if(raw.sampling&&typeof raw.sampling==='object')s.sampling={...s.sampling,...M.clone(raw.sampling)};
    if(raw.geometry&&typeof raw.geometry==='object')s.geometry=M.clone(raw.geometry);
    s.shape=raw.shape==='closed'?'closed':'c';s.direction=raw.direction==='ccw'?'ccw':'cw';s.view=raw.view==='back'?'back':'front';s.offset=number(raw.offset,0);
    for(const key of ['gap','cut'])if(raw[key]){if(M.SIDES.includes(raw[key].edge))s[key].edge=raw[key].edge;for(const p of key==='gap'?['start','end']:['position'])s[key][p]=number(raw[key][p],s[key][p]);}
    for(const edge of M.SIDES)if(raw.edges&&raw.edges[edge]){const e=raw.edges[edge];s.edges[edge]={count:number(e.count,s.edges[edge].count),full:e.full!==false,start:number(e.start,0),end:number(e.end,100)};}
    return s;
  }
  function layoutSignature(value=config){const {sampling,view,...layout}=value;return JSON.stringify(layout);}
  function feedback(text){$('feedback').textContent=text;}
  function value(id,n){if(document.activeElement!==$(id))$(id).value=n;}
  function createSvg(tag,attrs,text){
    attrs={...(attrs||{})};
    if(tag==='text'&&Number.isFinite(attrs.x)&&Number.isFinite(attrs.y)){
      const sizes={'diagram-label':15,'led-label':9,'first-label':15,'cut-label':13,'screen-word':20,'screen-sub':12},size=Number(attrs['font-size'])||sizes[attrs.class]||12,width=Math.min(870,Array.from(String(text||'')).reduce((sum,char)=>sum+size*(char.charCodeAt(0)>255?.95:.57),0)),anchor=attrs['text-anchor']||'start';
      const min=10+(anchor==='middle'?width/2:anchor==='end'?width:0),max=890-(anchor==='middle'?width/2:anchor==='start'?width:0);attrs.x=Math.max(min,Math.min(max,attrs.x));attrs.y=Math.max(size+6,Math.min(CANVAS.height-8,attrs.y));
    }
    const node=document.createElementNS(NS,tag);for(const [key,v]of Object.entries(attrs))node.setAttribute(key,String(v));if(text!==undefined)node.textContent=text;return node;
  }
  function clamp(value,min,max){return Math.max(min,Math.min(max,value));}
  function adjustedRect(rect,corner,dx,dy){
    let left=rect.x,right=rect.x+rect.width,top=rect.y,bottom=rect.y+rect.height;
    if(!corner){left=clamp(rect.x+dx,0,1-rect.width);top=clamp(rect.y+dy,0,1-rect.height);right=left+rect.width;bottom=top+rect.height;}
    else{if(corner.includes('w'))left=clamp(left+dx,0,right-.02);if(corner.includes('e'))right=clamp(right+dx,left+.02,1);if(corner.includes('n'))top=clamp(top+dy,0,bottom-.02);if(corner.includes('s'))bottom=clamp(bottom+dy,top+.02,1);}
    const round=value=>Math.round(value*1000000)/1000000;
    return {x:round(left),y:round(top),width:round(right-left),height:round(bottom-top)};
  }
  function worldPoint(event){
    const svg=$('diagram'),point=svg.createSVGPoint();point.x=event.clientX;point.y=event.clientY;
    const local=point.matrixTransform(svg.getScreenCTM().inverse());let x=(local.x-SCENE.x)/SCENE.w;if(config.view==='back')x=1-x;
    return {x,y:(local.y-SCENE.y)/SCENE.h};
  }
  function makeGeometryInteractive(node,target,corner){
    node.classList.add(corner?'geometry-handle':'geometry-drag');if(corner)node.style.cursor=((corner==='nw'||corner==='se')!==(config.view==='back'))?'nwse-resize':'nesw-resize';node.setAttribute('tabindex','0');node.setAttribute('role','button');
    node.setAttribute('aria-label',(target==='screen'?'屏幕':'灯带')+'方框'+(corner?'尺寸调整角':'，可拖动或用方向键移动'));
    node.addEventListener('pointerdown',event=>{
      if(event.button!==0)return;event.preventDefault();event.stopPropagation();geometryTarget=target;
      const initial=M.clone(M.validGeometry(M.geometry(config))?M.geometry(config):M.defaultGeometry()),start=worldPoint(event),svg=$('diagram'),pointerId=event.pointerId;
      if(svg.setPointerCapture)svg.setPointerCapture(pointerId);
      function move(e){if(e.pointerId!==pointerId)return;e.preventDefault();const current=worldPoint(e);config.geometry=M.clone(initial);config.geometry[target]=adjustedRect(initial[target],corner,current.x-start.x,current.y-start.y);render();}
      function end(e){if(e.pointerId!==pointerId)return;window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',end);window.removeEventListener('pointercancel',end);if(svg.hasPointerCapture&&svg.hasPointerCapture(pointerId))svg.releasePointerCapture(pointerId);feedback('已调整'+(target==='screen'?'屏幕':'灯带')+'方框；灯号顺序保持不变。停止调整后会自动保存。');}
      window.addEventListener('pointermove',move,{passive:false});window.addEventListener('pointerup',end);window.addEventListener('pointercancel',end);render();
    });
    node.addEventListener('keydown',event=>{
      const deltas={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]},delta=deltas[event.key];if(!delta)return;event.preventDefault();geometryTarget=target;
      const step=event.shiftKey?.01:.002,dx=delta[0]*step*(config.view==='back'?-1:1),dy=delta[1]*step;
      config.geometry=M.clone(M.geometry(config));config.geometry[target]=adjustedRect(config.geometry[target],corner,dx,dy);render();
    });
  }
  function draw(result){
    const svg=$('diagram');while(svg.children.length>2)svg.lastChild.remove();
    const defs=createSvg('defs'),marker=createSvg('marker',{id:'arrow',viewBox:'0 0 10 10',refX:8,refY:5,markerWidth:5,markerHeight:5,orient:'auto-start-reverse'});marker.append(createSvg('path',{d:'M0 0L10 5L0 10',fill:'none',stroke:'var(--secondary)','stroke-width':1.8}));defs.append(marker);svg.append(defs);
    const geometry=M.validGeometry(M.geometry(config))?M.geometry(config):M.defaultGeometry(),screen=geometry.screen,strip=geometry.strip;
    function scene(p){return {x:SCENE.x+(config.view==='back'?1-p.x:p.x)*SCENE.w,y:SCENE.y+p.y*SCENE.h};}
    function xy(p){return scene({x:strip.x+p.x*strip.width,y:strip.y+p.y*strip.height});}
    function rect(frame){return {x:SCENE.x+(config.view==='back'?1-frame.x-frame.width:frame.x)*SCENE.w,y:SCENE.y+frame.y*SCENE.h,w:frame.width*SCENE.w,h:frame.height*SCENE.h};}
    const a=rect(screen),b=rect(strip);
    const screenBody=createSvg('rect',{class:'tv-body',x:a.x,y:a.y,width:a.w,height:a.h,rx:DRAW_RADIUS.screen});makeGeometryInteractive(screenBody,'screen');svg.append(screenBody);
    svg.append(createSvg('line',{x1:a.x+a.w/2,y1:a.y+8,x2:a.x+a.w/2,y2:a.y+a.h-8,stroke:'var(--border)','stroke-dasharray':'3 7','pointer-events':'none'}));
    svg.append(createSvg('line',{x1:a.x+8,y1:a.y+a.h/2,x2:a.x+a.w-8,y2:a.y+a.h/2,stroke:'var(--border)','stroke-dasharray':'3 7','pointer-events':'none'}));
    if(livePreview){
      const clip=createSvg('clipPath',{id:'screen-field-clip'});clip.append(createSvg('rect',{x:a.x,y:a.y,width:a.w,height:a.h,rx:DRAW_RADIUS.screen}));defs.append(clip);
      const filter=createSvg('filter',{id:'screen-field-blur',x:'-10%',y:'-10%',width:'120%',height:'120%','color-interpolation-filters':'sRGB'});filter.append(createSvg('feGaussianBlur',{stdDeviation:Math.max(2,Math.min(a.w/14,a.h/9)*.42)}));defs.append(filter);
      const clipGroup=createSvg('g',{'clip-path':'url(#screen-field-clip)','pointer-events':'none'}),mesh=createSvg('g',{filter:'url(#screen-field-blur)',opacity:.8,'pointer-events':'none'}),columns=14,rows=9;
      for(let row=0;row<rows;row++)for(let col=0;col<columns;col++){const color=M.previewField(livePreview.points,(col+.5)/columns,(row+.5)/rows);if(!color)continue;const cell=rect({x:screen.x+col/columns*screen.width,y:screen.y+row/rows*screen.height,width:screen.width/columns,height:screen.height/rows});mesh.append(createSvg('rect',{x:cell.x-1,y:cell.y-1,width:cell.w+2,height:cell.h+2,fill:color,'pointer-events':'none'}));}clipGroup.append(mesh);svg.append(clipGroup);
    }
    if(a.w>110&&a.h>80){
      if(livePreview)svg.append(createSvg('rect',{x:a.x+a.w/2-Math.min(232,a.w-16)/2,y:a.y+a.h/2-36,width:Math.min(232,a.w-16),height:72,rx:DRAW_RADIUS.caption,fill:'var(--surface)',opacity:.82,'pointer-events':'none'}));
      svg.append(createSvg('text',{class:'screen-word',x:a.x+a.w/2,y:a.y+a.h/2-8,'text-anchor':'middle','font-size':Math.min(20,a.w/12),'pointer-events':'none'},config.view==='back'?'屏幕背面':'屏幕正面'));
      svg.append(createSvg('text',{class:'screen-sub',x:a.x+a.w/2,y:a.y+a.h/2+16,'text-anchor':'middle','font-size':Math.min(12,a.w/20),'pointer-events':'none'},livePreview?'边缘渐变示意 · 非画面截图':'拖动方框 · 拉角调整尺寸'));
    }
    const rawInset=M.sampling(config),inset=Number.isFinite(rawInset)?clamp(rawInset,0,.2):.045;
    svg.append(createSvg('rect',{class:'sampling-line',x:a.x+a.w*inset,y:a.y+a.h*inset,width:a.w*(1-2*inset),height:a.h*(1-2*inset),rx:DRAW_RADIUS.region,fill:'none',stroke:'#6caaa8','stroke-width':1.5,'stroke-dasharray':'3 5','pointer-events':'none'}));
    if(livePreview){
      const active=livePreview.activeRegion,activeWorld={x:screen.x+active.left*screen.width,y:screen.y+active.top*screen.height,width:(active.right-active.left)*screen.width,height:(active.bottom-active.top)*screen.height},r=rect(activeWorld);
      svg.append(createSvg('rect',{x:r.x,y:r.y,width:r.w,height:r.h,rx:DRAW_RADIUS.region,fill:'none',stroke:'var(--brand-rose)','stroke-width':1,'stroke-dasharray':'7 4',opacity:.65,'pointer-events':'none'}));
      const edgeEnds={top:[{x:active.left,y:active.top},{x:active.right,y:active.top}],right:[{x:active.right,y:active.top},{x:active.right,y:active.bottom}],bottom:[{x:active.right,y:active.bottom},{x:active.left,y:active.bottom}],left:[{x:active.left,y:active.bottom},{x:active.left,y:active.top}]};
      for(const edge of M.SIDES){const values=livePreview.points.filter(point=>point.edge===edge).sort((a,b)=>a.position-b.position);if(!values.length)continue;
        const ends=edgeEnds[edge].map(p=>scene({x:screen.x+p.x*screen.width,y:screen.y+p.y*screen.height})),id='screen-live-'+edge,gradient=createSvg('linearGradient',{id,gradientUnits:'userSpaceOnUse',x1:ends[0].x,y1:ends[0].y,x2:ends[1].x,y2:ends[1].y});
        gradient.append(createSvg('stop',{offset:0,'stop-color':values[0].color}));for(const point of values)gradient.append(createSvg('stop',{offset:point.position,'stop-color':point.color}));gradient.append(createSvg('stop',{offset:1,'stop-color':values[values.length-1].color}));defs.append(gradient);
        svg.append(createSvg('line',{x1:ends[0].x,y1:ends[0].y,x2:ends[1].x,y2:ends[1].y,stroke:'url(#'+id+')','stroke-width':5,'stroke-linecap':'round','pointer-events':'none'}));
      }
      for(const point of livePreview.points){const p=scene({x:screen.x+point.x*screen.width,y:screen.y+point.y*screen.height});svg.append(createSvg('circle',{cx:p.x,cy:p.y,r:4.2,fill:point.color,stroke:'var(--surface)','stroke-width':1.2,'pointer-events':'none'}));}
    }
    const wire=createSvg('rect' ,{class:'wire geometry-strip',x:b.x,y:b.y,width:b.w,height:b.h,rx:DRAW_RADIUS.region});svg.append(wire);
    const stripHit=createSvg('rect',{x:b.x,y:b.y,width:b.w,height:b.h,rx:DRAW_RADIUS.region,fill:geometryTarget==='strip'?'transparent':'none',stroke:'transparent','stroke-width':18});makeGeometryInteractive(stripHit,'strip');svg.append(stripHit);
    const labelLocations={top:[b.x+b.w/2,b.y<60?b.y+30:b.y-24],right:[b.x+b.w>830?b.x+b.w-45:b.x+b.w+47,b.y+b.h/2],bottom:[b.x+b.w/2,b.y+b.h>CANVAS.height-60?b.y+b.h-25:b.y+b.h+39],left:[b.x<70?b.x+45:b.x-47,b.y+b.h/2]};
    for(const edge of M.SIDES){const displayEdge=config.view==='back'?(edge==='left'?'right':edge==='right'?'left':edge):edge,p=labelLocations[displayEdge];svg.append(createSvg('text',{class:'diagram-label',x:p[0],y:p[1],'text-anchor':'middle','pointer-events':'none'},M.NAMES[edge]+' '+config.edges[edge].count+' 颗'));}
    const palette={top:'var(--border)',right:'var(--border)',bottom:'var(--border)',left:'var(--border)'};
    for(const edge of M.SIDES)for(const [start,end]of M.intervals(config,edge,false)){const p=xy(M.point(edge,start)),q=xy(M.point(edge,end));svg.append(createSvg('line',{x1:p.x,y1:p.y,x2:q.x,y2:q.y,stroke:palette[edge],'stroke-width':3,'stroke-opacity':.45,'pointer-events':'none'}));}
    if(config.shape==='c'&&config.gap.end>config.gap.start){const p=xy(M.point(config.gap.edge,(config.gap.start+config.gap.end)/200)),outside={top:[0,-48],right:[37,28],bottom:[0,61],left:[-37,28]};const edge=config.view==='back'?(config.gap.edge==='right'?'left':config.gap.edge==='left'?'right':config.gap.edge):config.gap.edge;svg.append(createSvg('text',{class:'cut-label',x:p.x+outside[edge][0],y:p.y+(edge==='top'&&p.y<75?49:edge==='bottom'&&p.y>CANVAS.height-80?-49:outside[edge][1]),'text-anchor':'middle','pointer-events':'none'},'缺口'));}
    const step=result.leds.length>80?10:result.leds.length>40?5:1;
    result.leds.forEach((led,index)=>{
      const p=xy(led.position),group=createSvg('g');group.append(createSvg('title',{},'第 '+led.order+' 颗 · WLED '+led.physicalIndex+' · '+M.NAMES[led.edge]+' '+Math.round(led.u*100)+'%'));
      group.append(createSvg('circle',{class:'led-point',cx:p.x,cy:p.y,r:index===0?5.5:3.8,fill:livePreview?(M.interpolateEdge(livePreview.points,led.edge,led.edge==='top'?led.sample.x:led.edge==='right'?led.sample.y:led.edge==='bottom'?1-led.sample.x:1-led.sample.y)||'var(--muted)'):'var(--muted)'}));if(index===0)group.append(createSvg('circle',{class:'led-ring',cx:p.x,cy:p.y,r:10}));
      const isEnd=index===result.leds.length-1;
      if(index===0||isEnd||led.order%step===0){const displayEdge=config.view==='back'?(led.edge==='left'?'right':led.edge==='right'?'left':led.edge):led.edge,delta={top:[0,-13],right:[18,0],bottom:[0,18],left:[-18,0]}[displayEdge];group.append(createSvg('text',{class:'led-label',x:p.x+delta[0],y:p.y+delta[1]+3,'text-anchor':'middle','pointer-events':'none'},String(led.order)));}
      if(config.shape==='closed'||index===0||isEnd){
        const hit=createSvg('circle',{class:'led-hit',cx:p.x,cy:p.y,r:10,tabindex:0,role:'button','aria-label':config.shape==='closed'?'将'+M.NAMES[led.edge]+'第'+led.order+'颗设为首灯':index===0?'当前首灯端点':'将另一开口端点设为首灯'});
        const select=()=>{if(config.shape==='closed'){config.cut={edge:led.edge,position:led.u*100};render();feedback('首灯已设为'+M.NAMES[led.edge]+' '+Math.round(led.u*100)+'% 处。');}else if(isEnd&&index!==0){config.direction=config.direction==='cw'?'ccw':'cw';render();feedback('已从另一开口端点开始走线。');}};
        hit.addEventListener('click',select);hit.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select();}});group.append(hit);
      }
      svg.append(group);
    });
    if(result.leds.length){const first=result.leds[0],p=xy(first.position),edge=config.view==='back'?(first.edge==='right'?'left':first.edge==='left'?'right':first.edge):first.edge,delta={top:[20,32],right:[32,-16],bottom:[-10,43],left:[-32,36]}[edge];svg.append(createSvg('line',{class:'start-tail',x1:p.x,y1:p.y,x2:p.x+delta[0]*.65,y2:p.y+delta[1]*.6}));svg.append(createSvg('text',{class:'first-label',x:p.x+delta[0],y:p.y+delta[1],'text-anchor':edge==='right'?'start':edge==='left'?'end':'middle','pointer-events':'none'},'首灯 · WLED '+first.physicalIndex));const path=result.leds.slice(0,Math.min(7,result.leds.length)).map((led,i)=>{const p=xy(led.position);return(i?'L':'M')+p.x+','+p.y;}).join(' ');svg.append(createSvg('path',{class:'direction-arrow',d:path,'marker-end':'url(#arrow)','pointer-events':'none'}));}
    const selected=geometry[geometryTarget],selectedRect=rect(selected);svg.append(createSvg('rect',{class:'geometry-selection',x:selectedRect.x,y:selectedRect.y,width:selectedRect.w,height:selectedRect.h,rx:geometryTarget==='screen'?DRAW_RADIUS.screen:DRAW_RADIUS.region,fill:'none',stroke:'var(--accent)','stroke-width':1.5,'stroke-dasharray':'5 4','pointer-events':'none'}));
    for(const [corner,x,y]of [['nw',selected.x,selected.y],['ne',selected.x+selected.width,selected.y],['sw',selected.x,selected.y+selected.height],['se',selected.x+selected.width,selected.y+selected.height]]){const p=scene({x,y}),handle=createSvg('rect',{x:p.x-6,y:p.y-6,width:12,height:12,rx:DRAW_RADIUS.handle,fill:'var(--surface)',stroke:'var(--accent)','stroke-width':2});makeGeometryInteractive(handle,geometryTarget,corner);svg.append(handle);}
    svg.append(createSvg('text',{x:selectedRect.x+12,y:selectedRect.y+18,'text-anchor':'start',fill:'var(--accent)','font-size':11,'pointer-events':'none'},(geometryTarget==='screen'?'屏幕':'灯带')+'方框 · 已选中'));
  }
  function buildEdgeControls(){
    const wrap=$('edgeControls');
    for(const edge of M.SIDES){const node=document.createElement('div');node.className='edge-row';node.innerHTML='<div class="edge-row-head"><label class="edge-name" for="count-'+edge+'">'+M.NAMES[edge]+'<small>'+basis[edge]+'</small></label><input id="count-'+edge+'" type="number" min="0" max="126" step="1" aria-label="'+M.NAMES[edge]+'灯珠数"></div><label class="check-label"><input id="full-'+edge+'" type="checkbox">本边覆盖完整</label><div class="pair" id="range-'+edge+'"><div class="field"><label for="start-'+edge+'">覆盖起点 %</label><input id="start-'+edge+'" type="number" min="0" max="99" step="1"></div><div class="field"><label for="end-'+edge+'">覆盖终点 %</label><input id="end-'+edge+'" type="number" min="1" max="100" step="1"></div></div>';wrap.append(node);
      $('count-'+edge).addEventListener('input',e=>{config.edges[edge].count=e.target.value===''?NaN:Number(e.target.value);render();});
      $('full-'+edge).addEventListener('change',e=>{config.edges[edge].full=e.target.checked;render();});
      for(const key of ['start','end'])$(key+'-'+edge).addEventListener('input',e=>{config.edges[edge][key]=e.target.value===''?NaN:Number(e.target.value);render();});
    }
  }
  function render(){
    if(resetUndo&&(layoutSignature()!==resetUndo.resetSignature||BUDGET!==resetUndo.budget))resetUndo=null;
    $('undoLayout').hidden=!resetUndo;
    const c=config.shape==='c', result=M.evaluate(config,BUDGET),g=M.geometry(config);
    const quality=M.samplingQuality(config);$('samplingStandard').setAttribute('aria-pressed',String(quality==='standard'));$('samplingFine').setAttribute('aria-pressed',String(quality==='fine'));$('samplingQualityHint').classList.toggle('is-fine',quality==='fine');
    const inset=M.sampling(config);value('samplingInset',Number.isFinite(inset)?inset*100:'');value('insetNumber',Number.isFinite(inset)?Number((inset*100).toFixed(3)):'');$('insetOutput').textContent=Number.isFinite(inset)?Number((inset*100).toFixed(2))+'%':'—';
    $('selectScreen').setAttribute('aria-pressed',String(geometryTarget==='screen'));$('selectStrip').setAttribute('aria-pressed',String(geometryTarget==='strip'));
    for(const key of ['x','y','width','height'])value('geometry-'+key,Number.isFinite(g[geometryTarget]&&g[geometryTarget][key])?Number((g[geometryTarget][key]*100).toFixed(3)):'');
    $('shapeC').setAttribute('aria-pressed',String(c));$('shapeClosed').setAttribute('aria-pressed',String(!c));
    $('cGapControls').hidden=!c;$('closedCutControls').hidden=c;$('cFirstControls').hidden=!c;
    $('gapEdgeLabel').textContent=c?'缺口在哪条边':'从哪条边断开';value('gapEdge',c?config.gap.edge:config.cut.edge);
    value('gapStart',config.gap.start);value('gapEnd',config.gap.end);value('cutPosition',config.cut.position);$('cutOutput').textContent=Math.round(config.cut.position)+'%';
    value('direction',config.direction);value('firstEndpoint',config.direction);value('offset',config.offset);
    $('edgeBasis').textContent=M.NAMES[c?config.gap.edge:config.cut.edge]+'：'+basis[c?config.gap.edge:config.cut.edge]+'计算百分比。';
    for(const edge of M.SIDES){const e=config.edges[edge];value('count-'+edge,e.count);$('full-'+edge).checked=e.full;value('start-'+edge,e.start);value('end-'+edge,e.end);$('range-'+edge).hidden=e.full;}
    $('frontView').setAttribute('aria-pressed',String(config.view==='front'));$('backView').setAttribute('aria-pressed',String(config.view==='back'));$('previewTitle').textContent=config.view==='front'?'从屏幕正面看':'从屏幕背面看';$('viewHint').textContent=config.view==='front'?'上下左右与画面一致。':'左右已镜像；编号和取色位置不变。';
    $('usedCount').innerHTML=String(result.total)+'<span> / '+BUDGET+'</span>';$('firstSummary').textContent=result.leds.length?M.NAMES[result.leds[0].edge]+' · '+Math.round(result.leds[0].u*100)+'%':'—';$('directionSummary').textContent=config.direction==='cw'?'顺时针':'逆时针';
    const validation=$('validation');validation.replaceChildren();validation.className='validation '+(result.errors.length?'error':result.warnings.length?'warning':'valid');
    const lines=result.errors.length?result.errors:result.warnings.length?result.warnings:[result.total+' 颗灯珠已分配，首尾与走线连续。'];
    for(const line of lines){const p=document.createElement('p');p.textContent=line;validation.append(p);}
    $('save').disabled=!!result.errors.length;$('export').disabled=!!result.errors.length;draw(result);
    window.dispatchEvent(new CustomEvent('layoutchange',{detail:{valid:!result.errors.length}}));
  }
  function chooseShape(shape){if(config.shape===shape)return;shapeSnapshots[config.shape]=M.clone(config);const previous=shapeSnapshots[shape];if(previous){const view=config.view,offset=config.offset,direction=config.direction,geometry=M.clone(M.geometry(config)),sampling=M.clone(config.sampling||M.defaults().sampling);config=M.clone(previous);config.view=view;config.offset=offset;config.direction=direction;config.geometry=geometry;config.sampling=sampling;}else{config.shape=shape;for(const side of M.SIDES)config.edges[side]={count:0,full:true,start:0,end:100};if(shape==='closed'){config.cut={edge:config.gap.edge,position:50};M.autoDistribute(config,BUDGET);}else{config.gap={edge:'bottom',start:0,end:100};M.autoDistribute(config,BUDGET);}}render();feedback('已切换轮廓。请按安装情况校准每条边的灯珠数。');}
  for(const [id,quality]of [['samplingStandard','standard'],['samplingFine','fine']])$(id).addEventListener('click',()=>{config.sampling={...(config.sampling||{}),quality};render();feedback(quality==='fine'?'已选择细腻：40 个取色点，延迟会增大。有效改动会自动保存。':'已选择标准：20 个取色点。有效改动会自动保存。');});
  for(const id of ['samplingInset','insetNumber'])$(id).addEventListener('input',event=>{config.sampling={...(config.sampling||{}),inset:event.target.value===''?NaN:Number(event.target.value)/100};render();});
  for(const target of ['screen','strip'])$(target==='screen'?'selectScreen':'selectStrip').addEventListener('click',()=>{geometryTarget=target;render();});
  for(const key of ['x','y','width','height'])$('geometry-'+key).addEventListener('input',event=>{config.geometry=M.clone(M.geometry(config));config.geometry[geometryTarget][key]=event.target.value===''?NaN:Number(event.target.value)/100;render();});
  $('insetScreen').addEventListener('click',()=>{config.geometry=M.clone(M.geometry(config));const strip=config.geometry.strip;config.geometry.screen={x:strip.x+strip.width*.06,y:strip.y+strip.height*.06,width:strip.width*.88,height:strip.height*.88};geometryTarget='screen';render();feedback('屏幕已在灯带内居中缩小一圈。请按实际安装调整。停止调整后会自动保存。');});
  $('alignFrames').addEventListener('click',()=>{config.geometry=M.clone(M.geometry(config));config.geometry.screen=M.clone(config.geometry.strip);geometryTarget='screen';render();feedback('屏幕与灯带方框已重合，取色投影恢复为按边对应；会自动保存。');});
  buildEdgeControls();
  $('shapeC').addEventListener('click',()=>chooseShape('c'));$('shapeClosed').addEventListener('click',()=>chooseShape('closed'));
  $('gapEdge').addEventListener('change',e=>{if(config.shape==='c'){config.gap.edge=e.target.value;M.autoDistribute(config,BUDGET);feedback('已按新缺口重新分配灯珠，请逐边校准。');}else config.cut.edge=e.target.value;render();});
  for(const prop of ['start','end'])$('gap'+(prop==='start'?'Start':'End')).addEventListener('input',e=>{config.gap[prop]=e.target.value===''?NaN:Number(e.target.value);if(config.gap.start>=0&&config.gap.end<=100&&config.gap.end>config.gap.start)M.autoDistribute(config,BUDGET);render();});
  $('wholeGap').addEventListener('click',()=>{config.gap.start=0;config.gap.end=100;M.autoDistribute(config,BUDGET);render();feedback('已将整条边留空，并重新分配灯珠。');});
  $('cutPosition').addEventListener('input',e=>{config.cut.position=Number(e.target.value);render();});
  for(const id of ['direction','firstEndpoint'])$(id).addEventListener('change',e=>{config.direction=e.target.value;render();});
  $('offset').addEventListener('input',e=>{config.offset=e.target.value===''?NaN:Number(e.target.value);render();});
  $('frontView').addEventListener('click',()=>{config.view='front';render();});$('backView').addEventListener('click',()=>{config.view='back';render();});
  $('distribute').addEventListener('click',()=>{M.autoDistribute(config,BUDGET);render();feedback('已按边长和覆盖范围分配可用灯珠。安装后仍需核对数量。');});
  $('reset').addEventListener('click',()=>{
    const previous={config:M.clone(config),shapeSnapshots:M.clone(shapeSnapshots)};
    const sampling=M.clone(config.sampling||M.defaults().sampling),view=config.view;
    config=M.defaults();config.sampling=sampling;config.view=view;M.autoDistribute(config,BUDGET);shapeSnapshots={};
    resetUndo={...previous,budget:BUDGET,resetSignature:layoutSignature()};render();
    const counts=M.SIDES.map(edge=>M.NAMES[edge]+' '+config.edges[edge].count+' 颗').join('、');
    feedback('已按当前控制器的 '+BUDGET+' 颗灯珠重置为 C 字形：'+counts+'。会自动保存，可撤销本次重置。');
  });
  $('undoLayout').addEventListener('click',()=>{
    if(!resetUndo)return;
    const previous=resetUndo,sampling=M.clone(config.sampling||M.defaults().sampling),view=config.view;resetUndo=null;
    config=M.clone(previous.config);config.sampling=sampling;config.view=view;shapeSnapshots=M.clone(previous.shapeSnapshots);render();
    feedback('已撤销排布重置，恢复之前的位置、走线和灯珠分配；会自动保存。');
  });
  function setController(value){
    const next={...controller,...value};
    if(!Number.isInteger(next.ledCount)||next.ledCount<1||next.ledCount>10000)throw new Error('灯珠总数必须为 1–10000 的整数。');
    controller={address:String(next.address||'').trim(),name:String(next.name||'WLED'),ledCount:next.ledCount};BUDGET=controller.ledCount;
    $('deviceName').textContent=controller.name;$('deviceSummary').textContent=BUDGET+' 颗 RGB 灯珠 · '+(controller.address||'未设置地址');
    $('offset').max=String(BUDGET-1);for(const edge of M.SIDES)$('count-'+edge).max=String(BUDGET);
    render();
  }
  window.LayoutPreview={getConfig:()=>M.clone(config),setConfig:v=>{config=safeRestore(v);shapeSnapshots={};render();},getMapping:()=>{const result=M.exportLayout(config,BUDGET);result.controller=M.clone(controller);return result;},setController,getController:()=>M.clone(controller),setLivePreview:value=>{if(value===livePreview)return;livePreview=value;draw(M.evaluate(config,BUDGET));}};
  const saveDock=document.querySelector('.save-dock');
  if(saveDock&&typeof ResizeObserver==='function'){
    const updateSaveHeight=()=>document.documentElement.style.setProperty('--save-dock-height',Math.ceil(saveDock.getBoundingClientRect().height/4)*4+'px');
    new ResizeObserver(updateSaveHeight).observe(saveDock);updateSaveHeight();
  }
  render();
})();
