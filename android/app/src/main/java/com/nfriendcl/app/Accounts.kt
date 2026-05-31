package com.nfriendcl.app

import android.content.Context
import android.webkit.CookieManager
import java.net.URLEncoder

/**
 * 네이버 계정(dicajohn / macdcross + 사용자가 직접 입력한 임의 아이디)을 쿠키 스냅샷으로 관리한다.
 *
 * 한 WebView 의 CookieManager 는 한 번에 한 네이버 세션만 들고 있으므로,
 * 계정 전환 시 (1) 현재 계정 쿠키 저장 → (2) 전체 쿠키 삭제 →
 * (3) 대상 계정의 저장된 쿠키 복원 순으로 처리해 여러 로그인을 모두 유지한다.
 *
 * (nclaude 와 동일한 방식 — 같은 집안 스택)
 */
object Accounts {

    /** 토글 버튼에 노출되는 기본 계정. 그 외 아이디는 사용자가 직접 입력. */
    val IDS = listOf("dicajohn", "macdcross")

    // --- 자동화에 쓰는 모바일 네이버 URL ---
    fun homeUrl(id: String) = "https://m.blog.naver.com/$id"

    /** 이웃새글 피드(하트·댓글 소셜 활동의 진입점) */
    const val FEED_URL = "https://m.blog.naver.com/News.naver"

    /** 이웃 목록 / 서로이웃 신청 관리 진입점 */
    fun buddyListUrl(id: String) = "https://m.blog.naver.com/BuddyList.naver?blogId=$id"

    /** 주제(키워드)로 모바일 블로그 검색 */
    fun blogSearchUrl(topic: String): String {
        val q = URLEncoder.encode(topic.trim(), "UTF-8")
        return "https://m.search.naver.com/search.naver?where=m_blog&sm=mtb_jum&query=$q"
    }

    private const val PREFS = "nfriendcl_accounts"
    private val HOSTS = listOf(
        "https://naver.com",
        "https://www.naver.com",
        "https://nid.naver.com",
        "https://blog.naver.com",
        "https://m.blog.naver.com"
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 현재 CookieManager 의 네이버 쿠키를 "n=v; n2=v2" 문자열로 병합 */
    fun snapshotCurrent(): String {
        val cm = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        for (h in HOSTS) {
            val ck = cm.getCookie(h) ?: continue
            for (pair in ck.split(";")) {
                val p = pair.trim()
                val eq = p.indexOf('=')
                if (eq > 0) map[p.substring(0, eq)] = p.substring(eq + 1)
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** NID 인증 쿠키 존재 여부로 로그인 상태 판단 */
    fun isLoggedIn(): Boolean {
        val snap = snapshotCurrent()
        return snap.contains("NID_SES") || snap.contains("NID_AUT")
    }

    fun saveCurrentFor(ctx: Context, id: String) {
        val snap = snapshotCurrent()
        if (snap.isNotBlank() && (snap.contains("NID_SES") || snap.contains("NID_AUT"))) {
            prefs(ctx).edit().putString("ck_$id", snap).apply()
        }
    }

    fun load(ctx: Context, id: String): String? = prefs(ctx).getString("ck_$id", null)

    fun hasSession(ctx: Context, id: String): Boolean = !load(ctx, id).isNullOrBlank()

    /**
     * 대상 계정으로 전환: 전체 쿠키 삭제 후 저장된 쿠키 복원.
     * @param done (hadSession) -> Unit  저장된 세션이 있었는지 콜백
     */
    fun applyTo(ctx: Context, id: String, done: (Boolean) -> Unit) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            val snap = load(ctx, id)
            val had = !snap.isNullOrBlank()
            if (had) {
                for (pair in snap!!.split(";")) {
                    val p = pair.trim()
                    if (p.isEmpty()) continue
                    cm.setCookie("https://www.naver.com", "$p; Domain=.naver.com; Path=/; Secure")
                }
            }
            cm.flush()
            done(had)
        }
    }
}
