package com.nfriendcl.app

/**
 * 네이버 모바일 블로그 소셜 활동 자동화 스크립트.
 *
 * Kotlin 이 호출하는 진입점(window.*):
 *   __NF_collectFeed(wanted,maxRounds,token): 이웃새글 피드에서 글 URL 목록 수집 → NF.onUrls(json)
 *   __NF_likeAndComment(payload)       : 열린 글에서 공감(하트) + 댓글 등록 → NF.onActed(json)
 *   __NF_acceptAll(token)              : 현재 화면의 '수락' 버튼 전부 클릭 → NF.onAccept(json)
 *   __NF_collectBloggers(myId,wanted,maxRounds,token): 검색결과 수집 → NF.onBloggers(json)
 *   __NF_addNeighbor(payload)          : 블로거 홈에서 (서로)이웃 신청 → NF.onAdd(json)
 *   __NF_loginCheck()                  : 현재 페이지 로그인 여부 → NF.onLogin(bool)
 *
 * 콜백(Kotlin NF 브리지): log(s), onUrls(s), onActed(s), onAccept(s),
 *                         onBloggers(s), onAdd(s), onLogin(b), onNeedLogin()
 *
 * 주의: Kotlin raw string 이라 JS 안에서 '$' 문자는 절대 쓰지 않는다(템플릿 충돌 방지).
 * 셀렉터/타이밍은 네이버 업데이트 시 기기에서 조정이 필요할 수 있다(집안 앱들과 동일 성격).
 */
object AutomationJs {
    const val SCRIPT = """
(function(){
  if (window.__NF_DEFINED) return;
  window.__NF_DEFINED = true;

  function log(m){ try{ NF.log(''+m); }catch(e){} }
  function edoc(){ return document; }
  function txt(el){ try{ return (el.textContent||''); }catch(e){ return ''; } }
  function attr(el,n){ try{ return (el.getAttribute(n)||''); }catch(e){ return ''; } }
  function norm(s){ return (''+s).replace(/\s/g,''); }
  function fire(el,type){
    try{ var IE = window.InputEvent || window.Event; el.dispatchEvent(new IE(type,{bubbles:true})); }
    catch(e){ try{ var ev=document.createEvent('Event'); ev.initEvent(type,true,true); el.dispatchEvent(ev); }catch(e2){} }
  }

  function looksLikeLogin(){
    try{
      var h = (location.host||'');
      if (h.indexOf('nid.naver.com')>=0) return true;
      if (document.querySelector('#id, #pw, input[name="id"], input[name="pw"], #login_form')) return true;
    }catch(e){}
    return false;
  }

  function positiveInt(v, fallback, max){
    var n = parseInt(v, 10);
    if (!isFinite(n) || n < 1) n = fallback;
    return Math.min(n, max);
  }

  function pageHeight(){
    try{
      var de=document.documentElement, b=document.body, s=document.scrollingElement;
      return Math.max(
        de ? de.scrollHeight : 0,
        b ? b.scrollHeight : 0,
        s ? s.scrollHeight : 0
      );
    }catch(e){ return 0; }
  }

  function scrollTop(){
    try{
      var s=document.scrollingElement;
      return Math.max(window.pageYOffset||0, s ? s.scrollTop : 0);
    }catch(e){ return 0; }
  }

  function nearBottom(){
    var vh=window.innerHeight||document.documentElement.clientHeight||600;
    return scrollTop()+vh >= pageHeight()-96;
  }

  function visible(el){
    try{
      var r=el.getBoundingClientRect(), st=window.getComputedStyle(el);
      return r.width>0 && r.height>0 && st.display!=='none' && st.visibility!=='hidden';
    }catch(e){ return true; }
  }

  function clickMore(){
    var els=edoc().querySelectorAll('button,a,[role=button]');
    for (var i=0;i<els.length;i++){
      if (!visible(els[i]) || els[i].disabled) continue;
      var t=norm(txt(els[i]));
      var a=norm(attr(els[i],'aria-label')+' '+attr(els[i],'title'));
      if (t==='더보기' || t==='글더보기' || t==='검색결과더보기' ||
          t==='이웃새글더보기' || a==='더보기' || a.indexOf('검색결과더보기')>=0){
        try{ els[i].click(); log('더보기 클릭'); return true; }catch(e){}
      }
    }
    return false;
  }

  function progressiveScan(wantedArg, roundsArg, harvest, size, done){
    var wanted=positiveInt(wantedArg, 40, 2000);
    var maxRounds=positiveInt(roundsArg, 40, 200);
    var rounds=0, stable=0, lastCount=-1, lastHeight=-1, ended=false;

    function finish(reason){
      if (ended) return;
      ended=true;
      harvest();
      log('스캔 종료 '+reason+' · '+size()+'개 · '+rounds+'회');
      done();
    }

    function waitForChange(beforeCount, beforeHeight, longWait){
      var tries=0, limit=longWait ? 9 : 2;
      function check(){
        if (ended) return;
        harvest();
        var changed=size()>beforeCount || pageHeight()>beforeHeight;
        tries++;
        if (changed){ setTimeout(scan, 450); return; }
        if (tries>=limit){ scan(); return; }
        setTimeout(check, 250);
      }
      setTimeout(check, 250);
    }

    function scan(){
      if (ended) return;
      harvest();
      var count=size(), height=pageHeight(), bottom=nearBottom();
      var grew=lastCount<0 || count>lastCount || height>lastHeight;
      if (lastCount>=0 && bottom) stable=grew ? 0 : stable+1;
      else if (grew || !bottom) stable=0;

      if (count>=wanted){ finish('목표'); return; }
      if (rounds>=maxRounds){ finish('최대회차'); return; }
      if (stable>=3){ finish('변화없음'); return; }

      lastCount=count; lastHeight=height;
      var clicked=clickMore();
      var y=scrollTop(), vh=window.innerHeight||document.documentElement.clientHeight||600;
      var step=Math.max(560, Math.floor(vh*0.9));
      var next=Math.min(height, y+step);
      if (height-next < step) next=height;
      try{ window.scrollTo({top:next,behavior:'smooth'}); }
      catch(e){ try{ window.scrollTo(0,next); }catch(e2){} }
      rounds++;
      waitForChange(count, height, clicked || next>=height-96);
    }

    scan();
  }

  function clickByText(d, names){
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var t = norm(txt(els[i]));
      for (var n=0;n<names.length;n++){ if (t===names[n]){ try{ els[i].click(); return true; }catch(e){} } }
    }
    return false;
  }

  // ---------- 1) 이웃새글 피드: 글 URL 수집 ----------
  function canonicalPost(h){
    if (!h) return null;
    try{
      var u=new URL(h, location.href);
      var host=(u.hostname||'').toLowerCase();
      if (host!=='naver.com' && host.slice(-10)!=='.naver.com') return null;
      var blogId=u.searchParams.get('blogId')||'';
      var logNo=u.searchParams.get('logNo')||'';
      if (!blogId || logNo.length<6 || /\D/.test(logNo)){
        var m=((u.pathname||'')+'/').match(/^\/([a-zA-Z0-9_-]+)\/(\d{6,})\//);
        if (m){ blogId=m[1]; logNo=m[2]; }
      }
      if (!blogId || /[^a-zA-Z0-9_-]/.test(blogId) || logNo.length<6 || /\D/.test(logNo)) return null;
      blogId=blogId.toLowerCase();
      return {key:blogId+':'+logNo, url:'https://m.blog.naver.com/'+blogId+'/'+logNo};
    }catch(e){ return null; }
  }

  window.__NF_collectFeed = function(wanted,maxRounds,token){
    var urls=[], seen={};
    function harvest(){
      var as=edoc().querySelectorAll('a[href]');
      for (var i=0;i<as.length;i++){
        var p=canonicalPost(as[i].href);
        if (p && !seen[p.key]){ seen[p.key]=1; urls.push(p.url); }
      }
    }
    progressiveScan(wanted,maxRounds,harvest,function(){ return urls.length; },function(){
      log('피드 글 '+urls.length+'개 수집');
      var out={items:urls};
      if (typeof token!=='undefined') out.token=token;
      try{ NF.onUrls(JSON.stringify(out)); }catch(e){}
    });
  };

  // ---------- 2) 글에서 공감 + 댓글 ----------
  function isOn(el){
    var n=el;
    for (var i=0;i<4 && n; i++){
      var c = ''+(n.className||'');
      if (c.indexOf('on')>=0 || c.indexOf('_selected')>=0) return true;
      if (n.getAttribute && n.getAttribute('aria-pressed')==='true') return true;
      n = n.parentNode;
    }
    return false;
  }
  function clickLike(d){
    var cand = d.querySelector('a.u_likeit_list_btn, .u_likeit_list_btn, .btn_sympathy, a._sympathyButton');
    if (cand){
      if (isOn(cand)) return 'already';
      try{ cand.click(); return 'liked'; }catch(e){}
    }
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var cl = ''+(els[i].className||'');
      var hay = norm(txt(els[i])+' '+attr(els[i],'aria-label')+' '+attr(els[i],'title')+' '+cl);
      if (hay.indexOf('공감')>=0 || hay.indexOf('좋아요')>=0 ||
          cl.indexOf('like')>=0 || cl.indexOf('sympath')>=0){
        if (isOn(els[i])) return 'already';
        try{ els[i].click(); return 'liked'; }catch(e){}
      }
    }
    return 'noheart';
  }
  function commentField(d){
    var sels = ['textarea#naverComment','.u_cbox_write textarea','.u_cbox_inbox textarea',
      'textarea[placeholder*="댓글"]','textarea[name*="comment"]','textarea[id*="comment"]',
      '.u_cbox_text','div.u_cbox_text','[contenteditable="true"][class*="comment"]',
      '[contenteditable="true"][class*="cbox"]','textarea'];
    for (var i=0;i<sels.length;i++){ var e=d.querySelector(sels[i]); if (e) return e; }
    return null;
  }
  function openComment(d){
    if (commentField(d)) return commentField(d);
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var hay = norm(txt(els[i])+' '+attr(els[i],'aria-label')+' '+(els[i].className||''));
      if (hay.indexOf('댓글')>=0){ try{ els[i].click(); }catch(e){} }
    }
    return commentField(d);
  }
  function typeInto(d, el, text){
    try{ el.focus(); }catch(e){}
    var tag = (el.tagName||'').toLowerCase();
    if (tag==='textarea' || tag==='input'){
      el.value = text; fire(el,'input'); fire(el,'keyup'); fire(el,'change');
    } else {
      el.textContent = text; fire(el,'input');
    }
  }
  function submitComment(d){
    var up = d.querySelector('.u_cbox_btn_upload, button.u_cbox_btn_upload');
    if (up){ try{ up.click(); return true; }catch(e){} }
    var els = d.querySelectorAll('button,a,[role=button]');
    for (var i=0;i<els.length;i++){
      var t = norm(txt(els[i]));
      if (t==='등록' || t==='댓글등록'){ try{ els[i].click(); return true; }catch(e){} }
    }
    return false;
  }
  window.__NF_likeAndComment = function(payload){
    if (typeof payload==='string'){ try{ payload=JSON.parse(payload); }catch(e){ payload={}; } }
    if (!payload || typeof payload!=='object') payload={};
    var doLike = payload.like!==false, doComment = payload.comment!==false, text = payload.text||'';
    var result = {liked:false, commented:false, msg:''};
    if (Object.prototype.hasOwnProperty.call(payload,'token')) result.token=payload.token;
    var finished=false;
    var d = edoc();
    if (looksLikeLogin()){ result.needLogin=true; result.msg='login'; finish(); return; }
    if (doLike){ var lk = clickLike(d); result.liked = (lk==='liked'||lk==='already'); result.msg += 'like:'+lk+' '; }
    if (!doComment){ finish(); return; }
    openComment(d);
    setTimeout(function(){
      var f = commentField(edoc());
      if (!f){ result.msg += 'no-field'; finish(); return; }
      typeInto(edoc(), f, text);
      setTimeout(function(){
        result.commented = submitComment(edoc());
        result.msg += 'submit:'+result.commented;
        setTimeout(finish, 700);
      }, 800);
    }, 1000);
    function finish(){
      if (finished) return;
      finished=true;
      log('행동 '+JSON.stringify(result));
      try{ NF.onActed(JSON.stringify(result)); }catch(e){}
    }
  };

  // ---------- 3) 서로이웃 신청 전체 수락 (Batch) ----------
  window.__NF_acceptAll = function(token){
    function reportAccept(result){
      if (typeof token!=='undefined') result.token=token;
      try{ NF.onAccept(JSON.stringify(result)); }catch(e){}
    }
    function getAdminDoc(){
      try {
        var f = document.getElementById('mainFrame');
        if (f && f.contentDocument) return f.contentDocument;
      } catch(e){}
      return document;
    }
    
    // 1) 왼쪽 메뉴 '서로이웃 신청' 클릭 (PC 관리 화면일 때만)
    // 왼쪽 메뉴는 보통 iframe 밖에 있음
    var menu = document.getElementById('menu_buddy_request') || 
               document.querySelector('a[href*="BuddyRequestReceipt"]');
               
    if (menu && location.href.indexOf('BuddyRequestReceipt') < 0) {
      log('서로이웃 신청 메뉴 이동');
      menu.click();
      return; 
    }

    var adoc = getAdminDoc();

    // 2) 전체 선택 체크박스 클릭
    var allCheck = adoc.getElementById('all_check') || adoc.querySelector('input[name="all_check"]');
    if (allCheck) {
      log('전체 선택 체크박스 클릭');
      if (!allCheck.checked) { allCheck.click(); }
      
      // 3) 바로 위 수락 버튼 클릭
      setTimeout(function(){
        var acceptBtn = adoc.querySelector('a.btn_ok, #content a.btn_type1') || 
                        Array.from(adoc.querySelectorAll('a, button')).find(function(el){
                          return norm(txt(el)) === '수락';
                        });
        if (acceptBtn) {
          log('수락 버튼 클릭');
          acceptBtn.click();
          setTimeout(function(){
            // 확인 팝업 처리
            clickByText(adoc, ['확인','예','수락']);
            setTimeout(function(){
              reportAccept({count:1, done:true, msg:'done'});
            }, 1000);
          }, 1000);
        } else {
          log('수락 버튼을 찾을 수 없음');
          reportAccept({count:0, done:true, msg:'none'});
        }
      }, 1000);
    } else {
      log('체크박스를 찾을 수 없음 (서로이웃 신청 목록이 아닐 수 있음)');
      reportAccept({count:0, done:true, msg:'none'});
    }
  };

  // ---------- 4) 검색결과에서 블로거 아이디 수집 ----------
  function extractBlogId(href){
    if (!href) return null;
    try{
      var u=new URL(href, location.href);
      var q=u.searchParams.get('blogId');
      if (q && !/[^a-zA-Z0-9_-]/.test(q)) return q;
      var host=(u.hostname||'').toLowerCase();
      if (host==='blog.naver.com' || host==='m.blog.naver.com'){
        var p=(u.pathname||'').match(/^\/([a-zA-Z0-9_-]+)/);
        if (p) return p[1];
      }
    }catch(e){}
    var decoded=href;
    try{ decoded=decodeURIComponent(href); }catch(e2){}
    var m=decoded.match(/[?&]blogId=([a-zA-Z0-9_-]+)/); if (m) return m[1];
    m=decoded.match(/blog\.naver\.com\/([a-zA-Z0-9_-]+)/); if (m) return m[1];
    return null;
  }
  window.__NF_collectBloggers = function(myId,wanted,maxRounds,token){
    var bad = {postview:1,postlist:1,buddylist:1,news:1,feedlist:1,recommendation:1,
      rabbitwrite:1,guestbook:1,prologue:1,rss:1,themepost:1,section:1,
      recommend:1,postthumbnailalbumview:1,ediaryview:1};
    var mine=(''+(myId||'')).toLowerCase();
    var ids=[], seen={};
    function harvest(){
      var as=edoc().querySelectorAll('a[href]');
      for (var i=0;i<as.length;i++){
        var id = extractBlogId(as[i].href);
        if (!id) continue;
        id=id.toLowerCase();
        if (id===mine) continue;
        if (bad[id]) continue;
        if (!/[\D]/.test(id)) continue;   // 숫자만이면(logNo 등) 제외
        if (seen[id]) continue;
        seen[id]=1; ids.push(id);
      }
    }
    progressiveScan(wanted,maxRounds,harvest,function(){ return ids.length; },function(){
      log('블로거 '+ids.length+'명 수집');
      var out={items:ids};
      if (typeof token!=='undefined') out.token=token;
      try{ NF.onBloggers(JSON.stringify(out)); }catch(e){}
    });
  };

  // ---------- 4-b) 이웃 목록에서 친구 블로거 아이디 수집 (소셜활동 보충용) ----------
  window.__NF_collectBuddies = function(myId,wanted,maxRounds,token){
    var bad = {postview:1,postlist:1,buddylist:1,news:1,feedlist:1,recommendation:1,
      rabbitwrite:1,guestbook:1,prologue:1,rss:1,themepost:1,section:1,
      recommend:1,postthumbnailalbumview:1,ediaryview:1,buddyaddform:1,
      buddylistmanage:1,buddyrequestreceipt:1,neighbor:1};
    var mine=(''+(myId||'')).toLowerCase();
    var ids=[], seen={};
    function harvest(){
      var as=edoc().querySelectorAll('a[href]');
      for (var i=0;i<as.length;i++){
        var id = extractBlogId(as[i].href);
        if (!id) continue;
        id=id.toLowerCase();
        if (id===mine) continue;
        if (bad[id]) continue;
        if (!/[\D]/.test(id)) continue;   // 숫자만이면(logNo 등) 제외
        if (seen[id]) continue;
        seen[id]=1; ids.push(id);
      }
    }
    progressiveScan(wanted,maxRounds,harvest,function(){ return ids.length; },function(){
      log('이웃 '+ids.length+'명 수집');
      var out={items:ids};
      if (typeof token!=='undefined') out.token=token;
      try{ NF.onBuddies(JSON.stringify(out)); }catch(e){}
    });
  };

  // ---------- 5) (서로)이웃 신청 ----------
  function findAddBtn(d,targetId){
    var els=d.querySelectorAll('a,button,[role=button],.btn_buddy,._buddyAdd');
    var best=null, bestScore=-9999, wanted=(''+(targetId||'')).toLowerCase();
    for (var i=0;i<els.length;i++){
      var el=els[i], t=norm(txt(el)), cl=(''+(el.className||'')).toLowerCase();
      var link=(attr(el,'href')+' '+attr(el,'onclick')).toLowerCase();
      var matches=t==='이웃추가' || t==='+이웃추가' || t==='이웃추가하기' ||
        t==='이웃맺기' || cl.indexOf('buddy')>=0 || link.indexOf('buddyadd')>=0;
      if (!matches || !visible(el) || el.disabled || attr(el,'aria-disabled')==='true') continue;
      var score=0;
      if (wanted && link.indexOf(wanted)>=0) score+=120;
      if (el.closest && el.closest('header,.header,[class*="profile"],[class*="cover"],[class*="blog_info"]')) score+=70;
      try{ if (el.getBoundingClientRect().top < (window.innerHeight||700)*1.5) score+=30; }catch(e){}
      if (t==='이웃추가' || t==='+이웃추가' || t==='이웃추가하기' || t==='이웃맺기') score+=20;
      if (el.closest && el.closest('[class*="recommend"],[class*="related"],[class*="suggest"]')) score-=120;
      if (score>bestScore){ best=el; bestScore=score; }
    }
    return best;
  }
  function isAlreadyNeighbor(d,targetId){
    if (findAddBtn(d,targetId)) return false;
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var t = norm(txt(els[i]));
      if (t==='이웃취소' || t==='서로이웃' || t==='이웃' || t==='이웃신청중') return true;
    }
    return false;
  }
  
  function selectedChoice(el){
    if (!el) return false;
    if (el.checked) return true;
    if (attr(el,'aria-checked')==='true') return true;
    var c=' '+(''+(el.className||'')).toLowerCase()+' ';
    return c.indexOf(' selected ')>=0 || c.indexOf(' checked ')>=0 || c.indexOf(' on ')>=0;
  }

  function selectChoice(el){
    if (!el) return false;
    try{ el.click(); }catch(e){}
    try{
      if ((el.tagName||'').toLowerCase()==='input') el.checked=true;
      fire(el,'input'); fire(el,'change');
    }catch(e2){}
    return selectedChoice(el);
  }

  function bothNeighborControl(d){
    return d.getElementById('bothBuddyRadio') ||
      d.querySelector('input[value="bothBuddy"]') ||
      d.getElementById('each_buddy2') ||
      d.querySelector('[role="radio"][data-value="bothBuddy"]');
  }

  function hasNeighborTypeControls(d){
    return !!bothNeighborControl(d) || d.querySelectorAll('input[type="radio"],[role="radio"]').length>=2;
  }

  function neighborTypeSelected(d){
    var exact=bothNeighborControl(d);
    if (selectedChoice(exact)) return true;
    var choices=d.querySelectorAll('input[type="radio"],[role="radio"]');
    if (choices.length>=2 && selectedChoice(choices[1])) return true;
    var labels=d.querySelectorAll('label,[role="radio"]');
    for (var i=0;i<labels.length;i++){
      var t=norm(txt(labels[i]));
      if (t.indexOf('서로이웃')<0) continue;
      var inner=labels[i].querySelector ? labels[i].querySelector('input[type="radio"]') : null;
      if (selectedChoice(inner) || selectedChoice(labels[i])) return true;
    }
    return false;
  }

  function chooseNeighborType(d){
    log('서로이웃 선택 시도');
    var exact=bothNeighborControl(d);
    if (exact && selectChoice(exact)) return true;

    var labels=d.querySelectorAll('label,[role="radio"]');
    for (var i=0;i<labels.length;i++){
      var t=norm(txt(labels[i]));
      if (t.indexOf('서로이웃을신청합니다')<0 && t.indexOf('서로이웃신청')<0) continue;
      var inner=labels[i].querySelector ? labels[i].querySelector('input[type="radio"]') : null;
      var forId=attr(labels[i],'for');
      if (!inner && forId) inner=d.getElementById(forId);
      if (inner && selectChoice(inner)) return true;
      if (selectChoice(labels[i])) return true;
    }

    var rs=d.querySelectorAll('input[type="radio"],[role="radio"]');
    if (rs.length>=2 && selectChoice(rs[1])) return true;
    return false;
  }

  // 상단 우측 '확인' 버튼 클릭 절차
  function clickConfirm(d){
    log('확인 버튼 탐색');
    
    // 방법 1: 상단 헤더 영역 내의 "확인" 또는 "다음" 텍스트 버튼 (스크린샷 기준)
    var headers = d.querySelectorAll('header, .header, .u_p_hd, .buddy_add_hd, .u_hd, .top_bar');
    for (var i=0; i<headers.length; i++){
      var btns = headers[i].querySelectorAll('a, button, span, [role="button"]');
      for (var j=0; j<btns.length; j++){
        var t = norm(txt(btns[j]));
        if ((t === '확인' || t === '다음') && !btns[j].disabled && attr(btns[j],'aria-disabled')!=='true') {
          log('헤더 내 [' + t + '] 버튼 발견 -> 클릭');
          try{ btns[j].click(); return true; }catch(e){}
        }
      }
    }
    
    // 방법 2: 클래스 기반 (btn_ok, btn_next 등)
    var okBtn = d.querySelector('.btn_ok, ._btn_ok, .btn_next, ._btnNext, ._confirm, .confirm');
    if (okBtn && !okBtn.disabled && attr(okBtn,'aria-disabled')!=='true') {
      log('클래스 기반 버튼 발견 -> 클릭');
      try{ okBtn.click(); return true; }catch(e){}
    }

    // 방법 3: 전체 영역 텍스트 매칭
    var all=d.querySelectorAll('a,button,[role="button"]');
    for (var k=0;k<all.length;k++){
      var at=norm(txt(all[k]));
      if ((at==='확인' || at==='다음' || at==='신청' || at==='완료') &&
          visible(all[k]) && !all[k].disabled && attr(all[k],'aria-disabled')!=='true'){
        try{ all[k].click(); return true; }catch(e){}
      }
    }
    return false;
  }

  function messageField(d){
    return d.querySelector('textarea[placeholder*="메시지"], textarea[placeholder*="멘트"], textarea[placeholder*="신청"], .buddy_message textarea, textarea[maxlength], textarea');
  }

  function fillMessage(d, msg){
    if (!msg) return true;
    var ta=messageField(d);
    if (ta){
      log('메시지 입력창 발견 -> 텍스트 입력');
      ta.focus(); ta.value = msg; fire(ta, 'input'); fire(ta, 'change');
      return (ta.value||'')===msg;
    }
    return false;
  }

  function dialogConfirm(d){
    var roots=d.querySelectorAll('[role="dialog"], .popup, .modal, .ly_popup, .layer_popup');
    for (var i=0;i<roots.length;i++){
      var els=roots[i].querySelectorAll('button,a,[role="button"]');
      for (var j=0;j<els.length;j++){
        var t=norm(txt(els[j]));
        if ((t==='확인' || t==='예') && visible(els[j]) && !els[j].disabled){
          try{ els[j].click(); return true; }catch(e){}
        }
      }
    }
    return false;
  }

  function neighborOutcome(d){
    var body=norm(txt(d.body)).slice(0,200000);
    var blocked=['일일신청한도를초과','하루신청가능횟수를초과','접근이제한되었습니다',
      '잠시후다시시도','비정상적인접근','자동입력방지'];
    for (var b=0;b<blocked.length;b++) if (body.indexOf(blocked[b])>=0) return 'blocked';
    var failures=['서로이웃을신청할수없','이웃을추가할수없','신청에실패','신청실패'];
    for (var i=0;i<failures.length;i++) if (body.indexOf(failures[i])>=0) return 'failed';

    try{
      var u=new URL(location.href);
      var code=(''+(u.searchParams.get('result')||u.searchParams.get('status')||'')).toLowerCase();
      if (code==='success' || code==='complete' || code==='ok') return 'added';
      if (code==='fail' || code==='failed' || code==='error') return 'failed';
      if ((u.pathname||'').indexOf('BuddyAddComplete')>=0) return 'added';
    }catch(e){}

    var notices=d.querySelectorAll('[role="alert"], [class*="toast"], [class*="result"], [class*="complete"], [class*="success"]');
    for (var n=0;n<notices.length;n++){
      var nt=norm(txt(notices[n]));
      if (nt.indexOf('신청이완료')>=0 || nt.indexOf('신청을보냈')>=0 ||
          nt.indexOf('신청이접수')>=0 || nt.indexOf('이웃으로추가되었습니다')>=0) return 'added';
    }

    if (location.href.indexOf('BuddyAddForm')<0){
      var states=d.querySelectorAll('a,button,[role="button"]');
      for (var s=0;s<states.length;s++){
        var st=norm(txt(states[s]));
        if (st==='이웃신청중' || st==='신청중' || st==='서로이웃' || st==='이웃취소') return 'added';
      }
    }
    return '';
  }

  window.__NF_addNeighbor = function(payload){
    if (typeof payload==='string'){ try{ payload=JSON.parse(payload); }catch(e){ payload={}; } }
    if (!payload || typeof payload!=='object') payload={};
    var msg = payload.message || '', targetId=payload.bloggerId||'', reported=false;
    var verifyKey='__NF_BUDDY_VERIFY', tokenKey='legacy';
    if (Object.prototype.hasOwnProperty.call(payload,'token')) tokenKey=''+payload.token;

    function pendingVerification(){
      try{
        var raw=sessionStorage.getItem(verifyKey);
        if (!raw) return false;
        var saved=JSON.parse(raw);
        if (!saved || Date.now()-saved.at>120000){ sessionStorage.removeItem(verifyKey); return false; }
        return saved.token===tokenKey;
      }catch(e){ return false; }
    }

    function rememberVerification(){
      try{ sessionStorage.setItem(verifyKey,JSON.stringify({token:tokenKey,at:Date.now()})); }catch(e){}
    }

    function clearVerification(){
      try{ if (pendingVerification()) sessionStorage.removeItem(verifyKey); }catch(e){}
    }

    function report(s){
      if (reported) return;
      reported=true;
      clearVerification();
      var out={status:s};
      if (Object.prototype.hasOwnProperty.call(payload,'token')) out.token=payload.token;
      log('결과: '+s);
      try{ NF.onAdd(JSON.stringify(out)); }catch(e2){}
    }

    function observeFinal(){
      var tries=0;
      function check(){
        if (reported) return;
        if (looksLikeLogin()){
          report('needlogin'); return;
        }
        var state=neighborOutcome(edoc());
        if (state){ report(state); return; }
        tries++;
        if (tries>=10){ report('unverified'); return; }
        if (tries===2) dialogConfirm(edoc());
        setTimeout(check,650);
      }
      setTimeout(check,450);
    }

    function submitFinal(){
      var state=neighborOutcome(edoc());
      if (state){ report(state); return; }
      if (msg && !fillMessage(edoc(),msg)){
        log('신청 메시지 입력 실패'); report('failed'); return;
      }
      setTimeout(function(){
        if (reported) return;
        log('최종 신청 클릭 시도');
        rememberVerification();
        if (!clickConfirm(edoc())){ report('failed'); return; }
        observeFinal();
      },700);
    }
    
    var d = edoc();
    if (looksLikeLogin()){ report('needlogin'); return; }
    
    // 현재 페이지 상태 판별
    var isFormPage = (location.href.indexOf('BuddyAddForm') >= 0 || d.querySelector('#bothBuddyRadio') || d.querySelector('.buddy_add_hd'));

    if (payload.verifyResult===true){
      var resultPageState=neighborOutcome(d);
      if (resultPageState){ report(resultPageState); return; }
    }

    // 최종 확인 뒤 페이지가 이동했다면 같은 token의 새 페이지에서 먼저 결과를 검증한다.
    if (pendingVerification()){
      var pendingState=neighborOutcome(d);
      if (pendingState){ report(pendingState); return; }
      observeFinal(); return;
    }
    
    if (!isFormPage) {
      // [단계 1] 블로그 홈: 이웃추가 버튼 클릭
      if (isAlreadyNeighbor(d,targetId)){ report('already'); return; }
      var addBtn = findAddBtn(d,targetId);
      if (!addBtn){ report('noaddbtn'); return; }
      log('1단계: 이웃추가 버튼 클릭');
      try{ addBtn.click(); }catch(e){ report('failed'); }
      return;
    } else {
      // [단계 2] 신청 폼 페이지: 서로이웃 선택 및 확인
      log('2단계: 신청 폼 처리 시작');
      setTimeout(function(){
        if (reported) return;
        var now=edoc(), immediate=neighborOutcome(now);
        if (immediate){ report(immediate); return; }

        if (!hasNeighborTypeControls(now)){
          if (messageField(now)){ submitFinal(); return; }
          report('failed'); return;
        }

        if (!chooseNeighborType(now)){ report('failed'); return; }
        setTimeout(function(){
          if (reported) return;
          if (!neighborTypeSelected(edoc())){ report('failed'); return; }
          log('서로이웃 선택 확인 · 다음 클릭 시도');
          if (!clickConfirm(edoc())){ report('failed'); return; }
          setTimeout(function(){ if (!reported) submitFinal(); },1200);
        },350);
      }, 800);
    }
  };

  window.__NF_loginCheck = function(){
    try{ NF.onLogin(looksLikeLogin()); }catch(e){}
  };

  log('NF 스크립트 로드됨');
})();
"""
}
