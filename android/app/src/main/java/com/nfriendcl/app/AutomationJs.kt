package com.nfriendcl.app

/**
 * 네이버 모바일 블로그 소셜 활동 자동화 스크립트.
 *
 * Kotlin 이 호출하는 진입점(window.*):
 *   __NF_collectFeed()                 : 이웃새글 피드에서 글 URL 목록 수집 → NF.onUrls(json)
 *   __NF_likeAndComment(payload)       : 열린 글에서 공감(하트) + 댓글 등록 → NF.onActed(json)
 *   __NF_acceptAll()                   : 현재 화면의 '수락' 버튼 전부 클릭 → NF.onAccept(json)
 *   __NF_collectBloggers(myId)         : 블로그 검색결과에서 블로거 아이디 수집 → NF.onBloggers(json)
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

  // 지연 로딩(무한 스크롤) 유도 후 콜백
  function scrollLoad(times, cb){
    var n=0;
    function go(){
      try{ window.scrollTo(0, document.body.scrollHeight); }catch(e){}
      n++;
      if (n>=times){ setTimeout(cb, 700); } else { setTimeout(go, 800); }
    }
    go();
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
  function isPostUrl(h){
    if (!h || h.indexOf('naver.com')<0) return false;
    if (/logNo=\d{6,}/.test(h)) return true;
    if (/blog\.naver\.com\/[a-zA-Z0-9_-]+\/\d{6,}/.test(h)) return true;
    if (h.indexOf('PostView')>=0 && h.indexOf('logNo')>=0) return true;
    return false;
  }
  window.__NF_collectFeed = function(){
    scrollLoad(3, function(){
      var d = edoc();
      var as = d.querySelectorAll('a[href]');
      var urls=[], seen={};
      for (var i=0;i<as.length;i++){
        var h = as[i].href;
        if (isPostUrl(h) && !seen[h]){ seen[h]=1; urls.push(h); }
      }
      log('피드 글 '+urls.length+'개 수집');
      try{ NF.onUrls(JSON.stringify(urls)); }catch(e){}
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
    var doLike = payload.like!==false, doComment = payload.comment!==false, text = payload.text||'';
    var result = {liked:false, commented:false, msg:''};
    var d = edoc();
    if (looksLikeLogin()){ try{ NF.onNeedLogin(); }catch(e){} result.msg='login'; finish(); return; }
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
    function finish(){ log('행동 '+JSON.stringify(result)); try{ NF.onActed(JSON.stringify(result)); }catch(e){} }
  };

  // ---------- 3) 서로이웃 신청 전체 수락 (Batch) ----------
  window.__NF_acceptAll = function(){
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
              try{ NF.onAccept(JSON.stringify({count:1, done:true, msg:'done'})); }catch(e){}
            }, 1000);
          }, 1000);
        } else {
          log('수락 버튼을 찾을 수 없음');
          try{ NF.onAccept(JSON.stringify({count:0, done:true, msg:'none'})); }catch(e){}
        }
      }, 1000);
    } else {
      log('체크박스를 찾을 수 없음 (서로이웃 신청 목록이 아닐 수 있음)');
      try{ NF.onAccept(JSON.stringify({count:0, done:true, msg:'none'})); }catch(e){}
    }
  };

  // ---------- 4) 검색결과에서 블로거 아이디 수집 ----------
  function extractBlogId(href){
    if (!href) return null;
    var m = href.match(/[?&]blogId=([a-zA-Z0-9_-]+)/); if (m) return m[1];
    m = href.match(/blog\.naver\.com\/([a-zA-Z0-9_-]+)/); if (m) return m[1];
    return null;
  }
  window.__NF_collectBloggers = function(myId){
    var bad = {PostView:1,PostList:1,BuddyList:1,News:1,FeedList:1,Recommendation:1,
      RabbitWrite:1,GuestBook:1,guestbook:1,prologue:1,rss:1,ThemePost:1,section:1,
      Recommend:1,PostThumbnailAlbumView:1,EdiaryView:1};
    scrollLoad(3, function(){
      var as = edoc().querySelectorAll('a[href]');
      var ids=[], seen={};
      for (var i=0;i<as.length;i++){
        var id = extractBlogId(as[i].href);
        if (!id) continue;
        if (id===myId) continue;
        if (bad[id]) continue;
        if (!/[\D]/.test(id)) continue;   // 숫자만이면(logNo 등) 제외
        if (seen[id]) continue;
        seen[id]=1; ids.push(id);
      }
      log('블로거 '+ids.length+'명 수집');
      try{ NF.onBloggers(JSON.stringify(ids)); }catch(e){}
    });
  };

  // ---------- 5) (서로)이웃 신청 ----------
  function findAddBtn(d){
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var t = norm(txt(els[i]));
      if (t==='이웃추가' || t==='+이웃추가' || t==='이웃추가하기' || t==='이웃맺기') return els[i];
    }
    return d.querySelector('.btn_buddy, ._buddyAdd, a[href*="BuddyAdd"], a[onclick*="buddy"]');
  }
  function isAlreadyNeighbor(d){
    if (findAddBtn(d)) return false;
    var els = d.querySelectorAll('a,button,[role=button]');
    for (var i=0;i<els.length;i++){
      var t = norm(txt(els[i]));
      if (t==='이웃취소' || t==='서로이웃' || t==='이웃' || t==='이웃신청중') return true;
    }
    return false;
  }
  
  // 1순위 서로이웃 선택 절차 (더욱 공격적인 체크)
  function chooseNeighborType(d){
    log('!! 서로이웃 원 체크 시도 !!');
    
    // 방법 1: "서로이웃을 신청합니다" 텍스트 기반 정밀 탐색 및 체크
    var labels = d.querySelectorAll('label, span, li, dt, dd, div');
    for (var i=0; i<labels.length; i++){
      var t = norm(txt(labels[i]));
      if (t.indexOf('서로이웃을신청합니다') >= 0 || t.indexOf('서로이웃신청') >= 0) {
        log('서로이웃 텍스트 발견 -> 체크 시도');
        // 해당 요소 직접 클릭
        labels[i].click();
        // 내부 또는 근처의 input(radio)을 찾아 강제로 체크
        var inner = labels[i].querySelector('input[type="radio"]');
        if (!inner && labels[i].parentElement) inner = labels[i].parentElement.querySelector('input[type="radio"]');
        if (inner) {
          log('라디오 버튼 강제 체크');
          inner.click(); inner.checked = true; fire(inner, 'change'); fire(inner, 'click');
        }
        return 'SUCCESS_TEXT';
      }
    }

    // 방법 2: 라디오 버튼 리스트에서 두 번째(서로이웃) 직접 강제 클릭
    var rs = d.querySelectorAll('input[type="radio"]');
    if (rs.length >= 2) {
      log('라디오 리스트 확인 -> 2번째(서로이웃) 강제 선택');
      rs[1].click(); rs[1].checked = true; fire(rs[1], 'change'); fire(rs[1], 'click');
      return 'SUCCESS_INDEX';
    }

    // 방법 3: ID 기반 직접 선택
    var both = d.getElementById('bothBuddyRadio') || d.querySelector('input[value="bothBuddy"]') || d.getElementById('each_buddy2');
    if (both) {
      log('ID/Value 기반 -> 서로이웃 선택');
      both.click(); both.checked = true; fire(both, 'change');
      return 'SUCCESS_ID';
    }
    return 'FAIL';
  }

  // 상단 우측 '확인' 버튼 클릭 절차
  function clickConfirm(d){
    log('!! 상단 확인 버튼 탐색 !!');
    
    // 방법 1: 상단 헤더 영역 내의 "확인" 또는 "다음" 텍스트 버튼 (스크린샷 기준)
    var headers = d.querySelectorAll('header, .header, .u_p_hd, .buddy_add_hd, .u_hd, .top_bar');
    for (var i=0; i<headers.length; i++){
      var btns = headers[i].querySelectorAll('a, button, span, [role="button"]');
      for (var j=0; j<btns.length; j++){
        var t = norm(txt(btns[j]));
        if (t === '확인' || t === '다음') {
          log('헤더 내 [' + t + '] 버튼 발견 -> 클릭');
          btns[j].click(); return true;
        }
      }
    }
    
    // 방법 2: 클래스 기반 (btn_ok, btn_next 등)
    var okBtn = d.querySelector('.btn_ok, ._btn_ok, .btn_next, ._btnNext, ._confirm, .confirm');
    if (okBtn) {
      log('클래스 기반 버튼 발견 -> 클릭');
      okBtn.click(); return true;
    }

    // 방법 3: 전체 영역 텍스트 매칭
    return clickByText(d, ['확인','다음','신청','완료']);
  }

  function fillMessage(d, msg){
    if (!msg) return;
    var ta = d.querySelector('textarea[placeholder*="메시지"], textarea[placeholder*="멘트"], textarea[placeholder*="신청"], .buddy_message textarea, textarea[maxlength], textarea');
    if (ta){ 
      log('메시지 입력창 발견 -> 텍스트 입력');
      ta.focus(); ta.value = msg; fire(ta, 'input'); fire(ta, 'change');
    }
  }

  window.__NF_addNeighbor = function(payload){
    if (typeof payload==='string'){ try{ payload=JSON.parse(payload); }catch(e){ payload={}; } }
    var msg = payload.message || '';
    function report(s){ log('결과: '+s); try{ NF.onAdd(JSON.stringify({status:s})); }catch(e){} }
    
    var d = edoc();
    if (looksLikeLogin()){ try{ NF.onNeedLogin(); }catch(e){} report('needlogin'); return; }
    
    // 현재 페이지 상태 판별
    var isFormPage = (location.href.indexOf('BuddyAddForm') >= 0 || d.querySelector('#bothBuddyRadio') || d.querySelector('.buddy_add_hd'));
    
    if (!isFormPage) {
      // [단계 1] 블로그 홈: 이웃추가 버튼 클릭
      if (isAlreadyNeighbor(d)){ report('already'); return; }
      var addBtn = findAddBtn(d);
      if (!addBtn){ report('noaddbtn'); return; }
      log('1단계: 이웃추가 버튼 클릭');
      addBtn.click();
    } else {
      // [단계 2] 신청 폼 페이지: 서로이웃 선택 및 확인
      log('2단계: 신청 폼 처리 시작');
      setTimeout(function(){
        // 1. 서로이웃 원 체크
        var res = chooseNeighborType(edoc());
        
        setTimeout(function(){
          // 2. 상단 우측 확인 클릭
          log('3단계: 상단 확인 클릭 시도');
          var ok1 = clickConfirm(edoc());
          
          setTimeout(function(){
            // 3. 메시지 입력 및 최종 신청
            fillMessage(edoc(), msg);
            setTimeout(function(){
              log('4단계: 최종 신청 클릭 시도');
              var ok2 = clickConfirm(edoc());
              setTimeout(function(){
                clickByText(edoc(), ['확인','예']);
                setTimeout(function(){ report('added'); }, 1000);
              }, 1000);
            }, 1200);
          }, 1200);
        }, 1000);
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
