/* Buildless, local-only prototype. No Google APIs, uploads or device file access. */
'use strict';
const $ = (selector) => document.querySelector(selector);
const icon = (name, extra = '') => `<svg class="icon ${extra}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const escapeHTML = (value) => String(value).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const photoSeeds = [
  ['lake','湖边的清晨','今天，10月18日','08:42','pending','相机',4.8],
  ['coffee','一杯慢时光','今天，10月18日','09:16','pending','相机',3.2],
  ['dog','周末的小伙伴','今天，10月18日','10:08','pending','相机',5.1],
  ['meadow','风吹过原野','今天，10月18日','11:24','pending','相机',6.4],
  ['sea','沿海公路','今天，10月18日','13:20','pending','相机',86.2,'00:24'],
  ['flowers','窗边的一束花','今天，10月18日','14:35','pending','相机',4.3],
  ['mountains','去山里走走','昨天，10月17日','07:30','uploaded','相机',7.3],
  ['alpine','远方的湖','昨天，10月17日','09:45','uploaded','相机',6.8],
  ['forest','森林里的光','昨天，10月17日','11:02','uploaded','相机',5.5],
  ['hills','山间日出','昨天，10月17日','06:32','uploaded','相机',8.1],
  ['beach','海边散步','昨天，10月17日','16:12','uploaded','相机',63.4,'00:18'],
  ['valley','路上的风景','昨天，10月17日','17:48','uploaded','相机',7.6],
  ['flowers','花开的时候','10月16日 · 星期五','10:25','uploaded','微信图片',4.1],
  ['forest','雨后的绿意','10月16日 · 星期五','12:17','uploaded','相机',5.7],
  ['coffee','午后咖啡','10月16日 · 星期五','14:46','uploaded','截图',2.6],
  ['sea','向海而行','10月16日 · 星期五','15:13','uploaded','相机',4.9],
  ['meadow','阳光正好','10月16日 · 星期五','16:02','uploaded','相机',6.3],
  ['lake','再见，今天','10月16日 · 星期五','18:30','uploaded','相机',5.2],
];
const makePhotos = () => photoSeeds.map((p,i) => ({id:i+1,image:p[0],name:p[1],date:p[2],time:p[3],status:p[4],folder:p[5],size:p[6],duration:p[7]||'',cleaned:false}));
const initialSettings = () => ({auto:true,wifi:true,charging:false,schedule:false,time:'02:00',videos:true,restart:true,album:'按来源文件夹',folderCamera:true,folderScreenshots:false,folderWechat:false,folderSD:false,quality:'原始画质',concurrency:'自动',retry:true,timeout:'60 秒',mobile:'不使用移动流量'});
let photos = makePhotos();
let settings = initialSettings();
let theme = 'light';
let palette = 'lavender';
try { const stored = JSON.parse(localStorage.getItem('cloud-demo-appearance') || '{}'); theme = stored.theme === 'dark' ? 'dark' : 'light'; if (['lavender','sage','peach'].includes(stored.palette)) palette = stored.palette; } catch {}
let state = {page:'photos',stack:[],filter:'all',selected:new Set(),selecting:false,scenario:'pending',account:true,backup:'pending',progress:0,activePhoto:1,mediaType:'all',dateFrom:'',dateTo:'',history:[],cleaned:0,uploadQueue:[],currentUpload:null};
let uploadTimer = null;
let modalTimer = null;
let toastTimer = null;
let currentSheet = '';

function visiblePhotos(){return photos.filter(p=>!p.cleaned);}
function uploaded(){return visiblePhotos().filter(p=>p.status==='uploaded');}
function pending(){return visiblePhotos().filter(p=>p.status!=='uploaded');}
function backupCandidates(){return pending().filter(p=>settings[{'相机':'folderCamera','截图':'folderScreenshots','微信图片':'folderWechat'}[p.folder]]&&(settings.videos||!p.duration));}
function eligible(){return uploaded().filter(p=>p.folder==='相机');}
function prettyMB(number){return number>=1024?`${(number/1024).toFixed(1)} GB`:`${number.toFixed(1)} MB`;}
function photoImage(p,extra=''){return `<img src="assets/${p.image}.jpg" alt="${escapeHTML(p.name)}" ${extra}>`;}
function button(label,action,value='',kind='primary',symbol=''){return `<button class="${kind}-button" data-action="${action}" data-value="${escapeHTML(value)}">${symbol?icon(symbol):''}${label}</button>`;}
function toolbar(title,actions=''){return `<div class="toolbar"><button class="icon-button" data-action="back" aria-label="返回">${icon('back')}</button><span class="toolbar-title">${title}</span><div class="toolbar-actions">${actions}</div></div>`;}
function row(title,subtitle,symbol,action,value='',end=''){return `<button class="setting-row" data-action="${action}" data-value="${escapeHTML(value)}">${symbol?icon(symbol):''}<span class="setting-copy"><span class="setting-title">${escapeHTML(title)}</span>${subtitle?`<span class="setting-subtitle">${escapeHTML(subtitle)}</span>`:''}</span>${end?`<span class="setting-end">${escapeHTML(end)}</span>`:''}${icon('arrow','arrow')}</button>`;}
function toggleRow(title,subtitle,symbol,key){return `<button class="setting-row" role="switch" aria-checked="${settings[key]}" data-action="toggle" data-value="${key}">${symbol?icon(symbol):''}<span class="setting-copy"><span class="setting-title">${title}</span><span class="setting-subtitle">${subtitle}</span></span><span class="switch ${settings[key]?'on':''}" aria-hidden="true"></span></button>`;}
function toast(text){clearTimeout(toastTimer);$('#toast').textContent=text;$('#toast').classList.add('visible');toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),3200);}
function applyAppearance(){
  $('#phone').dataset.theme=theme;$('#phone').dataset.palette=palette;
  $('#light-button').classList.toggle('active',theme==='light');$('#dark-button').classList.toggle('active',theme==='dark');
  document.querySelectorAll('.swatch').forEach(b=>b.classList.toggle('selected',b.dataset.value===palette));
  $('#palette-label').textContent={lavender:'薰衣草',sage:'鼠尾草',peach:'暖杏'}[palette];
  try{localStorage.setItem('cloud-demo-appearance',JSON.stringify({theme,palette}));}catch{}
  $('meta[name="theme-color"]').content=theme==='dark'?'#151218':'#fdf8ff';
}
function navigate(page,replace=false){if(!replace)state.stack.push(state.page);state.page=page;closeSheet();render();$('#screen').scrollTop=0;}
function switchTab(page){state.stack=[];state.page=page;state.selecting=false;state.selected.clear();closeSheet();render();$('#screen').scrollTop=0;}
function goBack(){state.page=state.stack.pop()||'photos';state.selecting=false;state.selected.clear();closeSheet();render();$('#screen').scrollTop=0;}
function render(){
  const scroll=$('#screen').scrollTop;
  const routes={photos:renderPhotos,backup:renderBackup,me:renderMe,settings:renderSettings,folders:renderFolders,cleanup:renderCleanup,history:renderHistory,detail:renderDetail,network:renderNetwork,advanced:renderAdvanced,welcome:renderWelcome};
  $('#screen').innerHTML=`<div class="page">${(routes[state.page]||renderPhotos)()}</div>`;
  const roots=['photos','backup','me'];
  $('#navigation').classList.toggle('hidden',!roots.includes(state.page));
  $('#navigation').innerHTML=[['photos','photo','照片'],['backup','cloud','备份'],['me','user','我的']].map(([page,symbol,label])=>`<button class="nav-item ${state.page===page?'active':''}" ${state.page===page?'aria-current="page"':''} data-action="tab" data-value="${page}"><span class="nav-icon">${icon(symbol)}</span><span>${label}</span></button>`).join('');
  $('#screen').scrollTop=scroll;
  document.querySelectorAll('.scenario').forEach(b=>b.classList.toggle('active',b.dataset.value===state.scenario));
}
function statusInfo(){
  if(!state.account)return {title:'为美好留一份备份',sub:'登录 Google，开始备份手机照片',symbol:'cloud',action:'login',tone:''};
  if(state.backup==='uploading')return {title:'正在备份你的照片',sub:`${Math.round(state.progress)}% · 保持连接，剩下的交给我们`,symbol:'cloud-up',action:'goto',value:'backup',tone:''};
  if(state.backup==='paused')return {title:'备份已暂停',sub:'已完成的项目会保留，随时可以继续',symbol:'pause',action:'goto',value:'backup',tone:''};
  if(state.backup==='wifi')return {title:'等待 Wi-Fi',sub:`${pending().length} 项准备就绪，连接后继续`,symbol:'wifi',action:'goto',value:'network',tone:''};
  if(state.backup==='error')return {title:'有项目需要处理',sub:'部分照片未能备份，点此查看',symbol:'info',action:'goto',value:'backup',tone:''};
  if(!settings.folderCamera&&!settings.folderScreenshots&&!settings.folderWechat&&!settings.folderSD)return {title:'选择备份文件夹',sub:'先选好范围，再开始自动备份',symbol:'folder',action:'goto',value:'folders',tone:''};
  if(!backupCandidates().length&&pending().length)return {title:'所选内容已备份',sub:'其他待备份内容可手动选择上传',symbol:'cloud-check',action:'goto',value:'backup',tone:'complete'};
  if(!pending().length)return {title:'备份已完成',sub:'已选文件夹中的内容均已备份',symbol:'cloud-check',action:'goto',value:'backup',tone:'complete'};
  return {title:`还有 ${pending().length} 项待备份`,sub:'原始画质 · 备份到 Google 相册',symbol:'cloud-up',action:'goto',value:'backup',tone:''};
}
function renderPhotos(){
  const info=statusInfo();
  let list=visiblePhotos().filter(p=>state.filter==='all'||(state.filter==='uploaded'?p.status==='uploaded':state.filter==='error'?p.status==='error':p.status!=='uploaded'));
  if(state.mediaType!=='all')list=list.filter(p=>state.mediaType==='video'?!!p.duration:!p.duration);
  if(state.dateFrom)list=list.filter(p=>`2026-10-${p.date.startsWith('今天')?'18':p.date.startsWith('昨天')?'17':'16'}`>=state.dateFrom);
  if(state.dateTo)list=list.filter(p=>`2026-10-${p.date.startsWith('今天')?'18':p.date.startsWith('昨天')?'17':'16'}`<=state.dateTo);
  const groups=[...new Set(list.map(p=>p.date))];
  return `<div class="page-heading">${state.selecting?`<div class="selected-count">已选择 ${state.selected.size} 项</div><button class="text-button small-button" data-action="end-selection">完成</button>`:`<h1>照片</h1><button class="avatar" data-action="tab" data-value="me" aria-label="账号与设置">${state.account?'林':'?'}</button>`}</div>
    <div class="backup-strip ${info.tone}">${icon(info.symbol)}<div class="strip-copy"><span class="strip-title">${info.title}</span><span class="strip-subtitle">${info.sub}</span></div><button class="icon-button" data-action="${info.action}" data-value="${info.value||''}" aria-label="查看备份状态">${icon('arrow')}</button></div>
    <div class="chips">${[['all','全部'],['pending',`待备份 ${pending().length}`],['uploaded','已备份']].map(([key,label])=>`<button class="chip ${state.filter===key?'active':''}" data-action="filter" data-value="${key}">${state.filter===key?icon('check'):''}${label}</button>`).join('')}<button class="chip" data-action="photo-filter" aria-label="更多照片筛选">${icon('settings')}</button></div>
    ${list.length?groups.map(date=>`<div class="date-label">${date}<button data-action="select-mode" data-value="${date}" aria-label="选择${date}的照片">${state.selecting?'全选':'选择'}</button></div><div class="photo-grid">${list.filter(p=>p.date===date).map(p=>`<button class="photo-cell ${state.selected.has(p.id)?'selected':''}" data-action="photo" data-value="${p.id}" aria-label="${p.name}，${p.status==='uploaded'?'已备份':p.status==='error'?'需要处理':'待备份'}">${photoImage(p,'loading="lazy"')}${state.selected.has(p.id)?`<span class="photo-state select-check">${icon('check')}</span>`:''}<span class="photo-state ${p.status}">${icon(p.status==='uploaded'?'cloud-check':p.status==='error'?'info':'cloud-up')}</span>${p.duration?`<span class="video-duration">${icon('play')}${p.duration}</span>`:''}</button>`).join('')}</div>`).join(''):`<div class="empty-state">${icon(state.filter==='pending'?'cloud-check':'photo')}<h2>${state.filter==='pending'?'都备份好了':'这里还没有照片'}</h2><p>${state.filter==='pending'?'新的照片出现后，会在这里等你。':'试试切换筛选条件。'}</p></div>`}
    ${state.selecting?`<div class="selection-bar">${button('备份所选','upload-selected','','primary','cloud-up')}${button('从手机删除','delete-selected','','text','trash')}</div>`:''}`;
}
function renderBackup(){
  const info=statusInfo();const done=uploaded().length;const remaining=backupCandidates().length;
  const titles={pending:'给今天，留一份备份',uploading:'正在安心备份',paused:'休息一下，稍后继续',wifi:'等待 Wi-Fi 连接',error:'有照片需要再试一次'};
  let title=!state.account?'登录后开始备份':remaining?(titles[state.backup]||titles.pending):'你的备份已完成';
  let sub=!state.account?'使用自己的 Google 账号，保存照片和视频。':remaining?`${remaining} 项待备份 · ${prettyMB(pending().reduce((n,p)=>n+p.size,0))}`:'已选文件夹中的照片和视频均已备份';
  let action=!state.account?button('登录 Google','login','','primary'):state.backup==='uploading'?button('暂停备份','pause','','tonal','pause'):state.backup==='paused'?button('继续备份','resume','','primary','play'):state.backup==='wifi'?button('查看网络设置','goto','network','tonal','wifi'):remaining?button(state.backup==='error'?'重试未完成项目':'立即备份','upload','','primary',state.backup==='error'?'refresh':'cloud-up'):button('检查新照片','check-new','','tonal','refresh');
  return `<div class="page-heading"><h1>备份</h1><button class="icon-button" data-action="goto" data-value="settings" aria-label="备份设置">${icon('settings')}</button></div>
    <div class="hero-status"><div class="cloud-art ${state.backup==='uploading'?'status-expressive':''}">${icon(info.symbol)}</div><h2>${title}</h2><p>${sub}</p>${action}</div>
    <div class="page-padding">${['uploading','paused'].includes(state.backup)?`<div class="progress-track"><div class="progress-fill" style="width:${state.progress}%"></div></div><div class="progress-meta"><span>${state.backup==='paused'?'已暂停':'正在上传 · 2.8 MB/s'}</span><span>${Math.round(state.progress)}%</span></div>`:''}
    <div class="stat-grid"><div><strong>${done}</strong><span>已备份</span></div><div><strong>${remaining}</strong><span>待备份</span></div><div><strong>${photos.filter(p=>p.status==='error').length}</strong><span>需要处理</span></div></div>
    <div class="settings-group">${toggleRow('自动备份',settings.auto?'有新照片时，自动为你备份':'已关闭，仍可手动备份','cloud-up','auto')}${row('备份文件夹',`${[settings.folderCamera&&'相机',settings.folderScreenshots&&'截图',settings.folderWechat&&'微信图片',settings.folderSD&&'SD 卡'].filter(Boolean).join('、')||'未选择文件夹'}`,'folder','goto','folders')}${row('任务记录','查看已完成和未完成的备份','clock','goto','history')}</div>
    ${state.backup==='error'?`<div class="hint-card">${icon('info')}<p>有 2 项因网络中断未完成。已成功备份的内容不会重复上传。</p></div>`:`<div class="helper">${icon('shield')}<span>备份到当前 Google 账号，保留原始画质。已备份状态仅针对当前账号。</span></div>`}</div>`;
}
function renderMe(){return `<div class="page-heading"><h1>我的</h1><button class="icon-button" data-action="appearance" aria-label="外观设置">${icon(theme==='dark'?'moon':'sun')}</button></div><div class="page-padding">
  <button class="profile-card wide" data-action="account"><span class="avatar large">${state.account?'林':'?'}</span><span class="setting-copy" style="text-align:left"><h2>${state.account?'林间':'尚未登录'}</h2><p>${state.account?'lin.demo@example.com':'登录 Google，开始安心备份'}</p></span>${icon('arrow','arrow')}</button>
  <div class="cleanup-card">${icon('clean')}<span class="eyebrow-label">给手机腾点空间</span><h2>${prettyMB(eligible().reduce((n,p)=>n+p.size,0))}</h2><p>${eligible().length?'这些已备份的照片和视频，可进一步核验清理。':'暂时没有可清理的备份文件。'}</p>${button('释放手机空间','goto','cleanup','primary','clean')}</div>
  <div class="section-title">偏好设置</div><div class="settings-group">${row('备份设置','自动备份、画质和相册','cloud','goto','settings')}${row('网络与移动流量',settings.wifi?'仅使用 Wi-Fi 备份':'允许使用移动流量','wifi','goto','network')}${row('外观',`${theme==='dark'?'深色':'浅色'} · ${{lavender:'薰衣草',sage:'鼠尾草',peach:'暖杏'}[palette]}`,'sun','appearance')}${row('高级设置','后台运行、重试和更多选项','settings','goto','advanced')}</div>
  <div class="section-title">帮助</div><div class="settings-group">${row('备份遇到问题？','检查网络、权限和后台运行设置','info','help')}${row('关于云备份','为珍贵的日常，留一份安心','cloud-check','about')}</div><div class="note-label">云备份 · 交互设计预览</div></div>`;}
function renderSettings(){return `${toolbar('备份设置')}<div class="page-padding"><h1 class="headline">让备份，<br>成为一件小事。</h1><div class="settings-group">${toggleRow('自动备份','备份选定文件夹中的新增照片和视频','cloud-up','auto')}${row('备份账号',state.account?'lin.demo@example.com':'尚未登录','user','account')}${row('备份画质','原始画质，保留每个细节','photo','quality')}</div><div class="section-title">备份内容</div><div class="settings-group">${row('备份文件夹','选择需要自动备份的文件夹','folder','goto','folders')}${toggleRow('备份视频','照片之外，也保存精彩片段','play','videos')}${row('相册归类',settings.album,'grid','album')}</div><div class="section-title">备份时机</div><div class="settings-group">${row('网络与移动流量',settings.wifi?'仅使用 Wi-Fi':'允许移动流量','wifi','goto','network')}${toggleRow('仅充电时备份','减少日常使用时的电量消耗','battery','charging')}${toggleRow('定时备份',settings.schedule?`每天 ${settings.time} 附近检查`:'有新增内容时自动检查','clock','schedule')}${settings.schedule?row('备份时间',`每天 ${settings.time} 附近`,'clock','schedule-time'):''}</div><div class="helper">${icon('info')}<span>自动备份会在条件满足时运行。定时时间可能因系统省电设置稍有延后。</span></div></div>`;}
function renderFolders(){const folders=[['folderCamera','相机','DCIM / Camera','lake','18 项 · 建议开启'],['folderScreenshots','截图','Pictures / Screenshots','coffee','6 项'],['folderWechat','微信图片','Pictures / WeiXin','flowers','12 项'],['folderSD','SD 卡照片','外置存储卡',null,settings.folderSD?'已授权访问':'需要访问权限']];return `${toolbar('备份文件夹')}<div class="page-padding"><h1 class="headline">只备份你想留住的。</h1><p class="muted body-small">开启的文件夹会自动检查新增内容。关闭后，已上传到 Google 相册的备份仍会保留。</p><div class="settings-group folder-list">${folders.map(([key,name,path,image,sub])=>`<button class="setting-row" role="switch" aria-checked="${settings[key]}" data-action="folder-toggle" data-value="${key}"><span class="folder-preview">${image?`<img src="assets/${image}.jpg" alt="">`:icon('sd')}</span><span class="setting-copy"><span class="setting-title">${name}</span><span class="setting-subtitle">${path}<br>${sub}</span></span><span class="switch ${settings[key]?'on':''}"></span></button>`).join('')}</div><div class="helper">${icon('shield')}<span>首次使用默认只开启相机文件夹。其他内容由你决定。</span></div>${button('添加其他文件夹','add-folder','','outline','folder')}</div>`;}
function renderNetwork(){return `${toolbar('网络与移动流量')}<div class="page-padding"><h1 class="headline">按你的网络习惯备份。</h1><div class="settings-group">${toggleRow('仅使用 Wi-Fi','避免备份消耗移动流量','wifi','wifi')}${row('移动流量使用量',settings.mobile,'cloud-up','mobile-data')}</div><div class="section-title">当前网络</div><div class="settings-group">${row(state.backup==='wifi'?'尚未连接 Wi-Fi':'已连接 Wi-Fi',state.backup==='wifi'?'连接后将自动继续未完成的备份':'Home Wi-Fi · 网络可用','wifi','network-test')}</div><div class="helper">${icon('info')}<span>切换到移动网络时，仅 Wi-Fi 任务会暂停等待。已完成的备份不会丢失。</span></div>${state.backup==='wifi'?button('模拟连接 Wi-Fi','connect-wifi','','primary','wifi'):''}</div>`;}
function renderAdvanced(){return `${toolbar('高级设置')}<div class="page-padding"><p class="helper">大多数情况下，保持默认设置就好。</p><div class="section-title">后台运行</div><div class="settings-group">${toggleRow('重启后恢复备份','恢复未完成的任务与自动检查','refresh','restart')}${row('后台运行设置','检查电池优化和自启动权限','battery','background-help')}</div><div class="section-title">上传设置</div><div class="settings-group">${row('同时上传数量',settings.concurrency,'cloud-up','concurrency')}${toggleRow('失败后自动重试','网络恢复后再试，最多 3 次','refresh','retry')}${row('连接超时',settings.timeout,'clock','timeout')}</div><div class="section-title">诊断</div><div class="settings-group">${row('检查账号连接','确认当前账号可以继续备份','shield','test-account')}${row('导出诊断报告','不包含密码或登录凭据','download','export-report')}</div></div>`;}
function renderHistory(){const entries=[...state.history,{title:'昨天的自动备份',date:'10月17日 23:16',count:12,size:'108.4 MB',status:'done'},{title:'相机文件夹备份',date:'10月16日 22:38',count:6,size:'28.8 MB',status:'done'}];return `${toolbar('任务记录')}<div class="page-padding"><h1 class="headline">每一份备份，都有记录。</h1><div class="section-title">最近任务</div>${entries.map((e,i)=>`<button class="history-item wide" data-action="history-detail" data-value="${i}"><span class="history-icon ${e.status==='error'?'error':''}">${icon(e.status==='error'?'info':'cloud-check')}</span><span class="setting-copy" style="text-align:left">${e.title}<span class="setting-subtitle">${e.date}<br>${e.count} 项 · ${e.size}</span></span><span class="badge">${e.status==='error'?'待重试':'已完成'}</span></button>`).join('')}<div class="helper">${icon('info')}<span>这里仅记录本工具执行的任务。释放手机空间不会删除这些备份记录。</span></div></div>`;}
function renderDetail(){const p=photos.find(p=>p.id===state.activePhoto);if(!p)return toolbar('照片详情');return `${toolbar('',`<button class="icon-button" data-action="photo-menu" aria-label="更多照片操作">${icon('more')}</button>`)}${photoImage(p,'class="detail-image"')}<div class="detail-meta"><div class="row-title"><h2>${p.name}</h2>${p.duration?`<span class="pill-label">视频 ${p.duration}</span>`:''}</div><span class="muted body-small">2026年10月${p.date.startsWith('今天')?'18':p.date.startsWith('昨天')?'17':'16'}日 · ${p.time}</span><div><span class="detail-state ${p.status==='uploaded'?'':'pending'}">${icon(p.status==='uploaded'?'cloud-check':'cloud-up')}${p.status==='uploaded'?'已备份到当前账号':p.status==='error'?'需要重试':'尚未备份'}</span></div><div class="metadata-row"><span>所在文件夹</span><strong>${p.folder}</strong></div><div class="metadata-row"><span>文件大小</span><strong>${prettyMB(p.size)}</strong></div><div class="metadata-row"><span>备份画质</span><strong>${p.status==='uploaded'?'原始画质':'备份时保留原始画质'}</strong></div>${p.status==='uploaded'?`<div class="helper">${icon('shield')}<span>已确认云端存在对应内容。释放手机空间前，还会再次核验。</span></div>`:''}<div class="detail-actions">${p.status==='uploaded'?button('释放空间','goto','cleanup','tonal','clean'):button('立即备份','upload-one',p.id,'primary','cloud-up')}${button('从手机删除','delete-one',p.id,'outline','trash')}</div></div>`;}
function renderCleanup(){const items=eligible();const size=items.reduce((n,p)=>n+p.size,0);return `${toolbar('释放手机空间')}<div class="page-padding"><div class="cleanup-hero"><div class="storage-art">${icon('clean')}</div><h2>${items.length?'给手机，留点新空间。':'手机已经轻装上阵。'}</h2><div class="storage-number">${size.toFixed(1)} <small>MB</small></div><p>${items.length} 个已备份项目可进一步核验<br>只清理手机原件，保留 Google 相册备份</p></div><div class="explanation">${icon('cloud-check')}<div><strong>先确认备份，再释放空间</strong><p>清理前会重新核验云端对应内容，无法确认的文件会保留。</p></div></div><div class="explanation">${icon('photo')}<div><strong>美好仍在 Google 相册中</strong><p>清理后，这些内容将不再显示在手机的本地图库中。</p></div></div><div class="explanation">${icon('shield')}<div><strong>每次清理，都由你确认</strong><p>不会在备份后自动删除。你可以先查看待清理项目。</p></div></div>${items.length?`<button class="text-button wide" data-action="cleanup-list">查看 ${items.length} 个待核验项目 ${icon('arrow')}</button><div class="bottom-action">${button('检查并释放空间','verify-cleanup','','primary','clean')}</div>`:button('返回照片','tab','photos','tonal')}<div class="note-label">本页使用示例数据，不会删除真实文件</div></div>`;}
function renderWelcome(){return `${toolbar('欢迎使用')}<div class="onboarding"><div class="onboarding-art"><img class="back-photo" src="assets/lake.jpg" alt="湖边风景示例"><img class="front-photo" src="assets/flowers.jpg" alt="鲜花示例"><span class="cloud-bubble">${icon('cloud-check')}</span></div><h1>珍贵的日常，<br>值得一份安心。</h1><p>把手机照片和视频备份到 Google 相册。保留原始画质，也为新的回忆留出空间。</p>${button('<span class="google-letter">G</span>使用 Google 账号继续','login','','primary')}<div class="helper">${icon('shield')}<span>只连接你选择的账号。备份范围和清理操作始终由你决定。</span></div><div class="dialog-demo-note">这是交互原型：下一步将模拟账号连接，不会打开真实 Google 登录页面。</div></div>`;}

function openSheet(content,name=''){clearTimeout(modalTimer);currentSheet=name;$('#overlay').innerHTML=`<section class="sheet" role="dialog" aria-modal="true" aria-label="${escapeHTML(name||'操作选项')}"><div class="sheet-handle"></div>${content}</section>`;requestAnimationFrame(()=>$('#overlay').querySelector('button,input')?.focus({preventScroll:true}));}
function sheetTitle(title){return `<div class="sheet-heading"><h2>${title}</h2><button class="icon-button" data-action="close" aria-label="关闭">${icon('close')}</button></div>`;}
function closeSheet(){clearTimeout(modalTimer);currentSheet='';$('#overlay').innerHTML='';}
function radioSheet(title,options,current,action){openSheet(`${sheetTitle(title)}${options.map(([value,label,sub])=>`<button class="radio-option" data-action="${action}" data-value="${escapeHTML(value)}"><span class="radio ${current===value?'checked':''}"></span><span class="setting-copy">${label}${sub?`<span class="setting-subtitle">${sub}</span>`:''}</span></button>`).join('')}`,title);}
function showAppearance(){openSheet(`${sheetTitle('外观')}<p>用你喜欢的颜色，看见每一份安心。</p><div class="chips option-chips">${[['light','浅色'],['dark','深色']].map(([key,name])=>`<button class="chip ${theme===key?'active':''}" data-action="theme" data-value="${key}">${icon(key==='light'?'sun':'moon')}${name}</button>`).join('')}</div><div class="section-title">主题颜色</div><div class="chips option-chips">${[['lavender','薰衣草'],['sage','鼠尾草'],['peach','暖杏']].map(([key,name])=>`<button class="chip ${palette===key?'active':''}" data-action="palette" data-value="${key}">${name}</button>`).join('')}</div><p>正式应用将支持跟随系统主题与动态配色。</p>`,'外观');}
function showAccount(){if(!state.account){navigate('welcome');return;}openSheet(`${sheetTitle('备份账号')}<div class="profile-card"><span class="avatar large">林</span><div><h2>林间</h2><p>lin.demo@example.com</p></div></div><div class="hint-card">${icon('cloud-check')}<p>连接正常 · 凭据将自动续期<br>当前账号用于备份与备份状态核验。</p></div>${button('切换账号','switch-account','','tonal')}${button('退出账号','logout','','text')}<p>切换后会重新核对备份状态，不会沿用其他账号的清理资格。</p>`,'备份账号');}
function simulateLogin(){
  if(state.account){toast('当前账号连接正常');return;}
  openSheet(`${sheetTitle('连接 Google 账号')}<p>选择演示账号，模拟登录和授权完成后的体验。</p><button class="profile-card wide" data-action="confirm-login"><span class="avatar large">林</span><span class="setting-copy"><h2>林间</h2><p>lin.demo@example.com</p></span>${icon('arrow')}</button><div class="dialog-demo-note">示例账号 · 不收集账号、密码或登录凭据</div>`,'模拟登录');
}
function confirmLogin(){openSheet(`${sheetTitle('正在连接')}<div class="loading-ring"></div><p style="text-align:center">正在确认账号，准备你的备份空间…</p>`,'连接中');modalTimer=setTimeout(()=>{state.account=true;state.scenario='pending';state.backup='pending';state.page='folders';state.stack=['backup'];closeSheet();render();toast('演示账号已连接，选择想备份的文件夹吧');},1000);}
function startUpload(ids){
  if(!state.account){simulateLogin();return;}
  if(state.backup==='uploading'||state.backup==='paused'){navigate('backup');toast('已有备份任务，先完成当前任务');return;}
  if(state.backup==='wifi'&&settings.wifi){navigate('backup');toast('等待 Wi-Fi 连接后继续');return;}
  const queue=ids?photos.filter(p=>!p.cleaned&&p.status!=='uploaded'&&ids.includes(p.id)):backupCandidates();
  if(!queue.length){toast(ids?'所选内容已经备份，无需重复上传':'已选文件夹中没有可备份的新内容');return;}
  if(!settings.videos&&queue.every(p=>p.duration)){toast('视频备份已关闭，可在设置中开启');return;}
  state.uploadQueue=queue.filter(p=>settings.videos||!p.duration).map(p=>p.id);state.currentUpload={count:state.uploadQueue.length,size:queue.reduce((n,p)=>n+p.size,0)};
  state.backup='uploading';state.progress=0;state.selecting=false;state.selected.clear();state.scenario='pending';state.page='backup';state.stack=[];closeSheet();render();tickUpload();
}
function tickUpload(){clearInterval(uploadTimer);uploadTimer=setInterval(()=>{
  if(state.backup!=='uploading')return;
  state.progress=Math.min(100,state.progress+7+Math.random()*5);
  const completeCount=Math.floor(state.uploadQueue.length*state.progress/100);
  state.uploadQueue.slice(0,completeCount).forEach(id=>{photos.find(p=>p.id===id).status='uploaded';});
  if(state.progress>=100){
    clearInterval(uploadTimer);state.uploadQueue.forEach(id=>{photos.find(p=>p.id===id).status='uploaded';});
    state.history.unshift({title:'手动备份',date:'今天 09:41',count:state.currentUpload.count,size:prettyMB(state.currentUpload.size),status:'done'});
    state.backup=backupCandidates().length?'pending':'complete';state.scenario=backupCandidates().length?'pending':'complete';state.uploadQueue=[];toast('备份完成，珍贵的日常已妥善保存');
  }render();
},650);}
function scenario(name){clearInterval(uploadTimer);closeSheet();photos=makePhotos();state.selected.clear();state.selecting=false;state.filter='all';state.mediaType='all';state.dateFrom='';state.dateTo='';state.stack=[];state.progress=0;state.uploadQueue=[];state.cleaned=0;state.account=name!=='signedout';state.scenario=name;state.backup=name==='signedout'?'pending':name;state.history=[];
  if(name==='complete')photos.forEach(p=>p.status='uploaded');
  if(name==='error')photos.filter(p=>p.status==='pending').forEach((p,i)=>p.status=i<2?'error':'uploaded');
  state.page=name==='signedout'?'welcome':name==='pending'?'photos':'backup';render();$('#screen').scrollTop=0;
}
function deleteConfirmation(ids){const items=photos.filter(p=>ids.includes(p.id)&&!p.cleaned);const unbacked=items.some(p=>p.status!=='uploaded');if(!items.length){toast('请先选择照片');return;}
  openSheet(`${sheetTitle('从手机删除？')}<p>将从手机移除 ${items.length} 个项目。${unbacked?'<strong class="danger">其中有尚未备份的内容，删除后可能无法找回。</strong>':'Google 相册中的已备份内容会保留。'}</p><div class="inline-photos">${items.slice(0,5).map(p=>photoImage(p)).join('')}</div><div class="dialog-demo-note">仅模拟删除，不访问或改变手机真实文件。</div><div class="sheet-actions">${button('保留','close','','text')}${button('确认从手机删除','confirm-delete',ids.join(','),'primary')}</div>`,'删除确认');
}
function verifyCleanup(){if(!state.account){simulateLogin();return;}if(!eligible().length){toast('暂时没有可清理的备份文件');return;}openSheet(`${sheetTitle('再确认一遍，才放心')}<div class="loading-ring"></div><p style="text-align:center">正在核验云端备份和本地文件…<br>无法确认的项目会留在手机上。</p>`,'核验备份');modalTimer=setTimeout(()=>{const items=eligible();openSheet(`${sheetTitle('可以安心释放空间')}<div class="hint-card">${icon('shield')}<p>${items.length} 个项目已完成演示核验<br>可释放 ${prettyMB(items.reduce((n,p)=>n+p.size,0))}</p></div><p>接下来将模拟系统删除确认。只移除手机上的原件，Google 相册中的备份会保留。</p><p>清理后，这些内容可能不再显示在其他本地图库中。</p><div class="sheet-actions">${button('暂不清理','close','','text')}${button('继续','system-delete','','primary')}</div>`,'清理确认');},1300);}
function systemDelete(){openSheet(`${sheetTitle('允许移除手机上的文件？')}<p>云备份请求移除 ${eligible().length} 个已核验项目。</p><div class="inline-photos">${eligible().slice(0,5).map(p=>photoImage(p)).join('')}</div><div class="dialog-demo-note">Android 系统确认的模拟界面，不会触发真实删除。</div><div class="sheet-actions">${button('取消','close','','text')}${button('允许清理','finish-cleanup','','primary')}</div>`,'模拟系统确认');}
function finishCleanup(){const items=eligible();const size=items.reduce((n,p)=>n+p.size,0);items.forEach(p=>p.cleaned=true);state.cleaned+=size;openSheet(`<div class="completion"><div class="success-medallion">${icon('check')}</div><h2>轻一点，装下更多美好。</h2><p>已模拟释放 <strong>${prettyMB(size)}</strong> 空间<br>${items.length} 个项目的云端备份仍然保留。</p>${button('完成','cleanup-done','','primary')}<p>需要查看已清理内容时，请前往 Google 相册。</p></div>`,'清理完成');render();}
function openDemoMenu(){openSheet(`${sheetTitle('演示控制')}<p>切换外观与场景，体验应用的不同状态。</p><div class="chips option-chips">${button('浅色','theme','light','tonal')}${button('深色','theme','dark','tonal')}</div><div class="section-title">演示场景</div>${[['pending','有照片待备份'],['complete','全部备份完成'],['wifi','等待 Wi-Fi'],['error','有项目需要处理'],['signedout','首次使用 / 未登录']].map(([value,label])=>row(label,'','cloud','scenario',value)).join('')}<div class="dialog-demo-note">所有数据和文件操作均为前端模拟。</div>${button('重置演示','reset','','text')}`,'演示控制');}

let holdTimer=null,holdStart=null,suppressPhotoClickUntil=0;
document.addEventListener('pointerdown',event=>{
  const cell=event.target.closest('.photo-cell[data-action="photo"]');
  if(!cell||state.selecting||event.button>0)return;
  holdStart={x:event.clientX,y:event.clientY};
  holdTimer=setTimeout(()=>{state.selecting=true;state.selected.add(Number(cell.dataset.value));suppressPhotoClickUntil=Date.now()+800;render();},450);
});
document.addEventListener('pointermove',event=>{if(holdStart&&Math.hypot(event.clientX-holdStart.x,event.clientY-holdStart.y)>8){clearTimeout(holdTimer);holdStart=null;}});
for(const eventName of ['pointerup','pointercancel'])document.addEventListener(eventName,()=>{clearTimeout(holdTimer);holdStart=null;});
document.addEventListener('click',(event)=>{
  const target=event.target.closest('[data-action]');if(!target||target.disabled)return;
  const action=target.dataset.action;const value=target.dataset.value||'';
  if(action==='photo'&&Date.now()<suppressPhotoClickUntil){suppressPhotoClickUntil=0;return;}
  switch(action){
    case 'goto':navigate(value);break;
    case 'tab':switchTab(value);break;
    case 'back':goBack();break;
    case 'close':closeSheet();break;
    case 'theme':theme=value;applyAppearance();if(currentSheet==='外观')showAppearance();render();break;
    case 'palette':palette=value;applyAppearance();if(currentSheet==='外观')showAppearance();render();break;
    case 'appearance':showAppearance();break;
    case 'scenario':scenario(value);break;
    case 'reset':settings=initialSettings();scenario('pending');toast('演示已重置');break;
    case 'demo-menu':openDemoMenu();break;
    case 'filter':state.filter=value;state.selecting=false;state.selected.clear();render();break;
    case 'photo':{const id=Number(value);if(state.selecting){state.selected.has(id)?state.selected.delete(id):state.selected.add(id);render();}else{state.activePhoto=id;navigate('detail');}break;}
    case 'select-mode':state.selecting=true;visiblePhotos().filter(p=>p.date===value&&(state.filter==='all'||(state.filter==='uploaded'?p.status==='uploaded':p.status!=='uploaded'))).forEach(p=>state.selected.add(p.id));render();break;
    case 'end-selection':state.selecting=false;state.selected.clear();render();break;
    case 'photo-filter':openSheet(`${sheetTitle('筛选照片')}<div class="section-title">内容类型</div><div class="chips option-chips">${[['all','全部'],['photo','照片'],['video','视频']].map(([key,label])=>`<button class="chip ${state.mediaType===key?'active':''}" data-action="media-type" data-value="${key}">${label}</button>`).join('')}</div><div class="section-title">拍摄日期</div><div class="date-controls"><label class="body-small">开始日期<input class="text-field" type="date" id="date-from" value="${state.dateFrom}" aria-label="开始日期"></label><label class="body-small">结束日期<input class="text-field" type="date" id="date-to" value="${state.dateTo}" aria-label="结束日期"></label></div>${button('应用筛选','apply-date','','primary')}${button('清除筛选','clear-filter','','text')}`,'筛选照片');break;
    case 'media-type':state.mediaType=value;document.querySelectorAll('[data-action="media-type"]').forEach(b=>b.classList.toggle('active',b.dataset.value===value));break;
    case 'apply-date':{const from=$('#date-from').value,to=$('#date-to').value;if(from&&to&&from>to){toast('开始日期不能晚于结束日期');break;}state.dateFrom=from;state.dateTo=to;closeSheet();render();break;}
    case 'clear-filter':state.mediaType='all';state.dateFrom='';state.dateTo='';closeSheet();render();break;
    case 'login':simulateLogin();break;
    case 'confirm-login':confirmLogin();break;
    case 'account':showAccount();break;
    case 'switch-account':case 'logout':clearInterval(uploadTimer);state.account=false;state.backup='pending';photos.forEach(p=>{if(!p.cleaned)p.status='pending';});state.selected.clear();state.history=[];state.page='welcome';state.stack=[];state.scenario='signedout';closeSheet();render();toast(action==='logout'?'已模拟退出，云端内容不受影响':'先退出当前演示账号，再连接账号');break;
    case 'upload':startUpload();break;
    case 'upload-one':startUpload([Number(value)]);break;
    case 'upload-selected':if(!state.selected.size)toast('请选择需要备份的照片');else startUpload([...state.selected]);break;
    case 'pause':clearInterval(uploadTimer);state.backup='paused';render();toast('已暂停，已完成的备份会保留');break;
    case 'resume':state.backup='uploading';render();tickUpload();break;
    case 'check-new':openSheet(`${sheetTitle('检查新照片')}<div class="loading-ring"></div><p style="text-align:center">检查已选择的备份文件夹…</p>`,'检查新照片');modalTimer=setTimeout(()=>{closeSheet();toast('没有发现新内容，备份已是最新');},850);break;
    case 'toggle':{settings[value]=!settings[value];if(value==='wifi'&&!settings.wifi)settings.mobile='仅照片';if(value==='wifi'&&settings.wifi)settings.mobile='不使用移动流量';render();if(value==='auto')toast(settings.auto?'自动备份已开启':'自动备份已关闭，仍可手动备份');break;}
    case 'folder-toggle':if(value==='folderSD'&&!settings.folderSD){openSheet(`${sheetTitle('访问 SD 卡照片')}<p>选择你想备份的 SD 卡文件夹。授权后，我们才能检查和备份其中的内容。</p><div class="numbered-step"><span>1</span>打开系统文件夹选择器</div><div class="numbered-step"><span>2</span>选择 SD 卡上的照片文件夹</div><div class="numbered-step"><span>3</span>点击“使用此文件夹”并允许访问</div><div class="dialog-demo-note">这里将模拟文件夹授权，不请求真实文件访问权限。</div>${button('选择文件夹','authorize-sd','','primary','folder')}`,'SD 卡访问权限');}else{settings[value]=!settings[value];render();}break;
    case 'authorize-sd':settings.folderSD=true;closeSheet();render();toast('已模拟授权：SD 卡 / 照片');break;
    case 'add-folder':openSheet(`${sheetTitle('添加备份文件夹')}<p>正式应用会打开系统文件夹选择器，支持手机存储与 SD 卡。本原型提供一个示例文件夹。</p>${row('旅行照片','SD 卡 / Pictures / Travel','folder','authorize-sd')}`,'添加文件夹');break;
    case 'quality':openSheet(`${sheetTitle('备份画质')}<div class="hint-card">${icon('photo')}<p><strong>原始画质</strong><br>保留照片和视频原本的清晰度。</p></div><p>当前采用原始画质备份方式。免配额结果以 Google 相册实际处理为准。</p>${button('知道了','close','','primary')}`,'备份画质');break;
    case 'album':radioSheet('相册归类',[['不分类','不分类','仅备份到 Google 相册'],['按来源文件夹','按来源文件夹','例如“相机”“截图”'],['自定义相册','自定义相册','为这份备份起个名字'],['加入已有相册','加入已有相册','选择 Google 相册中的相册']],settings.album,'set-album');break;
    case 'set-album':if(value==='自定义相册'){openSheet(`${sheetTitle('自定义相册')}<label class="body-small" for="album-name">相册名称</label><input id="album-name" class="text-field" maxlength="60" placeholder="例如：我的日常" autocomplete="off">${button('保存','save-album','','primary')}`,'自定义相册');}else if(value==='加入已有相册'){radioSheet('选择已有相册',[['周末散步','周末散步','示例相册 · 24 项'],['旅行手记','旅行手记','示例相册 · 86 项'],['生活碎片','生活碎片','示例相册 · 42 项']],'','existing-album');}else{settings.album=value;closeSheet();render();}break;
    case 'save-album':{const name=$('#album-name').value.trim();if(!name){toast('给相册起个名字吧');break;}settings.album=`自定义：${name}`;closeSheet();render();toast('相册设置已保存');break;}
    case 'existing-album':settings.album=`加入：${value}`;closeSheet();render();break;
    case 'schedule-time':openSheet(`${sheetTitle('备份时间')}<p>每天在这个时间附近，检查并备份新增内容。</p><input id="backup-time" type="time" class="schedule-input" value="${settings.time}" aria-label="每日备份时间"><p>实际执行可能因网络、充电条件和系统省电设置稍有延后。</p>${button('保存','save-time','','primary')}`,'备份时间');break;
    case 'save-time':if($('#backup-time').value){settings.time=$('#backup-time').value;closeSheet();render();toast('定时备份设置已保存');}break;
    case 'mobile-data':radioSheet('移动流量使用量',[['不使用移动流量','不使用移动流量','连接 Wi-Fi 后再备份'],['仅照片','仅照片','视频等待 Wi-Fi 后上传'],['照片和视频','照片和视频','可能产生较多流量消耗']],settings.mobile,'set-mobile');break;
    case 'set-mobile':settings.mobile=value;settings.wifi=value==='不使用移动流量';closeSheet();render();break;
    case 'network-test':toast(state.backup==='wifi'?'尚未连接 Wi-Fi，备份会等待':'网络连接正常，可以继续备份');break;
    case 'connect-wifi':state.backup='pending';state.scenario='pending';render();toast('已模拟连接 Wi-Fi');if(settings.auto)startUpload();break;
    case 'concurrency':radioSheet('同时上传数量',[['自动','自动（推荐）','根据使用情况保持流畅'],['1 个','1 个','更少占用网络'],['3 个','3 个','更快处理多份内容'] ],settings.concurrency,'set-concurrency');break;
    case 'set-concurrency':settings.concurrency=value;closeSheet();render();break;
    case 'timeout':radioSheet('连接超时',[['30 秒','30 秒'],['60 秒','60 秒（默认）'],['120 秒','120 秒']],settings.timeout,'set-timeout');break;
    case 'set-timeout':settings.timeout=value;closeSheet();render();toast(`已模拟设置连接超时：${value}`);break;
    case 'test-account':if(!state.account)simulateLogin();else toast('演示账号连接正常，自动续期可用');break;
    case 'background-help':openSheet(`${sheetTitle('让备份在后台继续')}<p>不同手机的设置名称可能略有不同。正式应用会提供适合当前设备的设置入口。</p><div class="numbered-step"><span>1</span>允许应用发送备份进度通知</div><div class="numbered-step"><span>2</span>允许后台运行，按需开启自启动</div><div class="numbered-step"><span>3</span>检查电池优化是否限制了备份</div>${button('知道了','close','','primary')}`,'后台运行');break;
    case 'export-report':{const report={prototype:true,note:'示例报告，不包含真实用户数据',accountConnected:state.account,backupState:state.backup,completed:uploaded().length,pending:pending().length};const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download='cloud-backup-demo-report.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);toast('示例诊断报告已导出');break;}
    case 'cleanup-list':{const items=eligible();openSheet(`${sheetTitle('待核验项目')}<p>以下 ${items.length} 项已备份。继续清理前，还会独立核验云端内容。</p><div class="photo-grid">${items.map(p=>`<div class="photo-cell">${photoImage(p)}<span class="photo-state uploaded">${icon('cloud-check')}</span></div>`).join('')}</div><div class="bottom-action">${button('开始核验','verify-cleanup','','primary')}</div>`,'待核验项目');break;}
    case 'verify-cleanup':verifyCleanup();break;
    case 'system-delete':systemDelete();break;
    case 'finish-cleanup':finishCleanup();break;
    case 'cleanup-done':closeSheet();render();toast('清理完成，备份记录已保留');break;
    case 'delete-one':deleteConfirmation([Number(value)]);break;
    case 'delete-selected':deleteConfirmation([...state.selected]);break;
    case 'confirm-delete':value.split(',').map(Number).forEach(id=>{const p=photos.find(p=>p.id===id);if(p)p.cleaned=true;});state.selected.clear();state.selecting=false;closeSheet();state.page='photos';state.stack=[];render();toast('已模拟从手机删除，云端内容不受影响');break;
    case 'photo-menu':openSheet(`${sheetTitle('照片操作')}${row('备份这张照片','已备份的内容不会重复上传','cloud-up','upload-one',state.activePhoto)}${row('从手机删除','只移除本地文件','trash','delete-one',state.activePhoto)}`,'照片操作');break;
    case 'history-detail':openSheet(`${sheetTitle('任务详情')}<div class="hint-card">${icon('cloud-check')}<p>任务已完成<br>已上传项目保留原始画质。</p></div><div class="metadata-row"><span>备份账号</span><strong>当前演示账号</strong></div><div class="metadata-row"><span>来源</span><strong>相机文件夹</strong></div><div class="metadata-row"><span>重复内容</span><strong>自动跳过</strong></div><p>示例记录用于展示任务详情结构。</p>${button('完成','close','','primary')}`,'任务详情');break;
    case 'help':openSheet(`${sheetTitle('备份遇到问题？')}${row('等待 Wi-Fi','检查当前网络与流量设置','wifi','goto','network')}${row('后台停止了备份','检查省电限制与自启动设置','battery','background-help')}${row('账号需要重新登录','重新连接当前 Google 账号','user','account')}<p>已完成的备份记录会保留，不必从头开始。</p>`,'备份帮助');break;
    case 'about':openSheet(`${sheetTitle('关于云备份')}<div class="cloud-art">${icon('cloud-check')}</div><p style="text-align:center">为珍贵的日常，留一份安心。<br>Android 交互原型 · Material 3 Expressive</p><div class="dialog-demo-note">纯前端模拟，无 Google API、真实登录、后台任务或文件删除。示例照片来自 Unsplash，已保存在本地。</div>`,'关于');break;
  }
});
$('#overlay').addEventListener('click',event=>{if(event.target===$('#overlay'))closeSheet();});
document.addEventListener('keydown',event=>{
  if(event.key==='Escape'){if($('#overlay').children.length)closeSheet();else if(state.selecting){state.selecting=false;state.selected.clear();render();}else if(state.stack.length)goBack();}
  if(event.key==='Tab'&&$('#overlay').children.length){const controls=[...$('#overlay').querySelectorAll('button:not(:disabled),input,select,[tabindex="0"]')];if(!controls.length)return;const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}
});
applyAppearance();render();
