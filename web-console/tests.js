const assert=require('node:assert/strict');
const M=require('./model.js');
const BASE=M.defaults();
let checks=0;
function valid(c){const r=M.evaluate(c);assert.deepEqual(r.errors,[]);assert.equal(r.leds.length,r.total);assert.equal(new Set(r.leds.map(x=>x.physicalIndex)).size,r.total);checks++;return r;}
let r=valid(BASE);assert.equal(r.total,126);assert.equal(r.leds[0].edge,'left');assert.equal(r.leds.at(-1).edge,'right');
for(const edge of M.SIDES)for(const direction of ['cw','ccw']){let c=M.defaults();c.gap.edge=edge;c.direction=direction;M.autoDistribute(c,126);r=valid(c);assert.equal(c.edges[edge].count,0);assert.ok(r.leds.every(x=>x.sample.x>=0&&x.sample.x<=1&&x.sample.y>=0&&x.sample.y<=1));}
let c=M.defaults();c.gap={edge:'bottom',start:35,end:65};M.autoDistribute(c,126);r=valid(c);assert.ok(r.leds.some(x=>x.edge==='bottom'&&x.u<.35));assert.ok(r.leds.some(x=>x.edge==='bottom'&&x.u>.65));assert.ok(r.leds.every(x=>x.edge!=='bottom'||x.u<.35||x.u>.65));
c=M.defaults();c.edges.top.count=0;assert.ok(M.evaluate(c).errors.some(x=>x.includes('连续')));checks++;
c=M.defaults();c.shape='closed';M.autoDistribute(c,126);r=valid(c);const counts=Object.fromEntries(M.SIDES.map(e=>[e,r.leds.filter(x=>x.edge===e).length]));
for(const edge of M.SIDES)for(const position of [0,50,100]){c.cut={edge,position};r=valid(c);assert.deepEqual(Object.fromEntries(M.SIDES.map(e=>[e,r.leds.filter(x=>x.edge===e).length])),counts);}
const clockwise=M.evaluate(c).leds.map(x=>x.p);c.direction='ccw';const counter=M.evaluate(c).leds.map(x=>x.p);assert.equal(counter[0],clockwise[0]);assert.deepEqual(counter.slice(1),clockwise.slice(1).reverse());checks++;
const a=M.exportLayout(c);c.view='back';assert.deepEqual(M.exportLayout(c),a);checks++;
c=M.defaults();c.offset=1;assert.ok(M.evaluate(c).errors.some(x=>x.includes('超出')));c.edges.top.count=49;r=valid(c);assert.equal(r.leds[0].physicalIndex,1);assert.equal(r.leds.at(-1).physicalIndex,125);assert.deepEqual(M.exportLayout(c).unassignedIndices,[0]);
c=M.defaults();c.edges.top.count=1;c.edges.left.count=1;c.edges.right.count=1;r=valid(c);assert.equal(r.total,3);assert.equal(r.leds.find(x=>x.edge==='top').u,.5);
c=M.defaults();c.gap.start=80;c.gap.end=20;assert.ok(M.evaluate(c).errors.some(x=>x.includes('终点')));checks++;
c=M.defaults();c.shape='closed';M.autoDistribute(c,126);c.edges.top.full=false;c.edges.top.start=10;c.edges.top.end=90;assert.ok(M.evaluate(c).errors.some(x=>x.includes('完整')));checks++;
// Remaining capacity is measured after offset + mapping, not before the offset.
c=M.defaults();c.offset=1;c.edges.top.count=49;r=valid(c);assert.deepEqual(r.warnings,[]);assert.deepEqual(M.exportLayout(c).unassignedIndices,[0]);
c=M.defaults();c.offset=3;c.edges.top.count=44;r=valid(c);assert.equal(r.total,120);assert.deepEqual(r.warnings,['当前排布之后还有 3 颗灯珠可分配。']);assert.deepEqual(M.exportLayout(c).unassignedIndices,[0,1,2,123,124,125]);
c=M.defaults();c.edges.top.count=44;r=valid(c);assert.deepEqual(r.warnings,['当前排布之后还有 6 颗灯珠可分配。']);
c=M.defaults();c.offset=1;assert.ok(M.evaluate(c).errors.length);assert.throws(()=>M.exportLayout(c),/超出/);checks++;
console.log('Passed '+checks+' mapping cases.');
