package com.nfriendcl.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

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

    companion object {
        private const val UI_PREFS = "nfriendcl_ui_settings"
        private const val KEY_SOCIAL_COUNT = "social_target_count"
        private const val KEY_DELAY_SECONDS = "action_delay_seconds"
        private const val KEY_GROW_COUNT = "grow_target_count"
        private const val KEY_MESSAGE_SEQUENCE = "message_sequence"
        private const val DEFAULT_COUNT = 20
        private const val DEFAULT_DELAY_SECONDS = 5
        private const val MIN_COUNT = 1
        private const val MAX_COUNT = 500
        private const val MIN_DELAY_SECONDS = 2
        private const val MAX_DELAY_SECONDS = 120
        private const val MAX_FEED_SCROLL_ROUNDS = 120
        private const val INITIAL_SEARCH_ROUNDS = 8
        private const val MAX_SEARCH_SCROLL_ROUNDS = 60
        // 얕은 깊이에서 이만큼 연속으로 새 후보가 안 나오면 주제 소진으로 보고 다음 주제로.
        // (최대 깊이에서는 1회만 비어도 바로 다음 주제로 넘어간다.)
        private const val TOPIC_DRY_LIMIT_SHALLOW = 3
        private const val SOCIAL_IDLE_RETRY_MS = 15_000L
        // 이웃새글 피드가 이만큼 연속으로 새 글을 못 주면 친구 블로그의 지난 글로 보충한다.
        private const val FEED_DRY_LIMIT = 2
        // 한 친구 블로그에서 한 번에 처리할 글 수 상한(도배처럼 보이지 않게 여러 친구로 분산).
        private const val PER_BUDDY_POST_CAP = 12
        private val ACCOUNT_ID_PATTERN = Regex("[a-z0-9_-]{2,50}")
    }

    private enum class Phase {
        NONE, FEED_COLLECT, POST_ACT, ACCEPT_RUN, SEARCH_COLLECT, BLOG_ADD,
        BUDDY_LIST_COLLECT, BUDDY_POSTS_COLLECT
    }

    /** SOCIAL 모드의 글 공급원: 이웃새글 피드 → 마르면 친구 블로그의 지난 글. */
    private enum class SocialSource { FEED, FRIEND_POSTS }
    private data class JsBatch(val token: Int?, val items: JSONArray)

    private lateinit var web: WebView
    private lateinit var form: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var debugLog: TextView
    private lateinit var stopBar: View
    private lateinit var btnStop: Button
    private lateinit var btnAcceptHere: Button
    private lateinit var btnLogin: Button
    private lateinit var btnUseCustom: Button

    private lateinit var accountGroup: MaterialButtonToggleGroup
    private lateinit var customId: EditText
    private lateinit var currentAccountText: TextView
    private lateinit var commentBase: EditText
    private lateinit var swLike: SwitchCompat
    private lateinit var swComment: SwitchCompat
    private lateinit var countInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var neighborMsg: EditText
    private lateinit var topicInput: EditText
    private lateinit var growCountInput: EditText
    private lateinit var growDelayInput: EditText
    private var syncingDelay = false

    private var currentAccount = Accounts.IDS[0]
    private var appliedAccount: String? = null
    private var accountApplyInProgress = false
    private var accountApplyGeneration = 0

    private var mode = Phase.NONE
    private var phase = Phase.NONE
    private var running = false
    private var pageActed = false
    private var lastBlogAddUrl = ""
    private var webPageGeneration = 0

    private val queue = ArrayDeque<String>()
    private var processed = 0      // SOCIAL: 처리한 글 수
    private var added = 0          // GROW: 신청 성공 수
    private var target = 20
    private var delayMs = 5000L
    private var seq = 0            // 댓글/메시지 변형 시드
    private var stepToken = 0      // 응답 없음 감시용 토큰
    private var runToken = 0L      // 이전 실행의 지연 콜백 차단

    private val seenPostKeys = LinkedHashSet<String>()
    private var socialScanRound = 0
    private var socialNoNewScans = 0
    private var feedScrollRounds = 8
    private var socialActionPending = false

    // SOCIAL 보충: 이웃새글이 마르면 친구 블로그의 지난 글을 이어서 처리
    private var socialSource = SocialSource.FEED
    private val socialBloggerQueue = ArrayDeque<String>()
    private val socialBloggersSeen = LinkedHashSet<String>()
    private var socialBuddyListDone = false
    private var socialCurrentBlogger = ""
    // 공급원을 모두 소진한 횟수. 매 사이클마다 더 깊이 스크롤하고 더 오래 쉰다.
    private var socialHarvestCycles = 0

    private val seenBloggerIds = LinkedHashSet<String>()
    private val growTopicDeck = ArrayDeque<String>()
    private val growTopicDepth = mutableMapOf<String, Int>()
    private val growTopicObserved = mutableMapOf<String, Int>()
    private var manualTopics = emptyList<String>()
    private var lastGrowTopic: String? = null
    private var currentGrowTopic = ""
    private var growNoNewSearches = 0
    // 현재 주제에서 연속으로 새 후보가 안 나온 스캔 수(주제 소진 판정용)
    private var growTopicDryStreak = 0
    private var searchScrollRounds = INITIAL_SEARCH_ROUNDS
    private var pendingBlogger = ""
    private var pendingNeighborMessage = ""
    private var neighborFormVisited = false

    private val logLines = ArrayDeque<String>()
    private val uiPrefs by lazy { getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

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
        swLike = findViewById(R.id.swLike)
        swComment = findViewById(R.id.swComment)
        countInput = findViewById(R.id.countInput)
        delayInput = findViewById(R.id.delayInput)
        neighborMsg = findViewById(R.id.neighborMsg)
        topicInput = findViewById(R.id.topicInput)
        growCountInput = findViewById(R.id.growCountInput)
        growDelayInput = findViewById(R.id.growDelayInput)
        btnLogin = findViewById(R.id.btnLogin)
        btnUseCustom = findViewById(R.id.btnUseCustom)

        commentBase.setText(Comments.DEFAULT_BASE)
        neighborMsg.setText(Comments.DEFAULT_NEIGHBOR_MSG)
        restoreUiSettings()
        setupSettingsPersistence()
        
        findViewById<Button>(R.id.btnRefreshComment).setOnClickListener {
            val base = commentBase.text?.toString().orEmpty()
            toast("변형 예시:\n" + Comments.comment(base, nextMessageSequence()))
        }
        findViewById<Button>(R.id.btnRefreshNeighborMsg).setOnClickListener {
            val base = neighborMsg.text?.toString().orEmpty()
            toast("변형 예시:\n" + Comments.neighborMessage(base, nextMessageSequence()))
        }

        setupWeb()
        setupAccounts()

        findViewById<Button>(R.id.btnSocial).setOnClickListener { startSocial() }
        findViewById<Button>(R.id.btnGrow).setOnClickListener { startGrow() }
        btnUseCustom.setOnClickListener { useCustomId() }
        btnLogin.setOnClickListener { openLogin() }
        btnStop.setOnClickListener { stopAll() }
        btnAcceptHere.setOnClickListener { acceptHere() }

        showRunning(false)
    }

    // ---------------------------------------------------------------
    //  계정
    // ---------------------------------------------------------------
    private fun setupAccounts() {
        val acc1 = findViewById<MaterialButton>(R.id.btnAcc1)
        val acc2 = findViewById<MaterialButton>(R.id.btnAcc2)
        acc1.text = Accounts.IDS[0]
        acc2.text = Accounts.IDS[1]

        val saved = Accounts.loadSelection(this)
        currentAccount = saved.id
        if (saved.isCustom) {
            accountGroup.clearChecked()
            customId.setText(saved.id)
        } else {
            accountGroup.check(if (saved.id == Accounts.IDS[1]) R.id.btnAcc2 else R.id.btnAcc1)
        }

        accountGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val picked = when (checkedId) {
                R.id.btnAcc1 -> Accounts.IDS[0]
                R.id.btnAcc2 -> Accounts.IDS[1]
                else -> return@addOnButtonCheckedListener
            }
            switchAccount(picked, isCustom = false)
        }
        applyAccount(currentAccount)
    }

    private fun useCustomId() {
        val id = customId.text?.toString()?.trim()?.lowercase(Locale.ROOT)
        if (id.isNullOrBlank()) { toast("아이디를 입력하세요"); return }
        if (!ACCOUNT_ID_PATTERN.matches(id)) {
            toast("아이디는 영문 소문자, 숫자, _, -만 입력하세요")
            return
        }
        accountGroup.clearChecked()
        customId.setText(id)
        switchAccount(id, isCustom = true)
        toast("$id 계정 사용")
    }

    private fun switchAccount(targetId: String, isCustom: Boolean) {
        if (running) {
            toast("진행 중인 작업을 정지한 뒤 계정을 바꾸세요")
            return
        }
        if (accountApplyInProgress) {
            toast("계정 전환이 끝난 뒤 다시 선택하세요")
            return
        }
        Accounts.saveSelection(this, targetId, isCustom)
        if (targetId == currentAccount && appliedAccount == targetId) {
            updateAccountLabel(Accounts.hasSession(this, targetId))
            return
        }
        appliedAccount?.let { applied ->
            if (Accounts.isLoggedIn()) Accounts.saveCurrentFor(this, applied)
        }
        currentAccount = targetId
        applyAccount(targetId)
    }

    private fun applyAccount(targetId: String) {
        val generation = ++accountApplyGeneration
        accountApplyInProgress = true
        setAccountControlsEnabled(false)
        currentAccountText.text = "현재 계정: $targetId (전환 중…)"
        Accounts.applyTo(this, targetId) { had ->
            runOnUiThread {
                if (generation != accountApplyGeneration) return@runOnUiThread
                accountApplyInProgress = false
                appliedAccount = targetId
                setAccountControlsEnabled(true)
                if (currentAccount == targetId) updateAccountLabel(had)
            }
        }
    }

    private fun setAccountControlsEnabled(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.btnAcc1).isEnabled = enabled
        findViewById<MaterialButton>(R.id.btnAcc2).isEnabled = enabled
        customId.isEnabled = enabled
        btnUseCustom.isEnabled = enabled
        btnLogin.isEnabled = enabled
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
            // 자동화 중에는 블로그/검색/로그인 외 다른 네이버 메뉴(카페·뉴스 등)로
            // 빠지지 않게 메인 프레임 이동을 허용 목록으로 제한한다.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (!running) return false
                val req = request ?: return false
                if (!req.isForMainFrame) return false
                if (isAutomationHostAllowed(req.url)) return false
                dbg("이동 차단: ${req.url.host ?: ""} (자동화 외 메뉴)")
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                webPageGeneration++
                if (running && phase == Phase.BLOG_ADD) lastBlogAddUrl = ""
                // 리다이렉트 등 두 번째 내비게이션이 일어나도 새 페이지에서 단계 처리가
                // 다시 걸리도록 초기화(안 하면 이전 예약이 세대 불일치로 조용히 사라져 멈춘다).
                if (running) pageActed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                val finishedPage = webPageGeneration
                if (
                    !accountApplyInProgress &&
                    appliedAccount == currentAccount &&
                    Accounts.isLoggedIn()
                ) {
                    Accounts.saveCurrentFor(this@MainActivity, appliedAccount!!)
                    if (!running) {
                        updateAccountLabel(true)
                        setStatus("$currentAccount 로그인 완료 — '■ 정지 / 홈'을 눌러 돌아가세요")
                        progress.visibility = View.GONE
                    }
                }
                if (!running) return
                if (url.contains("nid.naver.com")) { onNeedLoginUi(); return }
                if (pageActed && phase != Phase.BLOG_ADD) return
                when (phase) {
                    Phase.FEED_COLLECT -> {
                        pageActed = true
                        postForPage(900, finishedPage) {
                            if (phase == Phase.FEED_COLLECT) {
                                stepToken++ // 페이지 로드 감시 해제, 수집 감시로 교대
                                val wanted = (
                                    seenPostKeys.size + (target - processed) + 12
                                ).coerceIn(20, 2_000)
                                val collectionToken = stepToken
                                runJs(
                                    "window.__NF_collectFeed($wanted,$feedScrollRounds,$collectionToken)"
                                )
                                val timeout = (feedScrollRounds * 3_000L + 15_000L)
                                    .coerceAtMost(390_000L)
                                armWatchdog(timeout) {
                                    dbg("피드 수집 응답 없음 → 범위를 늘려 다시 시도")
                                    scheduleSocialRefill()
                                }
                            }
                        }
                    }
                    Phase.POST_ACT -> {
                        pageActed = true
                        postForPage(1500, finishedPage) {
                            if (phase == Phase.POST_ACT) doLikeAndComment()
                        }
                    }
                    Phase.ACCEPT_RUN -> {
                        pageActed = true
                        postForPage(1100, finishedPage) {
                            if (phase == Phase.ACCEPT_RUN) {
                                runJs("window.__NF_acceptAll($stepToken)")
                            }
                        }
                    }
                    Phase.SEARCH_COLLECT -> {
                        pageActed = true
                        postForPage(900, finishedPage) {
                            if (phase == Phase.SEARCH_COLLECT) {
                                stepToken++ // 페이지 로드 감시 해제, 수집 감시로 교대
                                val observed = growTopicObserved[currentGrowTopic] ?: 0
                                val wanted = (observed + max(20, target - added) + 12)
                                    .coerceIn(20, 2_000)
                                val collectionToken = stepToken
                                runJs(
                                    "window.__NF_collectBloggers(" +
                                        "${JSONObject.quote(currentAccount)},$wanted," +
                                        "$searchScrollRounds,$collectionToken)"
                                )
                                val timeout = (searchScrollRounds * 3_000L + 15_000L)
                                    .coerceAtMost(210_000L)
                                armWatchdog(timeout) {
                                    dbg("검색 수집 응답 없음 → 다음 주제로 이동")
                                    scheduleGrowRefill()
                                }
                            }
                        }
                    }
                    Phase.BLOG_ADD -> {
                        // 홈 → 신청 폼 → 결과 페이지로 이동할 때마다 같은 pending 요청을 한 번씩 이어서 처리한다.
                        if (url == lastBlogAddUrl || pendingBlogger.isBlank()) return
                        lastBlogAddUrl = url
                        if (url.contains("BuddyAddForm")) neighborFormVisited = true
                        pageActed = true
                        val wait = if (url.contains("BuddyAddForm")) 1_000L else 1_300L
                        postForPage(wait, finishedPage) {
                            if (phase == Phase.BLOG_ADD) doAddNeighbor()
                        }
                    }
                    Phase.BUDDY_POSTS_COLLECT -> {
                        pageActed = true
                        postForPage(900, finishedPage) {
                            if (phase == Phase.BUDDY_POSTS_COLLECT) {
                                stepToken++ // 페이지 로드 감시 해제, 수집 감시로 교대
                                val remaining = (target - processed).coerceAtLeast(1)
                                // 한 친구당 상한(+여유분)만큼만 긁어 여러 친구로 분산하고,
                                // 재순환 때마다 상한과 스크롤을 늘려 더 오래된 글까지 본다.
                                val cap = PER_BUDDY_POST_CAP + socialHarvestCycles * 8
                                val wanted = (minOf(remaining, cap) + 6).coerceIn(6, 150)
                                val rounds = (12 + socialHarvestCycles * 10)
                                    .coerceAtMost(60)
                                val collectionToken = stepToken
                                runJs(
                                    "window.__NF_collectFeed($wanted,$rounds,$collectionToken)"
                                )
                                val timeout = (rounds * 3_000L + 15_000L).coerceAtMost(200_000L)
                                armWatchdog(timeout) {
                                    dbg("친구 글 수집 응답 없음 → 다음 친구")
                                    advanceFriendHarvest()
                                }
                            }
                        }
                    }
                    Phase.BUDDY_LIST_COLLECT -> {
                        pageActed = true
                        postForPage(1100, finishedPage) {
                            if (phase == Phase.BUDDY_LIST_COLLECT) {
                                stepToken++ // 페이지 로드 감시 해제, 수집 감시로 교대
                                val wanted = (target - processed + 40).coerceIn(40, 2_000)
                                val rounds = 30
                                val collectionToken = stepToken
                                runJs(
                                    "window.__NF_collectBuddies(" +
                                        "${JSONObject.quote(currentAccount)},$wanted," +
                                        "$rounds,$collectionToken)"
                                )
                                val timeout = (rounds * 2_500L + 15_000L).coerceAtMost(120_000L)
                                armWatchdog(timeout) {
                                    dbg("이웃 목록 수집 응답 없음 → 마무리")
                                    advanceFriendHarvest()
                                }
                            }
                        }
                    }
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

    /** 자동화 중 이동을 허용하는 호스트: 블로그 계열 · 블로그 검색 · 네이버 로그인만. */
    private fun isAutomationHostAllowed(uri: Uri?): Boolean {
        val host = uri?.host?.lowercase(Locale.ROOT) ?: return false
        return host == "blog.naver.com" ||
            host.endsWith(".blog.naver.com") ||
            host == "m.search.naver.com" ||
            host == "search.naver.com" ||
            host == "nid.naver.com"
    }

    private fun runJs(call: String) {
        if (!running) return
        val expectedRun = runToken
        val expectedStep = stepToken
        val expectedPage = webPageGeneration
        web.evaluateJavascript(AutomationJs.SCRIPT) {
            if (
                running &&
                runToken == expectedRun &&
                stepToken == expectedStep &&
                webPageGeneration == expectedPage
            ) {
                web.evaluateJavascript(call, null)
            }
        }
    }

    // ---------------------------------------------------------------
    //  공통 실행 제어
    // ---------------------------------------------------------------
    private fun restoreUiSettings() {
        val socialCount = savedInt(KEY_SOCIAL_COUNT, DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)
        val delaySeconds = savedInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
            .coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
        val growCount = savedInt(KEY_GROW_COUNT, DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)
        countInput.setText(socialCount.toString())
        delayInput.setText(delaySeconds.toString())
        growCountInput.setText(growCount.toString())
        growDelayInput.setText(delaySeconds.toString())
        seq = savedInt(KEY_MESSAGE_SEQUENCE, 0).coerceAtLeast(0)
    }

    private fun setupSettingsPersistence() {
        countInput.doAfterTextChanged {
            persistValidNumber(KEY_SOCIAL_COUNT, it?.toString(), MIN_COUNT, MAX_COUNT)
        }
        delayInput.doAfterTextChanged {
            persistValidNumber(KEY_DELAY_SECONDS, it?.toString(), MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
            mirrorDelay(delayInput, growDelayInput, it?.toString())
        }
        growCountInput.doAfterTextChanged {
            persistValidNumber(KEY_GROW_COUNT, it?.toString(), MIN_COUNT, MAX_COUNT)
        }
        // 동작 간격은 두 섹션(소셜/이웃찾기)이 같은 값을 공유하고 같은 키에 저장한다.
        growDelayInput.doAfterTextChanged {
            persistValidNumber(KEY_DELAY_SECONDS, it?.toString(), MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
            mirrorDelay(growDelayInput, delayInput, it?.toString())
        }
    }

    /** 한쪽 동작 간격 입력을 다른 쪽에 반영(무한 재귀 방지 가드 포함). */
    private fun mirrorDelay(from: EditText, to: EditText, raw: String?) {
        if (syncingDelay) return
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || to.text?.toString() == value) return
        syncingDelay = true
        to.setText(value)
        syncingDelay = false
    }

    private fun savedInt(key: String, fallback: Int): Int =
        runCatching { uiPrefs.getInt(key, fallback) }.getOrElse {
            runCatching { uiPrefs.getString(key, null)?.toIntOrNull() ?: fallback }.getOrDefault(fallback)
        }

    private fun persistValidNumber(key: String, raw: String?, min: Int, max: Int) {
        val value = raw?.trim()?.toIntOrNull() ?: return
        if (value in min..max) uiPrefs.edit().putInt(key, value).apply()
    }

    private fun persistUiSettings() {
        persistValidNumber(KEY_SOCIAL_COUNT, countInput.text?.toString(), MIN_COUNT, MAX_COUNT)
        persistValidNumber(KEY_DELAY_SECONDS, delayInput.text?.toString(), MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
        persistValidNumber(KEY_DELAY_SECONDS, growDelayInput.text?.toString(), MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)
        persistValidNumber(KEY_GROW_COUNT, growCountInput.text?.toString(), MIN_COUNT, MAX_COUNT)
    }

    private fun nextMessageSequence(): Int {
        seq = if (seq >= Int.MAX_VALUE - 1) 1 else seq + 1
        uiPrefs.edit().putInt(KEY_MESSAGE_SEQUENCE, seq).apply()
        return seq
    }

    private fun readBounded(view: EditText, key: String, min: Int, max: Int, fallback: Int): Int {
        val saved = savedInt(key, fallback).coerceIn(min, max)
        val value = view.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: saved
        if (view.text?.toString() != value.toString()) view.setText(value.toString())
        uiPrefs.edit().putInt(key, value).apply()
        return value
    }

    private fun readSettings(countView: EditText, delayView: EditText) {
        val countKey = if (countView === growCountInput) KEY_GROW_COUNT else KEY_SOCIAL_COUNT
        target = readBounded(countView, countKey, MIN_COUNT, MAX_COUNT, DEFAULT_COUNT)
        val sec = readBounded(
            delayView,
            KEY_DELAY_SECONDS,
            MIN_DELAY_SECONDS,
            MAX_DELAY_SECONDS,
            DEFAULT_DELAY_SECONDS
        )
        delayMs = sec * 1000L
    }

    /**
     * 스팸/차단 방지를 위해 기본 간격에 ±몇 초의 난수를 더한 값을 돌려준다.
     * 가끔(약 12%) 사람처럼 몇 초 더 길게 쉰다. 최소 1.2초는 보장.
     */
    private fun jitteredDelay(): Long {
        val base = delayMs
        val spread = minOf(3500L, maxOf(1000L, base / 2))
        var d = base + Random.nextLong(-spread, spread + 1)
        if (Random.nextInt(100) < 12) d += Random.nextLong(2000L, 6000L)
        return d.coerceAtLeast(1200L)
    }

    private fun beginRun(
        m: Phase,
        firstUrl: String,
        firstPhase: Phase,
        countView: EditText = countInput,
        delayView: EditText = delayInput
    ) {
        if (accountApplyInProgress) {
            toast("계정 전환이 끝난 뒤 시작하세요")
            return
        }
        if (!ensureLoggedInOrPrompt()) {
            // 로그인 안 됐어도 일단 페이지를 열어 로그인 유도(웹뷰 보임)
        }
        readSettings(countView, delayView)
        invalidateScheduledWork()
        mode = m
        running = true
        processed = 0
        added = 0
        queue.clear()
        logLines.clear(); debugLog.text = ""
        showRunning(true)
        btnAcceptHere.visibility = if (m == Phase.ACCEPT_RUN) View.VISIBLE else View.GONE
        progress.visibility = View.VISIBLE
        loadPage(firstUrl, firstPhase)
        armCollectPageWatchdog(firstPhase)
    }

    /**
     * 수집용 페이지 이동이 리다이렉트/로드 실패로 조용히 사라져도 멈추지 않도록
     * 보조 감시를 건다. 수집이 정상적으로 시작되면 stepToken 교대로 자동 해제된다.
     */
    private fun armCollectPageWatchdog(p: Phase) {
        when (p) {
            Phase.FEED_COLLECT -> armWatchdog(45_000) {
                dbg("피드 페이지 응답 없음 → 재시도")
                scheduleSocialRefill()
            }
            Phase.SEARCH_COLLECT -> armWatchdog(45_000) {
                dbg("검색 페이지 응답 없음 → 다음 주제로 재시도")
                scheduleGrowRefill()
            }
            else -> {}
        }
    }

    private fun ensureLoggedInOrPrompt(): Boolean {
        return Accounts.isLoggedIn() || Accounts.hasSession(this, currentAccount)
    }

    private fun loadPage(url: String, p: Phase) {
        phase = p
        pageActed = false
        lastBlogAddUrl = ""
        stepToken++
        dbg("이동: ${shortUrl(url)}")
        web.loadUrl(url)
    }

    private fun showRunning(run: Boolean) {
        form.visibility = if (run) View.GONE else View.VISIBLE
        web.visibility = if (run) View.VISIBLE else View.GONE
        stopBar.visibility = if (run) View.VISIBLE else View.GONE
        debugLog.visibility = if (run) View.VISIBLE else View.GONE
        setAccountControlsEnabled(!run && !accountApplyInProgress)
        if (run) status.visibility = View.VISIBLE
    }

    private fun stopAll() {
        invalidateScheduledWork()
        running = false
        mode = Phase.NONE
        phase = Phase.NONE
        queue.clear()
        progress.visibility = View.GONE
        showRunning(false)
        setStatus("정지됨")
    }

    private fun finishRun(msg: String) {
        invalidateScheduledWork()
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
        invalidateScheduledWork()
        running = false
        progress.visibility = View.GONE
        setStatus("로그인이 필요합니다 — 웹뷰에서 로그인 후 같은 버튼을 다시 누르세요")
        dbg("로그인 대기")
    }

    /** 첫 화면에서 바로 네이버 로그인 → 완료 시 현재 계정 세션 자동 저장('정지/홈'으로 복귀) */
    private fun openLogin() {
        invalidateScheduledWork()
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
        if (!swLike.isChecked && !swComment.isChecked) {
            toast("공감 또는 댓글 중 하나 이상을 켜세요")
            return
        }
        seenPostKeys.clear()
        socialScanRound = 0
        socialNoNewScans = 0
        feedScrollRounds = 8
        socialActionPending = false
        socialSource = SocialSource.FEED
        socialBloggerQueue.clear()
        socialBloggersSeen.clear()
        socialBuddyListDone = false
        socialCurrentBlogger = ""
        socialHarvestCycles = 0
        setStatus("$currentAccount · 이웃새글 피드 여는 중…")
        beginRun(Phase.FEED_COLLECT, Accounts.FEED_URL, Phase.FEED_COLLECT, countInput, delayInput)
    }

    private fun handleUrls(json: String) {
        if (!running || mode != Phase.FEED_COLLECT) return
        if (phase != Phase.FEED_COLLECT && phase != Phase.BUDDY_POSTS_COLLECT) return
        val batch = parseBatch(json)
        if (batch.token != null && batch.token != stepToken) {
            dbg("지난 글 수집 응답 무시")
            return
        }
        stepToken++ // 수집 감시 무효화
        val arr = batch.items
        var fresh = 0
        for (i in 0 until arr.length()) {
            val u = arr.optString(i)
            if (u.isBlank()) continue
            rememberBlogger(u)
            val key = canonicalPostKey(u)
            if (seenPostKeys.add(key)) {
                queue.addLast(u)
                fresh++
            }
        }
        val label = if (phase == Phase.BUDDY_POSTS_COLLECT) "친구글" else "피드"
        dbg("$label ${arr.length()}개 확인 · 신규 ${fresh}개 · 누적 ${seenPostKeys.size}개")
        if (fresh == 0) {
            if (socialSource == SocialSource.FEED) scheduleSocialRefill() else advanceFriendHarvest()
            return
        }
        socialNoNewScans = 0
        setStatus("신규 글 ${fresh}개 확보 · 소셜활동 ${processed}/$target")
        processNextPost()
    }

    /** 글 URL에서 블로거 아이디를 뽑아 친구 글 보충 대상으로 기억한다. */
    private fun rememberBlogger(url: String) {
        val id = blogIdOf(url) ?: return
        if (id == currentAccount) return
        if (socialBloggersSeen.add(id)) socialBloggerQueue.addLast(id)
    }

    private fun blogIdOf(url: String): String? {
        val id = runCatching {
            val uri = Uri.parse(url)
            uri.getQueryParameter("blogId")?.takeIf { it.isNotBlank() }
                ?: uri.pathSegments.orEmpty().firstOrNull { seg -> seg.any(Char::isLetter) }
        }.getOrNull()?.trim()?.lowercase(Locale.ROOT) ?: return null
        return id.takeIf { ACCOUNT_ID_PATTERN.matches(it) }
    }

    private fun processNextPost() {
        if (!running || mode != Phase.FEED_COLLECT) return
        if (processed >= target) {
            finishRun("소셜활동 완료 — 글 ${processed}개 처리")
            return
        }
        if (queue.isEmpty()) {
            requestMoreSocialPosts()
            return
        }
        val url = queue.removeFirst()
        socialActionPending = true
        setStatus("소셜활동 ${processed + 1}/$target — 글 여는 중…")
        loadPage(url, Phase.POST_ACT)
        armWatchdog(30_000) {
            socialActionPending = false
            dbg("글 응답 없음 → 완료 수에는 넣지 않고 다음 글")
            postForRun(1_000) { processNextPost() }
        }
    }

    private fun doLikeAndComment() {
        if (!running || mode != Phase.FEED_COLLECT || phase != Phase.POST_ACT) return
        val payload = JSONObject()
            .put("like", swLike.isChecked)
            .put("comment", swComment.isChecked)
            .put("text", Comments.comment(commentBase.text?.toString().orEmpty(), nextMessageSequence()))
            .put("token", stepToken)
        runJs("window.__NF_likeAndComment($payload)")
    }

    private fun handleActed(json: String) {
        if (!running || mode != Phase.FEED_COLLECT || phase != Phase.POST_ACT) return
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val token = r.optInt("token", -1)
        if (token != stepToken) {
            dbg("지난 글의 늦은 응답 무시")
            return
        }
        stepToken++  // 감시 무효화
        socialActionPending = false
        if (r.optBoolean("needLogin") || r.optString("msg") == "login") {
            onNeedLoginUi()
            return
        }
        val liked = r.optBoolean("liked")
        val commented = r.optBoolean("commented")
        val completed = (swLike.isChecked && liked) || (swComment.isChecked && commented)
        if (completed) processed++
        dbg(
            "글 처리: 공감=$liked 댓글=$commented (${r.optString("msg")})" +
                if (completed) " · 완료 $processed/$target" else " · 성공 수 미반영"
        )
        val wait = jitteredDelay()
        setStatus("소셜활동 $processed/$target · ${wait / 1000}초 후 다음")
        postForRun(wait) { processNextPost() }
    }

    private fun requestMoreSocialPosts() {
        if (!running || mode != Phase.FEED_COLLECT) return
        if (socialActionPending || queue.isNotEmpty()) return
        if (processed >= target) {
            finishRun("소셜활동 완료 — 글 ${processed}개 처리")
            return
        }
        // 아직 피드가 살아 있으면 시간 범위를 넓혀 이웃새글을 더 긁어온다.
        if (socialSource == SocialSource.FEED && socialNoNewScans < FEED_DRY_LIMIT) {
            socialScanRound++
            feedScrollRounds = (8 + socialScanRound * 8).coerceAtMost(MAX_FEED_SCROLL_ROUNDS)
            setStatus(
                "목표 ${processed}/$target · 이웃 새글 시간 범위를 더 넓혀 찾는 중 " +
                    "(${feedScrollRounds}단계)"
            )
            loadPage(Accounts.FEED_URL, Phase.FEED_COLLECT)
            armCollectPageWatchdog(Phase.FEED_COLLECT)
            return
        }
        // 피드가 말랐다 → 친구 블로그의 지난 글로 목표 숫자까지 계속 이어간다.
        if (socialSource == SocialSource.FEED) {
            socialSource = SocialSource.FRIEND_POSTS
            dbg("이웃 새글이 부족해 친구들의 지난 글을 더 찾습니다")
        }
        advanceFriendHarvest()
    }

    /**
     * 친구 블로그의 지난 글을 한 명씩 순회하며 보충한다.
     * 피드에서 본 이웃 → (부족하면) 이웃 목록에서 더 모은 친구 순으로 진행하고,
     * 더 이상 찾을 곳이 없을 때만 종료한다.
     */
    private fun advanceFriendHarvest() {
        if (!running || mode != Phase.FEED_COLLECT) return
        if (socialActionPending || queue.isNotEmpty()) return
        if (processed >= target) {
            finishRun("소셜활동 완료 — 글 ${processed}개 처리")
            return
        }
        if (socialBloggerQueue.isNotEmpty()) {
            socialCurrentBlogger = socialBloggerQueue.removeFirst()
            setStatus("친구 '$socialCurrentBlogger' 지난 글에서 이어가는 중 · ${processed}/$target")
            loadPage(Accounts.postListUrl(socialCurrentBlogger), Phase.BUDDY_POSTS_COLLECT)
            armWatchdog(45_000) {
                dbg("'$socialCurrentBlogger' 글 응답 없음 → 다음 친구")
                postForRun(1_000) { advanceFriendHarvest() }
            }
            return
        }
        if (!socialBuddyListDone) {
            socialBuddyListDone = true
            setStatus("이웃 목록에서 친구 블로그를 더 모으는 중 · ${processed}/$target")
            loadPage(Accounts.buddyListUrl(currentAccount), Phase.BUDDY_LIST_COLLECT)
            armWatchdog(45_000) {
                dbg("이웃 목록 응답 없음 → 다음 사이클")
                postForRun(1_000) { advanceFriendHarvest() }
            }
            return
        }
        // 목표를 못 채웠으면 종료하지 않는다. 잠시 쉬었다가 피드부터 다시 순환하고,
        // 알고 있는 친구 전원을 더 깊은 스크롤로 재방문해 지난 글을 계속 보충한다.
        socialHarvestCycles++
        socialSource = SocialSource.FEED
        socialNoNewScans = 0
        socialScanRound = 0
        feedScrollRounds = 8
        socialBuddyListDone = false
        socialBloggerQueue.clear()
        socialBloggersSeen.shuffled().forEach(socialBloggerQueue::addLast)
        val waitMs = max(delayMs, minOf(20_000L + socialHarvestCycles * 15_000L, 120_000L))
        setStatus(
            "한 바퀴 다 돌았어요 · ${processed}/$target · ${waitMs / 1000}초 후 " +
                "새 글부터 다시 탐색 (${socialHarvestCycles}번째 재순환)"
        )
        dbg("공급원 소진 → ${waitMs / 1000}초 뒤 재순환 (친구 ${socialBloggerQueue.size}명 재방문 예정)")
        postForRun(waitMs) {
            if (queue.isEmpty() && !socialActionPending) requestMoreSocialPosts()
        }
    }

    private fun handleBuddies(json: String) {
        if (!running || mode != Phase.FEED_COLLECT || phase != Phase.BUDDY_LIST_COLLECT) return
        val batch = parseBatch(json)
        if (batch.token != null && batch.token != stepToken) {
            dbg("지난 이웃 목록 응답 무시")
            return
        }
        stepToken++
        val arr = batch.items
        var fresh = 0
        for (i in 0 until arr.length()) {
            val id = arr.optString(i).trim().lowercase(Locale.ROOT)
            if (!ACCOUNT_ID_PATTERN.matches(id) || id == currentAccount) continue
            if (socialBloggersSeen.add(id)) {
                socialBloggerQueue.addLast(id)
                fresh++
            }
        }
        dbg("이웃 목록 ${arr.length()}명 확인 · 새 친구 ${fresh}명 · 누적 ${socialBloggersSeen.size}명")
        advanceFriendHarvest()
    }

    private fun scheduleSocialRefill() {
        if (!running || mode != Phase.FEED_COLLECT || processed >= target) return
        socialNoNewScans++
        val waitMs = if (socialNoNewScans <= 2) 2_000L else max(delayMs, SOCIAL_IDLE_RETRY_MS)
        setStatus(
            "새 이웃 글 대기 중 · 목표 ${processed}/$target · ${waitMs / 1000}초 후 더 오래된 글 재탐색"
        )
        val expectedStep = stepToken
        postForRun(waitMs) {
            if (
                stepToken == expectedStep &&
                !socialActionPending &&
                queue.isEmpty()
            ) requestMoreSocialPosts()
        }
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
        invalidateScheduledWork()
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
            runJs("window.__NF_acceptAll($stepToken)")
        }
    }

    private fun handleAccept(json: String) {
        if (!running || mode != Phase.ACCEPT_RUN) return
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val token = r.optInt("token", -1)
        if (token != stepToken) {
            dbg("지난 수락 응답 무시")
            return
        }
        stepToken++
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
        manualTopics = topicInput.text?.toString().orEmpty()
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        seenBloggerIds.clear()
        growTopicDeck.clear()
        growTopicDepth.clear()
        growTopicObserved.clear()
        lastGrowTopic = null
        growNoNewSearches = 0
        growTopicDryStreak = 0
        pendingBlogger = ""
        pendingNeighborMessage = ""

        currentGrowTopic = nextGrowTopic()
        searchScrollRounds = INITIAL_SEARCH_ROUNDS
        growTopicDepth[currentGrowTopic] = searchScrollRounds
        val source = if (manualTopics.isEmpty()) "자동 주제" else "입력 주제"
        setStatus("$source · '$currentGrowTopic' 블로그 검색 중…")
        beginRun(
            Phase.SEARCH_COLLECT,
            Accounts.blogSearchUrl(currentGrowTopic),
            Phase.SEARCH_COLLECT,
            growCountInput,
            growDelayInput
        )
    }

    private fun handleBloggers(json: String) {
        if (!running || mode != Phase.SEARCH_COLLECT || phase != Phase.SEARCH_COLLECT) return
        val batch = parseBatch(json)
        if (batch.token != null && batch.token != stepToken) {
            dbg("지난 검색 수집 응답 무시")
            return
        }
        stepToken++ // 검색 수집 감시 무효화
        val arr = batch.items
        growTopicObserved[currentGrowTopic] = max(
            growTopicObserved[currentGrowTopic] ?: 0,
            arr.length()
        )
        val fresh = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optString(i).trim().lowercase(Locale.ROOT)
            if (!ACCOUNT_ID_PATTERN.matches(id) || id == currentAccount) continue
            if (seenBloggerIds.add(id)) fresh.add(id)
        }
        fresh.shuffled().forEach(queue::addLast)
        dbg("'$currentGrowTopic' ${arr.length()}명 확인 · 신규 ${fresh.size}명 · 누적 ${seenBloggerIds.size}명")
        if (fresh.isEmpty()) {
            scheduleGrowRefill()
            return
        }
        growNoNewSearches = 0
        growTopicDryStreak = 0
        setStatus("신규 후보 ${fresh.size}명 · 이웃 신청 $added/$target")
        processNextBlogger()
    }

    private fun processNextBlogger() {
        if (!running || mode != Phase.SEARCH_COLLECT) return
        if (added >= target) {
            finishRun("새 이웃 신청 완료 — ${added}명 신청")
            return
        }
        if (queue.isEmpty()) {
            requestMoreGrowCandidates()
            return
        }
        pendingBlogger = queue.removeFirst()
        neighborFormVisited = false
        pendingNeighborMessage = Comments.neighborMessage(
            neighborMsg.text?.toString().orEmpty(),
            nextMessageSequence()
        )
        setStatus("이웃 신청 ${added}/$target — $pendingBlogger 방문 중…")
        loadPage(Accounts.homeUrl(pendingBlogger), Phase.BLOG_ADD)
        armWatchdog(60_000) {
            dbg("$pendingBlogger 응답 없음 → 완료 수에는 넣지 않고 다음 후보")
            pendingBlogger = ""
            pendingNeighborMessage = ""
            neighborFormVisited = false
            postForRun(1_000) { processNextBlogger() }
        }
    }

    private fun doAddNeighbor() {
        if (!running || mode != Phase.SEARCH_COLLECT || phase != Phase.BLOG_ADD) return
        if (pendingBlogger.isBlank()) return
        val payload = JSONObject()
            .put("message", pendingNeighborMessage)
            .put("token", stepToken)
            .put("bloggerId", pendingBlogger)
            .put("verifyResult", neighborFormVisited)
        runJs("window.__NF_addNeighbor($payload)")
    }

    private fun handleAdd(json: String) {
        if (!running || mode != Phase.SEARCH_COLLECT || phase != Phase.BLOG_ADD) return
        val r = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val token = r.optInt("token", -1)
        if (token != stepToken) {
            dbg("지난 후보의 늦은 응답 무시")
            return
        }
        stepToken++
        when (r.optString("status")) {
            "added" -> { added++; dbg("신청 성공 ($added/$target)") }
            "already" -> dbg("이미 이웃 — 건너뜀")
            "noaddbtn" -> dbg("이웃추가 버튼 없음 — 건너뜀")
            "needlogin" -> { onNeedLoginUi(); return }
            "blocked" -> {
                finishRun("네이버 신청 한도 또는 접근 제한이 감지됐어요 — 잠시 후 다시 시도하세요")
                return
            }
            "unverified" -> dbg("신청 여부를 확인하지 못함 — 성공 수에는 넣지 않음")
            "failed" -> dbg("신청 버튼 처리 실패 — 다음 후보")
            else -> dbg("신청 실패 — 다음 후보")
        }
        pendingBlogger = ""
        pendingNeighborMessage = ""
        neighborFormVisited = false
        val wait = jitteredDelay()
        setStatus("이웃 신청 $added/$target · ${wait / 1000}초 후 다음")
        postForRun(wait) { processNextBlogger() }
    }

    /**
     * 후보가 떨어지면: 현재 주제를 더 깊이(+8단계) 다시 검색해 최대한 많이 신청하고,
     * 같은 주제에서 연속으로 새 후보가 안 나오면(얕은 깊이 3회, 최대 깊이 1회)
     * 다음 랜덤 주제로 넘어간다.
     */
    private fun requestMoreGrowCandidates() {
        if (!running || mode != Phase.SEARCH_COLLECT) return
        if (pendingBlogger.isNotBlank() || queue.isNotEmpty()) return
        if (added >= target) {
            finishRun("새 이웃 신청 완료 — ${added}명 신청")
            return
        }
        val atMaxDepth = searchScrollRounds >= MAX_SEARCH_SCROLL_ROUNDS
        val exhausted = currentGrowTopic.isBlank() ||
            growTopicDryStreak >= (if (atMaxDepth) 1 else TOPIC_DRY_LIMIT_SHALLOW)
        if (exhausted) {
            val previous = currentGrowTopic
            currentGrowTopic = nextGrowTopic()
            // 예전에 판 주제가 다시 오면 이전 깊이에서 이어서(얕은 중복 스캔 방지)
            searchScrollRounds = (growTopicDepth[currentGrowTopic] ?: INITIAL_SEARCH_ROUNDS)
                .coerceAtMost(MAX_SEARCH_SCROLL_ROUNDS)
            growTopicDryStreak = 0
            if (previous.isNotBlank()) dbg("'$previous' 소진 → 다음 주제 '$currentGrowTopic'")
        } else {
            searchScrollRounds = (searchScrollRounds + 8).coerceAtMost(MAX_SEARCH_SCROLL_ROUNDS)
        }
        growTopicDepth[currentGrowTopic] = searchScrollRounds
        setStatus(
            "목표 $added/$target · '$currentGrowTopic' 더 깊이 찾는 중 " +
                "(${searchScrollRounds}단계)"
        )
        loadPage(Accounts.blogSearchUrl(currentGrowTopic), Phase.SEARCH_COLLECT)
        armCollectPageWatchdog(Phase.SEARCH_COLLECT)
    }

    private fun scheduleGrowRefill() {
        if (!running || mode != Phase.SEARCH_COLLECT || added >= target) return
        growNoNewSearches++
        growTopicDryStreak++   // 이번 스캔에서 새 후보 없음 → 주제 소진 판정에 반영
        val waitMs = when {
            growNoNewSearches <= 2 -> 1_500L
            growNoNewSearches <= 6 -> max(delayMs, 5_000L)
            else -> max(delayMs, 30_000L)
        }
        val atMaxDepth = searchScrollRounds >= MAX_SEARCH_SCROLL_ROUNDS
        val willRotate = growTopicDryStreak >= (if (atMaxDepth) 1 else TOPIC_DRY_LIMIT_SHALLOW)
        val next = if (willRotate) "다음 주제로" else "더 깊이"
        setStatus(
            "'$currentGrowTopic' 새 블로거 없음 · ${waitMs / 1000}초 후 $next 재검색 ($added/$target)"
        )
        val expectedStep = stepToken
        postForRun(waitMs) {
            if (
                stepToken == expectedStep &&
                pendingBlogger.isBlank() &&
                queue.isEmpty()
            ) requestMoreGrowCandidates()
        }
    }

    private fun nextGrowTopic(): String {
        if (growTopicDeck.isEmpty()) {
            val topics = if (manualTopics.isEmpty()) {
                DiscoveryTopics.shuffledDeck(lastGrowTopic)
            } else {
                manualTopics.flatMap { topic ->
                    // 원문은 그대로 두고, 5자 이내가 되는 변주만 덧붙인다.
                    listOf(topic) + listOf("후기", "일상", "기록", "일기")
                        .map { topic + it }
                        .filter { it.length <= 5 }
                }.distinct().shuffled().let { shuffled ->
                    if (shuffled.size > 1 && shuffled.first() == lastGrowTopic) {
                        shuffled.drop(1) + shuffled.first()
                    } else shuffled
                }
            }
            topics.forEach(growTopicDeck::addLast)
        }
        val topic = growTopicDeck.removeFirst()
        lastGrowTopic = topic
        return topic
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
        @JavascriptInterface fun onBuddies(json: String) = runOnUiThread { handleBuddies(json) }
        @JavascriptInterface fun onAdd(json: String) = runOnUiThread { handleAdd(json) }
        @JavascriptInterface fun onLogin(b: Boolean) = runOnUiThread { if (b) onNeedLoginUi() }
        @JavascriptInterface fun onNeedLogin() = runOnUiThread { onNeedLoginUi() }
    }

    // ---------------------------------------------------------------
    //  유틸
    // ---------------------------------------------------------------
    private fun invalidateScheduledWork() {
        runToken++
        stepToken++
    }

    private fun postForRun(delay: Long, action: () -> Unit) {
        val expectedRun = runToken
        web.postDelayed({
            if (running && runToken == expectedRun) action()
        }, delay)
    }

    private fun postForPage(delay: Long, pageGeneration: Int, action: () -> Unit) {
        val expectedRun = runToken
        web.postDelayed({
            if (
                running &&
                runToken == expectedRun &&
                webPageGeneration == pageGeneration
            ) action()
        }, delay)
    }

    /** 일정 시간 응답이 없으면 다음으로 넘어가는 감시(토큰이 그대로일 때만 발동) */
    private fun armWatchdog(timeoutMs: Long = 22_000L, onTimeout: () -> Unit) {
        val expectedRun = runToken
        val tk = stepToken
        web.postDelayed({
            if (running && runToken == expectedRun && stepToken == tk) {
                stepToken++
                onTimeout()
            }
        }, timeoutMs)
    }

    private fun canonicalPostKey(url: String): String {
        val key = runCatching {
            val uri = Uri.parse(url)
            val segments = uri.pathSegments.orEmpty()
            val numericIndex = segments.indexOfLast { part ->
                part.length >= 5 && part.all { it.isDigit() }
            }
            val logNo = uri.getQueryParameter("logNo")
                ?.takeIf { it.isNotBlank() }
                ?: numericIndex.takeIf { it >= 0 }?.let(segments::get)
            val blogId = uri.getQueryParameter("blogId")
                ?.takeIf { it.isNotBlank() }
                ?: numericIndex.takeIf { it > 0 }?.let { segments[it - 1] }
            if (logNo != null) {
                "${blogId.orEmpty().lowercase(Locale.ROOT)}:$logNo"
            } else {
                null
            }
        }.getOrNull()
        return key ?: url.substringBefore('#').trim().lowercase(Locale.ROOT)
    }

    private fun parseArray(json: String): JSONArray =
        runCatching { JSONArray(json) }.getOrDefault(JSONArray())

    private fun parseBatch(json: String): JsBatch {
        val obj = runCatching { JSONObject(json) }.getOrNull()
        if (obj != null && obj.has("items")) {
            return JsBatch(
                token = obj.optInt("token").takeIf { obj.has("token") },
                items = obj.optJSONArray("items") ?: JSONArray()
            )
        }
        return JsBatch(token = null, items = parseArray(json))
    }

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
        persistUiSettings()
        if (!accountApplyInProgress && Accounts.isLoggedIn()) {
            appliedAccount?.let { Accounts.saveCurrentFor(this, it) }
        }
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        invalidateScheduledWork()
        running = false
        web.removeJavascriptInterface("NF")
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
