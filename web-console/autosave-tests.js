'use strict';
const assert=require('node:assert/strict');
const {harness,flush,initial,fingerprint,M,C}=require('./console-api-tests.js');
const posts=h=>h.calls.filter(c=>c.method==='POST');
const configs=h=>posts(h).filter(c=>c.path==='/api/config');
const value=async(h,id,text,type='input')=>{h.element(id).value=text;await h.fire(id,type);};
let groups=0;
(async()=>{
  const idle=harness();await flush();await idle.context.TVConsole.refresh();await idle.context.TVConsole.refreshPreview();idle.context.LayoutPreview.setConfig(M.clone(idle.layout()));await flush();await idle.flushAutosave();assert.equal(posts(idle).length,0);groups++;

  const quiet=harness();await flush();const before=fingerprint(quiet.saved());
  for(const n of ['20','30','40','51'])await value(quiet,'brightness',n);
  assert.equal(quiet.autoTimers.size,1);assert.equal(configs(quiet).length,0);await quiet.flushAutosave();
  assert.equal(configs(quiet).length,1);assert.equal(configs(quiet)[0].headers['If-Match'],before);assert.equal(quiet.saved().output.brightness,.51);assert.equal(quiet.context.TVConsole.isDirty(),false);assert.equal(posts(quiet).some(c=>c.path==='/api/start'||c.path==='/api/apply'),false);assert.equal(quiet.enabled(),false);groups++;

  const gesture=harness();await flush();await gesture.documentEvent('pointerdown',{target:{closest:()=>null,matches:()=>true}});
  for(const n of ['25','45','65'])await value(gesture,'brightness',n);await gesture.flushAutosave();assert.equal(configs(gesture).length,0);
  await gesture.documentEvent('pointerup');await gesture.flushAutosave();assert.equal(configs(gesture).length,1);assert.equal(gesture.saved().output.brightness,.65);groups++;

  const invalid=harness();await flush();await value(invalid,'color-gamma-number','');await invalid.flushAutosave();assert.equal(configs(invalid).length,0);assert.match(invalid.element('saveState').textContent,/补全/);
  await value(invalid,'color-gamma-number','1.25');await invalid.flushAutosave();assert.equal(invalid.saved().output.color.gamma,1.25);assert.equal(invalid.context.TVConsole.isDirty(),false);groups++;

  const active=harness('abc',initial(),{enabled:true,applied:initial()});await flush();await value(active,'brightness','62');await active.flushAutosave();assert.deepEqual(posts(active).map(c=>c.path),['/api/config','/api/apply']);assert.equal(active.applied().output.brightness,.62);assert.equal(active.enabled(),true);groups++;

  const editDuringSave=harness('abc',initial(),{enabled:true,applied:initial()});await flush();const release=editDuringSave.hold('/api/config');await value(editDuringSave,'brightness','31');await editDuringSave.flushAutosave();assert.equal(editDuringSave.context.TVConsole.getSaveState().saving,true);
  await value(editDuringSave,'brightness','79');assert.equal(editDuringSave.element('brightness').disabled,false);assert.equal(editDuringSave.element('.workspace').inert,false);release();await flush();await flush();
  assert.equal(editDuringSave.context.TVConsole.getDocument().output.brightness,.79);assert.equal(editDuringSave.context.TVConsole.isDirty(),true);assert.equal(posts(editDuringSave).some(c=>c.path==='/api/apply'),false);await editDuringSave.flushAutosave();assert.equal(configs(editDuringSave).length,2);assert.equal(editDuringSave.saved().output.brightness,.79);assert.equal(editDuringSave.applied().output.brightness,.79);groups++;

  for(const heldPath of ['/api/config','/api/apply']){
    const stopping=harness('abc',initial(),{enabled:true,applied:initial()});await flush();const unblock=stopping.hold(heldPath);await value(stopping,'brightness','43');await stopping.flushAutosave();assert.equal(stopping.element('syncSwitch').disabled,false);await stopping.fire('syncSwitch');assert.equal(stopping.enabled(),false);unblock();await flush();await flush();assert.equal(stopping.enabled(),false);assert.equal(posts(stopping).some(c=>c.path==='/api/start'),false);
  }groups++;

  const conflict=harness();await flush();await value(conflict,'brightness','45');const remote=initial();remote.output.brightness=.84;conflict.setStored(remote);await conflict.flushAutosave();assert.equal(configs(conflict).length,0);assert.equal(conflict.context.TVConsole.getSaveState().conflict,true);assert.equal(conflict.context.TVConsole.getDocument().output.brightness,.45);assert.equal(conflict.element('saveConflict').hidden,false);await conflict.fire('useRemote');assert.equal(conflict.context.TVConsole.getDocument().output.brightness,.84);assert.equal(conflict.context.TVConsole.isDirty(),false);groups++;

  const overwrite=harness();await flush();await value(overwrite,'brightness','46');overwrite.setStored(remote);await overwrite.flushAutosave();await overwrite.fire('useLocal');assert.equal(overwrite.saved().output.brightness,.46);assert.equal(configs(overwrite)[0].headers['If-Match'],fingerprint(remote));assert.equal(overwrite.context.TVConsole.isDirty(),false);groups++;

  // The displayed document and its CAS fingerprint must remain one atomic pair.
  for(const noEditorSnapshot of [false,true]){
    const raced=harness('abc',initial(),{noEditorSnapshot});await flush();await value(raced,'brightness','45');raced.setStored(remote);await raced.flushAutosave();raced.setRaceAfterRead(true);await raced.fire('useRemote');
    await value(raced,'color-gamma-number','1.25');await raced.flushAutosave();
    assert.equal(raced.saved().output.brightness,.987654321);
    if(!noEditorSnapshot){assert.equal(configs(raced).length,0);assert.equal(raced.context.TVConsole.getSaveState().conflict,true);}
    else{assert.equal(raced.context.TVConsole.getDocument().output.brightness,.987654321);assert.equal(raced.saved().output.color.gamma,1.25);}
  }groups++;

  const failedFirst=harness('abc',initial(),{initialOffline:true});await flush();assert.equal(failedFirst.storage.size,0);assert.equal(failedFirst.context.TVConsole.getSaveState().revision,0);failedFirst.setOffline(false);await failedFirst.context.TVConsole.refresh();await failedFirst.flushAutosave();assert.equal(failedFirst.context.TVConsole.getDocument().output.brightness,.123456789);assert.equal(failedFirst.context.TVConsole.getSaveState().conflict,false);assert.equal(failedFirst.context.TVConsole.isDirty(),false);assert.equal(configs(failedFirst).length,0);groups++;

  const unseen=harness();await flush();unseen.setStored(remote);await unseen.fire('syncSwitch');assert.equal(unseen.enabled(),false);assert.equal(posts(unseen).some(c=>c.path==='/api/start'),false);assert.equal(unseen.context.TVConsole.getSaveState().conflict,true);await unseen.fire('useRemote');await unseen.fire('syncSwitch');assert.equal(unseen.enabled(),true);assert.equal(unseen.applied().output.brightness,.84);assert.equal(unseen.context.TVConsole.getDocument().output.brightness,.84);groups++;

  const lost=harness('abc',initial(),{enabled:true,applied:initial()});await flush();lost.loseNextPostResponse();await value(lost,'brightness','67');await lost.flushAutosave();assert.equal(configs(lost).length,1);assert.equal(lost.saved().output.brightness,.67);await lost.context.TVConsole.refresh();await lost.flushAutosave();assert.equal(configs(lost).length,1);assert.equal(lost.context.TVConsole.getSaveState().conflict,false);assert.equal(lost.applied().output.brightness,.67);groups++;

  const offline=harness();await flush();await value(offline,'brightness','37');offline.setOffline(true);await offline.context.TVConsole.refresh();assert.equal(configs(offline).length,0);offline.setOffline(false);await offline.context.TVConsole.refresh();await offline.flushAutosave();assert.equal(offline.saved().output.brightness,.37);assert.equal(offline.context.TVConsole.isDirty(),false);groups++;

  const cached=harness();await flush();await value(cached,'brightness','28');const staleStorage=new Map(cached.storage),restored=harness('abc',initial(),{storage:cached.storage});await flush();assert.equal(restored.context.TVConsole.getDocument().output.brightness,.28);await restored.flushAutosave();assert.equal(restored.saved().output.brightness,.28);const stale=harness('abc',remote,{storage:staleStorage});await flush();assert.equal(stale.context.TVConsole.getSaveState().conflict,true);await stale.flushAutosave();assert.equal(configs(stale).length,0);groups++;

  const address=harness();await flush();await value(address,'wledAddress','192.168.1.99');await address.flushAutosave();assert.equal(posts(address).length,0);await address.fire('wledAddress','blur');await address.flushAutosave();assert.equal(posts(address).filter(c=>c.path==='/api/probe').length,1);assert.equal(configs(address).length,0);assert.equal(address.device().ledCount,60);const layout=M.clone(address.layout());M.autoDistribute(layout,60);address.context.LayoutPreview.setConfig(layout);await flush();await address.flushAutosave();assert.equal(configs(address).length,1);assert.equal(address.saved().controller.address,'192.168.1.99');assert.equal(address.saved().controller.ledCount,60);groups++;

  const old=harness('abc',initial(),{enabled:true,applied:initial(),legacy:true});await flush();await value(old,'brightness','39');await old.flushAutosave();assert.equal(configs(old).length,1);assert.equal(posts(old).some(c=>c.path==='/api/start'||c.path==='/api/apply'),false);assert.match(old.element('saveHint').textContent,/更新应用|版本/);groups++;

  const demo=harness('');await flush();await value(demo,'brightness','54');const reopened=harness('',initial(),{storage:demo.storage});await flush();assert.equal(reopened.context.TVConsole.getDocument().output.brightness,.54);assert.equal(posts(demo).length,0);assert.match(demo.element('saveState').textContent,/此浏览器/);groups++;
  const black=harness('abc',initial(),{enabled:true,applied:initial()});await flush();for(const threshold of ['10','18','23'])await value(black,'blackFilterThreshold',threshold);assert.equal(configs(black).length,0);await black.flushAutosave();assert.equal(configs(black).length,1);assert.equal(black.saved().output.blackFilter.threshold,.23);assert.equal(black.applied().output.blackFilter.threshold,.23);assert.equal(black.context.TVConsole.isDirty(),false);groups++;
  black.element('blackFilterEnabled').checked=false;await black.fire('blackFilterEnabled','change');await black.flushAutosave();assert.equal(black.saved().output.blackFilter.enabled,false);assert.equal(black.applied().output.blackFilter.enabled,false);assert.equal(black.element('blackFilterThreshold').disabled,true);assert.equal(black.enabled(),true);groups++;
  const badBlack=harness();await flush();await value(badBlack,'blackFilterThreshold','51');await badBlack.flushAutosave();assert.equal(configs(badBlack).length,0);assert.match(badBlack.element('saveState').textContent,/补全/);await value(badBlack,'blackFilterThreshold','15');await badBlack.flushAutosave();assert.equal(badBlack.context.TVConsole.isDirty(),false);assert.equal(badBlack.enabled(),false);groups++;
  console.log('Passed '+groups+' automatic-save behavior groups: debounce/gestures, valid-only writes, serial latest edits, safe apply and stop races, conditional conflicts, lost responses, offline/cache recovery, address verification, legacy backend, and local demo persistence.');
})().catch(error=>{console.error(error);process.exitCode=1;});
