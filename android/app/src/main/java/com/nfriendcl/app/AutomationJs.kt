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

  // ---------- 3) 서로이웃 신청 전체 수락 ----------
  window.__NF_acceptAll = function(){
    var count=0, max=200;
    function findAccept(){
      var els = edoc().querySelectorAll('a,button,[role=button]');
      for (var i=0;i<els.length;i++){
        var t = norm(txt(els[i]));
        if (t==='수락' || t==='서로이웃수락' || t==='신청수락' || t==='수락하기') return els[i];
      }
      return null;
    }
    function step(){
      if (count>=max){ done('max'); return; }
      var b = findAccept();
      if (!b){ done(count>0?'done':'none'); return; }
      try{ b.click(); }catch(e){}
      count++;
      setTimeout(function(){
        clickByText(edoc(), ['확인','예','수락']);
        setTimeout(step, 1200);
      }, 700);
    }
    function done(why){ log('수락 '+count+'건 ('+why+')'); try{ NF.onAccept(JSON.stringify({count:count, done:true, msg:why})); }catch(e){} }
    step();
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
  function chooseBothNeighbor(d){
    var els = d.querySelectorAll('label,button,a,[role=button],span');
    for (var i=0;i<els.length;i++){
      if (norm(txt(els[i]))==='서로이웃'){
        var inp = els[i].querySelector ? els[i].querySelector('input') : null;
        if (inp && inp.disabled) continue;
        try{ els[i].click(); }catch(e){}
        try{ if (inp){ inp.checked=true; fire(inp,'change'); } }catch(e){}
        return '서로이웃';
      }
    }
    return 'default';
  }
  function fillMessage(d, msg){
    if (!msg) return;
    var ta = d.querySelector('textarea[placeholder*="메시지"], textarea[placeholder*="멘트"], textarea[placeholder*="신청"], .buddy_message textarea, textarea[maxlength], textarea');
    if (ta){ try{ ta.focus(); }catch(e){} ta.value = msg; fire(ta,'input'); fire(ta,'change'); }
  }
  window.__NF_addNeighbor = function(payload){
    if (typeof payload==='string'){ try{ payload=JSON.parse(payload); }catch(e){ payload={}; } }
    var msg = payload.message || '';
    function report(s){ log('이웃신청 '+s); try{ NF.onAdd(JSON.stringify({status:s})); }catch(e){} }
    var d = edoc();
    if (looksLikeLogin()){ try{ NF.onNeedLogin(); }catch(e){} report('needlogin'); return; }
    if (isAlreadyNeighbor(d)){ report('already'); return; }
    var addBtn = findAddBtn(d);
    if (!addBtn){ report('noaddbtn'); return; }
    try{ addBtn.click(); }catch(e){}
    setTimeout(function(){
      chooseBothNeighbor(edoc());
      setTimeout(function(){
        fillMessage(edoc(), msg);
        setTimeout(function(){
          clickByText(edoc(), ['다음']);
          setTimeout(function(){
            var ok = clickByText(edoc(), ['확인','신청','서로이웃신청','이웃신청','이웃추가','추가']);
            setTimeout(function(){
              if (looksLikeLogin()){ report('needlogin'); return; }
              report(ok ? 'added' : 'fail');
            }, 800);
          }, 700);
        }, 500);
      }, 600);
    }, 900);
  };

  window.__NF_loginCheck = function(){
    try{ NF.onLogin(looksLikeLogin()); }catch(e){}
  };

  log('NF 스크립트 로드됨');
})();
"""
}
