import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const html = await readFile(resolve(root, 'index.html'), 'utf8');
const script = await readFile(resolve(root, 'app.js'), 'utf8');
const errors = [];
const dom = new JSDOM(html, {url:'http://localhost:4173',runScripts:'outside-only',pretendToBeVisual:true});
const {window} = dom;
window.addEventListener('error', event => errors.push(event.error));
let nextId = 0;
const intervals = new Map(), timeouts = new Map();
window.setInterval = callback => {const id=++nextId;intervals.set(id,callback);return id;};
window.clearInterval = id => intervals.delete(id);
window.setTimeout = callback => {const id=++nextId;timeouts.set(id,callback);return id;};
window.clearTimeout = id => timeouts.delete(id);
window.requestAnimationFrame = callback => {callback();return 0;};
window.eval(script);
const document = window.document;
const q = selector => document.querySelector(selector);
const click = (action,value) => {
  const selector=`[data-action="${action}"]${value===undefined?'':`[data-value="${value}"]`}`;
  const element=q(selector);assert.ok(element,`Missing ${selector}`);element.click();assert.equal(errors.length,0,errors[0]?.stack);
};
const screen = () => q('#screen').textContent;
const flush = () => {const callbacks=[...timeouts.values()];timeouts.clear();callbacks.forEach(f=>f());};
const finishUpload = () => {for(let i=0;i<20;i++)[...intervals.values()].forEach(f=>f());};
let count=0;
function check(name,fn){fn();count++;console.log(`PASS ${name}`);}

check('initial gallery has 18 local sample photos and six pending',()=>{
  assert.equal(document.querySelectorAll('.photo-cell').length,18);
  assert.match(screen(),/还有 6 项待备份/);
});
check('status filter and full-screen detail navigation',()=>{
  click('filter','pending');assert.equal(document.querySelectorAll('.photo-cell').length,6);
  click('photo','1');assert.ok(q('.detail-image'));assert.match(screen(),/尚未备份/);
  click('back');assert.ok(q('.photo-grid'));
});
check('multi-select can remove a selected item and start upload',()=>{
  q('[data-action="select-mode"]').click();assert.match(screen(),/已选择 6 项/);
  click('photo','1');assert.match(screen(),/已选择 5 项/);
  click('upload-selected');assert.match(screen(),/正在安心备份/);
});
check('pause preserves progress, resume completes and writes task history',()=>{
  click('pause');assert.match(screen(),/休息一下/);assert.equal(intervals.size,0);
  click('resume');finishUpload();assert.match(screen(),/1 项待备份/);
  click('goto','history');assert.match(screen(),/手动备份/);
});
check('ordinary batch upload completes all six new files',()=>{
  click('scenario','pending');click('tab','backup');click('upload');finishUpload();
  assert.match(screen(),/你的备份已完成/);assert.match(screen(),/18已备份/);
});
check('cleanup verifies, confirms twice and preserves unselected folders',()=>{
  click('goto','cleanup');assert.match(screen(),/16 个已备份项目/);
  click('verify-cleanup');assert.match(q('#overlay').textContent,/正在核验/);flush();
  assert.match(q('#overlay').textContent,/可以安心释放空间/);
  click('system-delete');assert.match(q('#overlay').textContent,/Android 系统确认的模拟界面/);
  click('finish-cleanup');assert.match(q('#overlay').textContent,/已模拟释放/);
  click('cleanup-done');click('tab','photos');assert.equal(document.querySelectorAll('.photo-cell').length,2);
});
check('no backup can clear automatically without explicit confirmation',()=>{
  click('scenario','complete');click('goto','cleanup');click('verify-cleanup');flush();click('close');
  click('tab','photos');assert.equal(document.querySelectorAll('.photo-cell').length,18);
});
check('unbacked local deletion warns the user before simulated removal',()=>{
  click('scenario','pending');click('photo','1');click('delete-one','1');
  assert.match(q('#overlay').textContent,/尚未备份/);click('close');click('back');
  assert.equal(document.querySelectorAll('.photo-cell').length,18);
});
check('Wi-Fi wait resumes when a network becomes available',()=>{
  click('scenario','wifi');click('goto','network');click('connect-wifi');
  assert.match(screen(),/正在安心备份/);finishUpload();
});
check('error scenario retries incomplete files only',()=>{
  click('scenario','error');assert.match(screen(),/2 项待备份/);click('upload');finishUpload();
  assert.match(screen(),/你的备份已完成/);
});
check('mock first login leads into folder selection and SD authorization',()=>{
  click('scenario','signedout');click('login');assert.match(q('#overlay').textContent,/不收集账号/);
  click('confirm-login');flush();assert.match(screen(),/只备份你想留住的/);
  click('folder-toggle','folderSD');assert.match(q('#overlay').textContent,/SD 卡照片/);
  click('authorize-sd');assert.equal(q('[data-value="folderSD"]').getAttribute('aria-checked'),'true');
});
check('schedule time and settings update on screen',()=>{
  click('goto','settings');click('toggle','schedule');click('schedule-time');q('#backup-time').value='03:15';click('save-time');
  assert.match(screen(),/03:15/);click('toggle','charging');
  assert.equal(q('[data-value="charging"]').getAttribute('aria-checked'),'true');
});
check('custom album content remains text rather than executable markup',()=>{
  click('album');click('set-album','自定义相册');q('#album-name').value='<img src=x onerror=alert(1)>';
  click('save-album');assert.match(screen(),/<img src=x/);assert.equal(document.querySelectorAll('img[src="x"]').length,0);
});
check('appearance supports three palettes and dark mode',()=>{
  click('theme','dark');click('palette','sage');assert.equal(q('#phone').dataset.theme,'dark');
  assert.equal(q('#phone').dataset.palette,'sage');click('theme','light');click('palette','lavender');
});
check('gallery media and date filters can be reset',()=>{
  click('scenario','pending');click('photo-filter');click('media-type','video');click('apply-date');
  assert.equal(document.querySelectorAll('.photo-cell').length,2);
  click('photo-filter');click('clear-filter');assert.equal(document.querySelectorAll('.photo-cell').length,18);
});
check('long press enters multi-selection without opening photo details',()=>{
  const cell=q('[data-action="photo"]');
  cell.dispatchEvent(new window.MouseEvent('pointerdown',{bubbles:true,button:0,clientX:30,clientY:300}));
  flush();assert.match(screen(),/已选择 1 项/);assert.equal(q('.detail-image'),null);
  click('end-selection');
});
for(const name of ['mountains','lake','meadow','forest','sea','hills','beach','alpine','valley','flowers','coffee','dog'])await access(resolve(root,`assets/${name}.jpg`));
assert.equal(errors.length,0);
console.log(`\n${count} frontend interaction tests passed; all 12 photo assets exist.`);
dom.window.close();
