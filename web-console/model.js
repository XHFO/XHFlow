(function (scope) {
  'use strict';
  const SIDES = ['top', 'right', 'bottom', 'left'];
  const NAMES = {top:'上边', right:'右边', bottom:'下边', left:'左边'};
  const EPS = 1e-8;
  function defaultGeometry(){return {screen:{x:.14,y:.14,width:.72,height:.72},strip:{x:.14,y:.14,width:.72,height:.72}};}
  function geometry(config){return config.geometry||defaultGeometry();}
  function validGeometry(g){return !!g&&['screen','strip'].every(key=>{const rect=g[key];return rect&&['x','y','width','height'].every(field=>typeof rect[field]==='number'&&Number.isFinite(rect[field]))&&rect.x>=0&&rect.y>=0&&rect.width>=.02&&rect.height>=.02&&rect.x+rect.width<=1+EPS&&rect.y+rect.height<=1+EPS;});}
  function sampling(config){return config.sampling&&config.sampling.inset!==undefined?config.sampling.inset:.045;}
  function samplingQuality(config){return config.sampling&&config.sampling.quality!==undefined?config.sampling.quality:'standard';}
  function defaults() {
    return {version:1, geometry:defaultGeometry(),sampling:{inset:.045,quality:'standard'}, shape:'c', gap:{edge:'bottom',start:0,end:100}, cut:{edge:'bottom',position:50}, direction:'cw', view:'front', offset:0,
      edges:{top:{count:50,full:true,start:0,end:100}, right:{count:38,full:true,start:0,end:100}, bottom:{count:0,full:true,start:0,end:100}, left:{count:38,full:true,start:0,end:100}}};
  }
  function clone(v) { return JSON.parse(JSON.stringify(v)); }
  function intervals(config, edge, ignoreCount) {
    const entry=config.edges[edge];
    if (!ignoreCount && !(entry.count>0)) return [];
    let ranges = [[entry.full?0:entry.start/100, entry.full?1:entry.end/100]];
    if (config.shape==='c' && config.gap.edge===edge) {
      const a=config.gap.start/100, b=config.gap.end/100;
      ranges=ranges.flatMap(([lo,hi])=>[[lo,Math.min(hi,a)],[Math.max(lo,b),hi]]).filter(([lo,hi])=>hi-lo>EPS);
    }
    return ranges.filter(([lo,hi])=>hi-lo>EPS);
  }
  function allocate(total, weights) {
    const sum=weights.reduce((a,b)=>a+b,0);
    if (!(sum>0)) return weights.map(()=>0);
    const raw=weights.map(w=>total*w/sum), out=raw.map(Math.floor);
    let left=total-out.reduce((a,b)=>a+b,0);
    const order=raw.map((n,i)=>({i,rem:n-out[i]})).sort((a,b)=>b.rem-a.rem||a.i-b.i);
    for (let i=0;i<left;i++) out[order[i%order.length].i]++;
    return out;
  }
  function autoDistribute(config, budget) {
    const g=geometry(config),strip=validGeometry(g)?g.strip:defaultGeometry().strip;
    const weights=SIDES.map((edge,i)=>intervals(config,edge,true).reduce((a,[lo,hi])=>a+hi-lo,0)*(i%2===0?16*strip.width:9*strip.height));
    const counts=allocate(Math.max(0,budget-config.offset),weights);
    SIDES.forEach((side,i)=>config.edges[side].count=counts[i]);
    return config;
  }
  function components(config) {
    const ranges=[];
    SIDES.forEach((edge,i)=>intervals(config,edge,false).forEach(([lo,hi])=>ranges.push([i+lo,i+hi])));
    ranges.sort((a,b)=>a[0]-b[0]);
    const merged=[];
    for(const item of ranges) {
      const last=merged[merged.length-1];
      if(last && item[0]-last[1]<EPS) last[1]=Math.max(last[1],item[1]);
      else merged.push(item.slice());
    }
    if(merged.length>1 && merged[0][0]<EPS && 4-merged[merged.length-1][1]<EPS) {
      const first=merged.shift(), last=merged.pop();
      merged.push([last[0],first[1]+4]);
    }
    return merged;
  }
  function point(edge,u) {
    if(edge==='top') return {x:u,y:0};
    if(edge==='right') return {x:1,y:u};
    if(edge==='bottom') return {x:1-u,y:1};
    return {x:0,y:1-u};
  }
  function sample(edge,u,depth=.045) {
    const p=point(edge,u);
    if(edge==='top') p.y=depth;
    if(edge==='right') p.x=1-depth;
    if(edge==='bottom') p.y=1-depth;
    if(edge==='left') p.x=depth;
    return p;
  }
  function projectSample(config,edge,u){
    const g=geometry(config),a=g.screen,b=g.strip,inset=sampling(config);
    if(['x','y','width','height'].every(key=>a[key]===b[key]))return sample(edge,u,inset);
    const physical=point(edge,u),x=(b.x+physical.x*b.width-a.x)/a.width,y=(b.y+physical.y*b.height-a.y)/a.height,clamp=value=>Math.max(0,Math.min(1,value));
    if(edge==='top')return {x:clamp(x),y:inset};
    if(edge==='bottom')return {x:clamp(x),y:1-inset};
    if(edge==='right')return {x:1-inset,y:clamp(y)};
    return {x:inset,y:clamp(y)};
  }
  function interpolateEdge(points,edge,u){
    const values=points.filter(point=>point.edge===edge&&/^#[0-9a-f]{6}$/i.test(point.color)).sort((a,b)=>a.position-b.position);
    if(!values.length)return null;if(u<=values[0].position)return values[0].color;if(u>=values[values.length-1].position)return values[values.length-1].color;
    let a=values[0],b=values[values.length-1];for(let i=1;i<values.length;i++)if(u<=values[i].position){a=values[i-1];b=values[i];break;}
    const mix=b.position>a.position?(u-a.position)/(b.position-a.position):0;
    return '#'+[1,3,5].map(index=>Math.round(parseInt(a.color.slice(index,index+2),16)*(1-mix)+parseInt(b.color.slice(index,index+2),16)*mix).toString(16).padStart(2,'0')).join('');
  }
  function previewField(points,u,v){
    const colors=[interpolateEdge(points,'top',u),interpolateEdge(points,'right',v),interpolateEdge(points,'bottom',1-u),interpolateEdge(points,'left',1-v)],weights=[v,1-u,1-v,u].map(distance=>1/Math.pow(distance+.08,2));
    let total=0;const channels=[0,0,0];colors.forEach((color,index)=>{if(!color)return;total+=weights[index];for(let channel=0;channel<3;channel++)channels[channel]+=parseInt(color.slice(channel*2+1,channel*2+3),16)*weights[index];});
    if(!total)return null;return '#'+channels.map(channel=>Math.round(channel/total).toString(16).padStart(2,'0')).join('');
  }
  function evaluate(config,budget=126) {
    const errors=[], warnings=[];
    if(config.sampling!==undefined&&(!config.sampling||typeof config.sampling!=='object'||Array.isArray(config.sampling)))errors.push('取色配置格式无效。');
    if(!['standard','fine'].includes(samplingQuality(config)))errors.push('采样档位必须为标准或细腻。');
    if(typeof sampling(config)!=='number'||!Number.isFinite(sampling(config))||sampling(config)<0||sampling(config)>.2)errors.push('取色缩进必须在 0–20% 内。');
    if(!validGeometry(geometry(config)))errors.push('几何方框必须位于场景 0–100% 内，宽高至少 2%，且不能超出边界。');
    if(!Number.isInteger(budget)||budget<1||budget>10000) errors.push('控制器灯珠总数必须为 1–10000 的整数。');
    if(!['cw','ccw'].includes(config.direction)) errors.push('请选择有效的走线方向。');
    if(!(config.cut.position>=0)||!(config.cut.position<=100)) errors.push('断开位置必须在 0–100% 内。');
    if(!['c','closed'].includes(config.shape)) errors.push('请选择 C 字形或口字形。');
    if(!Number.isInteger(config.offset)||config.offset<0) errors.push('WLED 起始灯号必须为不小于 0 的整数。');
    if(!SIDES.includes(config.gap.edge)||!SIDES.includes(config.cut.edge)) errors.push('请选择有效的边。');
    if(config.shape==='c' && (!(config.gap.start>=0)||!(config.gap.end<=100)||!(config.gap.end>config.gap.start))) errors.push('缺口终点必须大于起点，且在 0–100% 内。');
    SIDES.forEach(edge=>{
      const e=config.edges[edge];
      if(!Number.isInteger(e.count)||e.count<0||e.count>budget) errors.push(NAMES[edge]+'灯珠数必须为 0–'+budget+' 的整数。');
      if(!e.full && (!(e.start>=0)||!(e.end<=100)||!(e.end>e.start))) errors.push(NAMES[edge]+'覆盖终点必须大于起点，且在 0–100% 内。');
      if(e.count>0 && intervals(config,edge,true).length===0) errors.push(NAMES[edge]+'没有可覆盖的位置，请将该边灯珠数设为 0。');
    });
    const total=SIDES.reduce((n,e)=>n+(Number(config.edges[e].count)||0),0);
    if(total===0) errors.push('请至少分配一颗灯珠。');
    if(total+config.offset>budget) errors.push('灯号超出控制器范围：起始 '+config.offset+' + 使用 '+total+'，控制器共有 '+budget+' 颗。');
    if(total+config.offset<budget) warnings.push('当前排布之后还有 '+(budget-total-config.offset)+' 颗灯珠可分配。');
    const arcs=components(config);
    if(config.shape==='c' && arcs.length!==1) errors.push('C 字形需要一条连续灯带。请检查零灯珠边、局部覆盖是否形成了第二处缺口。');
    if(config.shape==='closed' && (arcs.length!==1||arcs[0][1]-arcs[0][0]<4-EPS)) errors.push('口字形需要四边连续完整覆盖。需要留空时请选择 C 字形。');
    const raw=[];
    if(errors.some(e=>e.includes('整数')||e.includes('终点')||e.includes('几何')||e.includes('缩进')||e.includes('取色配置')||e.includes('采样档位'))) return {errors,warnings,total,arcs,leds:[]};
    SIDES.forEach((edge,edgeIndex)=>{
      const ranges=intervals(config,edge,false), number=config.edges[edge].count;
      const counts=allocate(number,ranges.map(([a,b])=>b-a));
      ranges.forEach(([a,b],segmentIndex)=>{
        for(let i=0;i<counts[segmentIndex];i++) {
          const u=a+(i+.5)/counts[segmentIndex]*(b-a);
          raw.push({edge,u,p:edgeIndex+u,position:point(edge,u),sample:projectSample(config,edge,u)});
        }
      });
    });
    raw.sort((a,b)=>a.p-b.p);
    let ordered=raw.slice();
    if(config.shape==='c' && arcs.length===1) {
      const start=arcs[0][0];
      ordered.sort((a,b)=>((a.p-start+4)%4)-((b.p-start+4)%4));
      if(config.direction==='ccw') ordered.reverse();
    } else if(config.shape==='closed' && raw.length) {
      const target=SIDES.indexOf(config.cut.edge)+config.cut.position/100;
      let nearest=0, distance=Infinity;
      raw.forEach((item,i)=>{const d=Math.min(Math.abs(item.p-target),4-Math.abs(item.p-target));if(d<distance){distance=d;nearest=i;}});
      ordered=raw.slice(nearest).concat(raw.slice(0,nearest));
      if(config.direction==='ccw') ordered=[ordered[0]].concat(ordered.slice(1).reverse());
    }
    const leds=ordered.map((led,index)=>({...led,order:index+1,physicalIndex:config.offset+index}));
    return {errors,warnings,total,arcs,leds};
  }
  function exportLayout(config,budget=126) {
    const result=evaluate(config,budget);
    if(result.errors.length) throw new Error(result.errors.join(' '));
    const saved=clone(config); delete saved.view;saved.geometry=clone(geometry(config));saved.sampling={...(saved.sampling||{}),inset:sampling(config),quality:samplingQuality(config)};
    return {schema:'xh-tv-wled-layout/v1', coordinateSystem:'screen-front-normalized',geometryCoordinateSystem:'scene-normalized',positionCoordinateSystem:'strip-front-normalized', controller:{address:'192.168.1.50',name:'WLED 示例',ledCount:budget},layout:saved,
      leds:result.leds.map(led=>({order:led.order,wledIndex:led.physicalIndex,edge:led.edge,position:led.position,sample:led.sample})),
      unassignedIndices:Array.from({length:budget},(_,i)=>i).filter(i=>i<config.offset||i>=config.offset+result.total)};
  }
  const api={SIDES,NAMES,defaultGeometry,geometry,validGeometry,projectSample,sampling,samplingQuality,interpolateEdge,previewField,defaults,clone,intervals,allocate,autoDistribute,components,point,sample,evaluate,exportLayout};
  if(typeof module!=='undefined' && module.exports) module.exports=api;
  scope.LayoutModel=api;
})(typeof globalThis!=='undefined'?globalThis:window);
