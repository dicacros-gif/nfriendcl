package com.nfriendcl.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * N FriendCl — 네이버 블로그 친구/소셜 활동 자동화.
 *
 * 3가지 모드(WebView + JS 주입으로 동작, 계정은 쿠키 스냅샷으로 전환):
 *  - SOCIAL : 이웃새글 피드를 돌며 공감(하트) + 변형 댓글 등록
 *  - ACCEPT : 받은 서로이웃 신청 전체 수락
 *  - GROW   : 주제 검색 → 친구가 아닌 블로거에게 (서로)이웃 신청
 *
 * 각 동작 사이에 사용자가 정한 간격을 두고 한 건씩 처리(도배/차단 방지), '정지'로 중단 가능.
 */
class MainActivity : AppCompatActivity() {

    private enum class Phase { NONE, FEED_COLLECT, POST_ACT, ACCEPT_RUN, SEARCH_COLLECT, BLOG_ADD }

    private lateinit var web: WebView
    private lateinit var form: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var debugLog: TextView
    private lateinit var stopBar: View
    private lateinit var btnStop: Button
    private lateinit var btnAcceptHere: Button
    private lateinit var btnLogin: Button

    private lateinit var accountGroup: MaterialButtonToggleGroup
    private lateinit var customId: EditText
    private lateinit var currentAccountText: TextView
    private lateinit var commentBase: EditText
    private lateinit var commentSamples: TextView
    private lateinit var swLike: SwitchCompat
    private lateinit var swComment: SwitchCompat
    private lateinit var countInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var neighborMsg: EditText
    private lateinit var topicInput: EditText
    private lateinit var growCountInput: EditText

    private var currentAccount = Accounts.IDS[0]

    private var mode = Phase.NONE
    private var phase = Phase.NONE
    private var running = false
    private var pageActed = false

    private val queue = ArrayDeque<String>()
    private var processed = 0      // SOCIAL: 처리한 글 수
    private var added = 0          // GROW: 신청 성공 수
    private var target = 20
    private var delayMs = 5000L
    private var seq = 0            // 댓글/메시지 변형 시드
    private var stepToken = 0      // 응답 없음 감시용 토큰

    private val logLines = ArrayDeque<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        form = findViewById(R.id.form)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        debugLog = findViewById(R.id.debugLog)
        debugLog.movementMethod = android.text.method.ScrollingMovementMethod()
        stopBar = findViewById(R.id.stopBar)
        btnStop = findViewById(R.id.btnStop)
        btnAcceptHere = findViewById(R.id.btnAcceptHere)

        accountGroup = findViewById(R.id.accountGroup)
        customId = findViewById(R.id.customId)
        currentAccountText = findViewById(R.id.currentAccountText)
        commentBase = findViewById(R.id.commentBase)
        commentSamples = findViewById(R.id.commentSamples)
        swLike = findViewById(R.id.swLike)
        swComment = findViewById(R.id.swComment)
        countInput = findViewById(R.id.countInput)
        delayInput = findViewById(R.id.delayInput)
        neighborMsg = findViewById(R.id.neighborMsg)
        topicInput = findViewById(R.id.topicInput)
        growCountInput = findViewById(R.id.growCountInput)
        btnLogin = findViewById(R.id.btnLogin)

        commentBase.setText(Comments.DEFAULT_BASE)
        neighborMsg.setText(Comments.DEFAULT_NEIGHBOR_MSG)
        refreshSamples()
        commentBase.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshSamples()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        setupWeb()
        setupAccounts()

        findViewById<Button>(R.id.btnSocial).setOnClickListener { startSocial() }
        findViewById<Button>(R.id.btnAccept).setOnClickListener { startAccept() }
        findViewById<Button>(R.id.btnGrow).setOnClickListener { startGrow() }
        findViewById<Button>(R.id.btnUseCustom).setOnClickListener { useCustomId() }
        btnLogin.setOnClickListener { openLogin() }
        btnStop.setOnClickListener { stopAll() }
        btnAcceptHere.setOnClickListener { acceptHere() }

        showRunning(false)
    }

    private fun refreshSamples() {
        val base = commentBase.text?.toString().orEmpty()
        val s = Comments.samples(base, 3).joinToString("\n") { "• $it" }
        commentSamples.text = "변형 예시\n$s"
    }

    // ---------------------------------------------------------------
    //  계정
    // ---------------------------------------------------------------
    private fun setupAccounts() {
        findViewById<MaterialButton>(R.id.btnAcc1).text = Accounts.IDS[0]
        findViewById<MaterialButton>(R.id.btnAcc2).text = Accounts.IDS[1]
        accountGroup.check(R.id.btnAcc1)
        Accounts.applyTo(this, currentAccount) { had -> updateAccountLabel(had) }
        accountGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val picked = if (checkedId == R.id.btnAcc1) Accounts.IDS[0] else Accounts.IDS[1]
            switchAccount(picked)
        }
    }

    private fun useCustomId() {
        val id = customId.text?.toString()?.trim()?.lowercase()
        if (id.isNullOrBlank()) { toast("아이디를 입력하세요"); return }
        accountGroup.clearChecked()
        switchAccount(id)
        toast("$id 계정 사용")
    }

    private fun switchAccount(targetId: String) {
        if (targetId == currentAccount) { updateAccountLabel(Accounts.hasSession(this, targetId)); return }
        if (Accounts.isLoggedIn()) Accounts.saveCurrentFor(this, currentAccount)
        currentAccount = targetId
        Accounts.applyTo(this, targetId) { had -> updateAccountLabel(had) }
    }

    private fun updateAccountLabel(hadSession: Boolean) {
        val state = if (hadSession || Accounts.isLoggedIn()) "로그인 세션 있음" else "로그인 필요"
        currentAccountText.text = "현재 계정: $currentAccount ($state)"
    }

    // ---------------------------------------------------------------
    //  WebView
    // ---------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWeb() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        web.addJavascriptInterface(NF(), "NF")

        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP ->
                    if (!v.hasFocus()) v.requestFocus()
            }
            false
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (Accounts.isLoggedIn()) {
                    Accounts.saveCurrentFor(this@MainActivity, currentAccount)
                    if (!running) {
                        updateAccountLabel(true)
                        setStatus("$currentAccount 로그인 완료 — '■ 정지 / 홈'을 눌러 돌아가세요")
                        progress.visibility = View.GONE
                    }
                }
                if (!running) return
                if (url.contains("nid.naver.com")) { onNeedLoginUi(); return }
                if (pageActed) return
                when (phase) {
                    Phase.FEED_COLLECT -> { pageActed = true; web.postDelayed({ runJs("window.__NF_collectFeed()") }, 900) }
                    Phase.POST_ACT -> { pageActed = true; web.postDelayed({ doLikeAndComment() }, 1500) }
                    Phase.ACCEPT_RUN -> { pageActed = true; web.postDelayed({ runJs("window.__NF_acceptAll()") }, 1100) }
                    Phase.SEARCH_COLLECT -> { pageActed = true; web.postDelayed({ runJs("window.__NF_collectBloggers(${JSONObject.quote(currentAccount)})") }, 900) }
                    Phase.BLOG_ADD -> { pageActed = true; web.postDelayed({ doAddNeighbor() }, 1500) }
                    Phase.NONE -> {}
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // 네이버 수락/서로이웃 확인창(window.confirm/alert)을 자동 통과시켜 자동화가 멈추지 않게 함
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                dbg("알림: ${message ?: ""}")
                result?.confirm(); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                dbg("확인: ${message ?: ""}")
                result?.confirm(); return true
            }
        }
    }

    private fun runJs(call: String) {
        if (!running) return
        web.evaluateJavascript(AutomationJs.SCRIPT) { web.evaluateJavascript(call, null) }
    }

    // ---------------------------------------------------------------
    //  공통 실행 제어
    // ---------------------------------------------------------------
    private fun readSettings(countView: EditText) {
        target = countView.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 500) ?: 20
        val sec = delayInput.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(2, 120) ?: 5
        delayMs = sec * 1000L
    }

    private fun beginRun(m: Phase, firstUrl: String, firstPhase: Phase, countView: EditText = countInput) {
        if (!ensureLoggedInOrPrompt()) {
            // 로그인 안 됐어도 일단 페이지를 열어 로그인 유도(웹뷰 보임)
        }
        readSettings(countView)
        mode = m
        running = true
        processed = 0
        added = 0
        seq = 0
        queue.clear()
        logLines.clear(); debugLog.text = ""
        showRunning(true)
        btnAcceptHere.visibility = if (m == Phase.ACCEPT_RUN) View.VISIBLE else View.GONE
        progress.visibility = View.VISIBLE
        loadPage(firstUrl, firstPhase)
    }

    private fun ensureLoggedInOrPrompt(): Boolean {
        return Accounts.isLoggedIn() || Accounts.hasSession(this, currentAccount)
    }

    private fun loadPage(url: String, p: Phase) {
        phase = p
        pageActed = false
        stepToken++
        dbg("이동: ${shortUrl(url)}")
        web.loadUrl(url)
    }

    private fun showRunning(run: Boolean) {
        form.visibility = if (run) View.GONE else View.VISIBLE
        web.visibility = if (run) View.VISIBLE else View.GONE
        stopBar.visibility = if (run) View.VISIBLE else View.GONE
        debugLog.visibility = if (run) View.VISIBLE else View.GONE
        if (run) status.visibility = View.VISIBLE
    }

    private fun stopAll() {
        running = false
        mode = Phase.NONE
        phase = Phase.NONE
        queue.clear()
        progress.visibility = View.GONE
        showRunning(false)
        setStatus("정지됨")
    }

    private fun finishRun(msg: String) {
        running = false
        mode = Phase.NONE
        phase = Phase.NONE
        queue.clear()
        progress.visibility = View.GONE
        showRunning(false)
        setStatus(msg)
        toast(msg)
    }

    private fun onNeedLoginUi() {
        running = false
        progress.visibility = View.GONE
        setStatus("로그인이 필요합니다 — 웹뷰에서 로그인 후 같은 버튼을 다시 누르세요")
        dbg("로그인 대기")
    }

    /** 첫 화면에서 바로 네이버 로그인 → 완료 시 현재 계정 세션 자동 저장('정지/홈'으로 복귀) */
    private fun openLogin() {
        running = false
        mode = Phase.NONE
        phase = Phase.NONE
        queue.clear()
        showRunning(true)
        btnAcceptHere.visibility = View.GONE
        progress.visibility = View.VISIBLE
        setStatus("$currentAccount 로 로그인하세요 — 끝나면 '■ 정지 / 홈'을 누르면 저장됩니다")
        dbg("로그인 화면 — $currentAccount")
        web.loadUrl(Accounts.LOGIN_URL)
    }

    // ---------------------------------------------------------------
    //  모드 1: 이웃새글 소셜 활동
    // ---------------------------------------------------------------
    private fun startSocial() {
        setStatus("$currentAccount · 이웃새글 피드 여는 중…")
        beginRun(Phase.FEED_COLLECT, Accounts.FEED_URL, Phase.FEED_COLLECT, countInput)
    }

    private fun handleUrls(json: String) {
        if (!running) return
        val arr = parseArray(json)
        if (arr.length() == 0) { finishRun("이웃새글을 찾지 못했어요 — 로그인/이웃 여부를 확인하세요"); return }
        for (i in 0 until arr.length()) {
            val u = arr.optString(i)
            if (u.isNotBlank() && queue.size < target) queue.addLast(u)
        }
        dbg("처리 대상 글 ${queue.size}개")
        setStatus("글 ${queue.size}개 · 소셜활동 시작")
        processNextPost()
    }

    private fun processNextPost() {
        if (!running) return
        if (processed >= target || queue.isEmpty()) {
            finishRun("소셜활동 완료 — 글 ${processed}개 처리"); return
        }
        val url = queue.removeFirst()
        setStatus("소셜활동 ${processed + 1}/$target — 글 여는 중…")
        loadPage(url, Phase.POST_ACT)
        armWatchdog { dbg("응답 없음 → 다음 글"); processed++; processNextPost() }
    }

    private fun doLikeAndComment() {
        if (!running) return
        val payload = JSONObject()
            .put("like", swLike.isChecked)
            .put("comment", swComment.isChecked)
            .put("text", Comments.comment(commentBase.text?.toString().orEmpty(), seq++))
        runJs("window.__NF_likeAndComment($payload)")
    }

    private fun handleActed(json: String) {
        if (!running) return
        stepToken++  // 감시 무효화
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        processed++
        dbg("글 $processed: 공감=${r.optBoolean("liked")} 댓글=${r.optBoolean("commented")} (${r.optString("msg")})")
        setStatus("소셜활동 $processed/$target 완료 · ${delayMs / 1000}초 후 다음")
        web.postDelayed({ if (running) processNextPost() }, delayMs)
    }

    // ---------------------------------------------------------------
    //  모드 2: 서로이웃 신청 수락
    // ---------------------------------------------------------------
    private fun startAccept() {
        setStatus("$currentAccount · 블로그 관리(서로이웃 신청) 여는 중…")
        beginRun(Phase.ACCEPT_RUN, Accounts.acceptUrl(currentAccount), Phase.ACCEPT_RUN)
    }

    /** 웹뷰에 보이는 현재 화면에서 수락 실행(자동 진입 URL에 신청목록이 없을 때 수동 대안) */
    private fun acceptHere() {
        mode = Phase.ACCEPT_RUN
        running = true
        showRunning(true)
        btnAcceptHere.visibility = View.VISIBLE
        phase = Phase.NONE  // 자동 onPageFinished 처리 끔(현재 페이지에서 바로 실행)
        val u = web.url
        if (u == null || u == "about:blank") {
            progress.visibility = View.VISIBLE
            loadPage(Accounts.acceptUrl(currentAccount), Phase.ACCEPT_RUN)
        } else {
            setStatus("현재 화면에서 수락 실행…")
            runJs("window.__NF_acceptAll()")
        }
    }

    private fun handleAccept(json: String) {
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val count = r.optInt("count")
        val why = r.optString("msg")
        if (why == "none") {
            running = false
            progress.visibility = View.GONE
            setStatus("이 화면에 '수락' 버튼이 없어요 — 웹뷰에서 '서로이웃 신청' 목록으로 이동 후 '현재화면 수락'을 누르세요")
            dbg("수락 버튼 0개")
            return
        }
        finishRun("서로이웃 ${count}건 수락 완료")
    }

    // ---------------------------------------------------------------
    //  모드 3: 주제로 새 이웃 찾기/추가
    // ---------------------------------------------------------------
    private fun startGrow() {
        val topic = topicInput.text?.toString()?.trim()
        if (topic.isNullOrBlank()) { toast("주제 키워드를 입력하세요"); return }
        setStatus("'$topic' 블로그 검색 중…")
        beginRun(Phase.SEARCH_COLLECT, Accounts.blogSearchUrl(topic), Phase.SEARCH_COLLECT, growCountInput)
    }

    private fun handleBloggers(json: String) {
        if (!running) return
        val arr = parseArray(json)
        if (arr.length() == 0) { finishRun("검색결과에서 블로거를 찾지 못했어요"); return }
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isNotBlank()) queue.addLast(id)
        }
        dbg("후보 블로거 ${queue.size}명 (목표 ${target}명 신청)")
        setStatus("블로거 ${queue.size}명 · 이웃 신청 시작")
        processNextBlogger()
    }

    private fun processNextBlogger() {
        if (!running) return
        if (added >= target || queue.isEmpty()) {
            finishRun("새 이웃 신청 완료 — ${added}명 신청"); return
        }
        val id = queue.removeFirst()
        setStatus("이웃 신청 ${added}/$target — $id 방문 중…")
        loadPage(Accounts.homeUrl(id), Phase.BLOG_ADD)
        armWatchdog { dbg("응답 없음 → 다음 블로거"); processNextBlogger() }
    }

    private fun doAddNeighbor() {
        if (!running) return
        val payload = JSONObject()
            .put("message", Comments.neighborMessage(neighborMsg.text?.toString().orEmpty(), seq++))
        runJs("window.__NF_addNeighbor($payload)")
    }

    private fun handleAdd(json: String) {
        if (!running) return
        stepToken++
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        when (r.optString("status")) {
            "added" -> { added++; dbg("신청 성공 ($added/$target)") }
            "already" -> dbg("이미 이웃 — 건너뜀")
            "noaddbtn" -> dbg("이웃추가 버튼 없음 — 건너뜀")
            "needlogin" -> { onNeedLoginUi(); return }
            else -> dbg("신청 실패 — 건너뜀")
        }
        setStatus("이웃 신청 $added/$target · ${delayMs / 1000}초 후 다음")
        web.postDelayed({ if (running) processNextBlogger() }, delayMs)
    }

    // ---------------------------------------------------------------
    //  JS → Kotlin 브리지
    // ---------------------------------------------------------------
    inner class NF {
        @JavascriptInterface fun log(m: String) = runOnUiThread { dbg(m) }
        @JavascriptInterface fun onUrls(json: String) = runOnUiThread { handleUrls(json) }
        @JavascriptInterface fun onActed(json: String) = runOnUiThread { handleActed(json) }
        @JavascriptInterface fun onAccept(json: String) = runOnUiThread { handleAccept(json) }
        @JavascriptInterface fun onBloggers(json: String) = runOnUiThread { handleBloggers(json) }
        @JavascriptInterface fun onAdd(json: String) = runOnUiThread { handleAdd(json) }
        @JavascriptInterface fun onLogin(b: Boolean) = runOnUiThread { if (b) onNeedLoginUi() }
        @JavascriptInterface fun onNeedLogin() = runOnUiThread { onNeedLoginUi() }
    }

    // ---------------------------------------------------------------
    //  유틸
    // ---------------------------------------------------------------
    /** 일정 시간 응답이 없으면 다음으로 넘어가는 감시(토큰이 그대로일 때만 발동) */
    private fun armWatchdog(onTimeout: () -> Unit) {
        val tk = stepToken
        web.postDelayed({
            if (running && stepToken == tk) { stepToken++; onTimeout() }
        }, 22000)
    }

    private fun parseArray(json: String): JSONArray =
        runCatching { JSONArray(json) }.getOrDefault(JSONArray())

    private fun setStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun dbg(msg: String) {
        logLines.addLast(msg)
        while (logLines.size > 80) logLines.removeFirst()
        debugLog.text = logLines.joinToString("\n")
        val layout = debugLog.layout ?: return
        val y = layout.getLineTop(debugLog.lineCount) - debugLog.height
        debugLog.scrollTo(0, if (y > 0) y else 0)
    }

    private fun shortUrl(url: String): String =
        if (url.length <= 64) url else url.substring(0, 64) + "…"

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE) {
            if (running) { stopAll(); return }
            if (web.canGoBack()) { web.goBack(); return }
            showRunning(false); return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (Accounts.isLoggedIn()) Accounts.saveCurrentFor(this, currentAccount)
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("NF")
        web.destroy()
        super.onDestroy()
    }
}
