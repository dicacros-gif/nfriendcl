package com.nfriendcl.app

import kotlin.random.Random

/**
 * 댓글과 서로이웃 신청 문구를 자연스럽게 변형한다.
 *
 * 기본 문구는 여러 문장 구조를 조합하고, 사용자가 직접 입력한 문구는 원문 전체를
 * 그대로 둔 채 앞뒤에 짧고 중립적인 문구만 더한다. 매 호출마다 시스템 난수를 섞고
 * 최근 결과를 기억해 같은 문구가 연달아 만들어지는 것도 피한다.
 */
object Comments {

    const val DEFAULT_BASE =
        "좋은 글 잘 읽었어요. 고생 많으셨어요. 제 블로그에도 놀러오셔서 인사이트 받아가세요"

    const val DEFAULT_NEIGHBOR_MSG =
        "안녕하세요! 글 잘 보고 이웃 신청합니다. 자주 소통해요"

    private const val RECENT_CAPACITY = 320
    private const val UNIQUE_ATTEMPTS = 160
    private const val EMOJI_PERCENT = 28
    private const val COMMENT_SALT = -7046029254386353131L
    private const val NEIGHBOR_SALT = -4417276706812531889L

    private val generationLock = Any()
    private val recentComments = RecentLru(RECENT_CAPACITY)
    private val recentNeighborMessages = RecentLru(RECENT_CAPACITY)

    private val emojis = listOf("😊", "👍", "🙏", "✨", "🌿", "😄", "🍀", "🌸")

    private val commentOpeners = listOf(
        "좋은 글 잘 읽었어요",
        "포스팅 잘 보고 갑니다",
        "유익한 글 감사합니다",
        "정성스러운 글 잘 봤어요",
        "오늘도 좋은 글 잘 읽고 가요",
        "글 잘 읽었습니다",
        "공감하며 잘 읽었어요",
        "좋은 정보 잘 보고 가요",
        "포스팅 천천히 살펴보고 갑니다",
        "알찬 내용 잘 읽었어요",
        "소중한 글 잘 보고 갑니다",
        "오늘 포스팅도 잘 읽었어요"
    )

    private val commentReactions = listOf(
        "정성이 느껴지는 글이네요",
        "덕분에 유익한 시간 보냈어요",
        "차분하게 읽으며 많이 배웠습니다",
        "공감되는 부분이 많았어요",
        "내용을 이해하기 쉽게 정리해 주셨네요",
        "좋은 내용을 나눠 주셔서 감사해요",
        "한 번 더 생각해 보게 되는 내용이었어요",
        "편안하게 읽을 수 있는 글이었어요",
        "필요한 내용을 잘 살펴보고 갑니다",
        "정성껏 전해 주신 내용 잘 봤어요"
    )

    private val commentWishes = listOf(
        "오늘도 좋은 하루 보내세요",
        "편안한 하루 되세요",
        "늘 건강하고 행복하세요",
        "기분 좋은 하루 보내시길 바라요",
        "남은 하루도 즐겁게 보내세요",
        "이번 주도 좋은 일 가득하세요",
        "따뜻하고 여유로운 하루 보내세요",
        "오늘 하루도 힘내세요"
    )

    private val commentClosers = listOf(
        "다음 글도 기대할게요",
        "앞으로도 종종 들르겠습니다",
        "좋은 글 또 보러 올게요",
        "다음에 다시 인사드릴게요",
        "꾸준히 좋은 글 만나고 싶어요",
        "또 들러서 천천히 읽어볼게요",
        "앞으로도 좋은 소식 기다릴게요",
        "다음 포스팅도 잘 보러 올게요"
    )

    private val customCommentPrefixes = listOf(
        "안녕하세요",
        "반갑습니다",
        "오늘도 들러 인사드려요",
        "잠시 들러 인사 남겨요",
        "편안한 마음으로 인사드려요",
        "좋은 하루 보내고 계신가요",
        "오늘도 반가운 마음으로 들렀어요",
        "가볍게 인사 남기고 갑니다",
        "반가운 마음에 인사드려요",
        "잠깐 들러 안부 전해요",
        "오늘도 편안히 들렀습니다",
        "기분 좋게 인사드리고 갑니다"
    )

    private val customCommentSuffixes = listOf(
        "좋은 하루 보내세요",
        "편안한 하루 되세요",
        "늘 건강하세요",
        "다음에 또 들를게요",
        "오늘도 행복하세요",
        "기분 좋은 하루 보내세요",
        "앞으로도 종종 들를게요",
        "남은 하루도 편안하세요",
        "다음에 다시 인사드릴게요",
        "오늘 하루도 힘내세요",
        "즐거운 시간 보내세요",
        "따뜻한 하루 보내세요",
        "좋은 일 가득하시길 바라요",
        "또 편하게 들르겠습니다"
    )

    private val neighborOneLiners = listOf(
        "안녕하세요, 블로그 글 잘 보고 서로이웃 신청드립니다",
        "반가운 마음으로 들러 서로이웃 신청 남겨요",
        "좋은 글을 함께 나누고 싶어 서로이웃 신청드립니다",
        "블로그를 둘러보고 자주 소통하고 싶어 이웃 신청드려요",
        "앞으로 편하게 소통하고 싶어 서로이웃 신청합니다",
        "좋은 인연으로 지내고 싶어 이웃 신청 남깁니다",
        "반갑게 인사드리며 서로이웃 신청드려요",
        "서로 좋은 글 나누며 지내고 싶어 이웃 신청합니다"
    )

    private val neighborGreetings = listOf(
        "안녕하세요",
        "반갑습니다",
        "반가운 마음에 인사드려요",
        "블로그에 들러 인사드립니다",
        "편안한 마음으로 방문했어요",
        "좋은 하루에 인사드려요",
        "블로그를 둘러보다 인사 남깁니다",
        "기분 좋게 들러 인사드려요"
    )

    private val neighborRequests = listOf(
        "좋은 글을 함께 나누고 싶어 서로이웃 신청드립니다",
        "앞으로 종종 소통하고 싶어 이웃 신청드려요",
        "편하게 왕래하며 지내고 싶어 서로이웃 신청합니다",
        "좋은 인연으로 이어가고 싶어 이웃 신청 남겨요",
        "서로의 글을 천천히 나누고 싶어 서로이웃 신청드립니다",
        "반갑게 소통하고 싶어 이웃 신청드려요",
        "앞으로 자주 인사 나누고 싶어 서로이웃 신청합니다",
        "좋은 글로 종종 만나고 싶어 이웃 신청 남깁니다"
    )

    private val neighborClosers = listOf(
        "앞으로 편하게 소통해요",
        "좋은 인연으로 자주 뵈어요",
        "서로 응원하며 지내면 좋겠습니다",
        "종종 들러 안부 나눌게요",
        "앞으로 잘 부탁드립니다",
        "편안하게 왕래하며 지내요",
        "좋은 글로 자주 만나고 싶어요",
        "반갑게 소통하며 지내요",
        "다음 글도 천천히 보러 올게요",
        "좋은 하루 보내세요"
    )

    private val customNeighborPrefixes = listOf(
        "안녕하세요",
        "반갑습니다",
        "블로그에 들러 인사드려요",
        "반가운 마음에 인사드립니다",
        "편안한 마음으로 방문했어요",
        "오늘도 좋은 마음으로 들렀어요",
        "가볍게 인사부터 드립니다",
        "블로그를 둘러보다 인사 남겨요",
        "좋은 하루에 반갑게 인사드려요",
        "기분 좋게 들러 인사드립니다"
    )

    private val customNeighborSuffixes = listOf(
        "앞으로 편하게 소통해요",
        "좋은 인연으로 자주 뵈어요",
        "서로 응원하며 지내면 좋겠습니다",
        "종종 들러 안부 나눌게요",
        "앞으로 잘 부탁드립니다",
        "편안하게 왕래하며 지내요",
        "좋은 글로 자주 만나고 싶어요",
        "반갑게 소통하며 지내요",
        "다음에 다시 인사드릴게요",
        "오늘도 좋은 하루 보내세요",
        "앞으로 종종 들르겠습니다",
        "서로 좋은 이야기 나누면 좋겠어요"
    )

    /** 댓글 생성. 공개 시그니처는 기존 호출부와 호환된다. */
    fun comment(base: String, seq: Int): String = synchronized(generationLock) {
        val random = callRandom(seq, COMMENT_SALT)
        val customCore = customCore(base, DEFAULT_BASE)
        uniqueResult(random, recentComments) {
            if (customCore == null) defaultComment(random)
            else wrappedCustom(
                core = customCore,
                prefixes = customCommentPrefixes,
                suffixes = customCommentSuffixes,
                random = random
            )
        }
    }

    /** 미리보기용 샘플 n개. */
    fun samples(base: String, n: Int): List<String> =
        (0 until n).map { comment(base, it * 31 + 5) }

    /** 서로이웃/이웃 신청 메시지 생성. */
    fun neighborMessage(base: String, seq: Int): String = synchronized(generationLock) {
        val random = callRandom(seq, NEIGHBOR_SALT)
        val customCore = customCore(base, DEFAULT_NEIGHBOR_MSG)
        uniqueResult(random, recentNeighborMessages) {
            if (customCore == null) defaultNeighborMessage(random)
            else wrappedCustom(
                core = customCore,
                prefixes = customNeighborPrefixes,
                suffixes = customNeighborSuffixes,
                random = random
            )
        }
    }

    private fun defaultComment(random: Random): String {
        val sentences = when (random.nextInt(8)) {
            0 -> listOf(pick(random, commentOpeners))
            1 -> listOf(pick(random, commentReactions))
            2 -> listOf(pick(random, commentOpeners), pick(random, commentWishes))
            3 -> listOf(pick(random, commentOpeners), pick(random, commentClosers))
            4 -> listOf(pick(random, commentReactions), pick(random, commentWishes))
            5 -> listOf(
                pick(random, commentOpeners),
                pick(random, commentReactions),
                pick(random, commentWishes)
            )
            6 -> listOf(
                pick(random, commentOpeners),
                pick(random, commentReactions),
                pick(random, commentClosers)
            )
            else -> listOf(
                pick(random, commentOpeners),
                pick(random, commentWishes),
                pick(random, commentClosers)
            )
        }
        return format(sentences) + optionalEmoji(random)
    }

    private fun defaultNeighborMessage(random: Random): String {
        val sentences = when (random.nextInt(6)) {
            0 -> listOf(pick(random, neighborOneLiners))
            1 -> listOf(pick(random, neighborRequests), pick(random, neighborClosers))
            2 -> listOf(pick(random, neighborGreetings), pick(random, neighborRequests))
            else -> listOf(
                pick(random, neighborGreetings),
                pick(random, neighborRequests),
                pick(random, neighborClosers)
            )
        }
        return format(sentences) + optionalEmoji(random)
    }

    /** 사용자 원문은 건드리지 않고 짧은 중립 래퍼만 붙인다. */
    private fun wrappedCustom(
        core: String,
        prefixes: List<String>,
        suffixes: List<String>,
        random: Random
    ): String {
        var prefix = if (random.nextInt(100) < 55) pick(random, prefixes) else null
        var suffix = if (random.nextInt(100) < 70) pick(random, suffixes) else null

        // 매 결과에 최소 한 가지 자연스러운 변화가 생기도록 짧은 래퍼 하나는 유지한다.
        if (prefix == null && suffix == null) {
            if (random.nextBoolean()) prefix = pick(random, prefixes)
            else suffix = pick(random, suffixes)
        }

        val result = buildString {
            prefix?.let {
                append(withSentenceEnding(it))
                append(' ')
            }
            // trim/치환 없이 원문을 그대로 넣어 줄바꿈을 포함한 사용자 입력을 보존한다.
            append(core)
            suffix?.let {
                val last = core.lastOrNull()
                if (last != null && !last.isWhitespace() && last !in ".!?。！？…~") append('.')
                append(' ')
                append(withSentenceEnding(it))
            }
        }
        return result + optionalEmoji(random)
    }

    private fun customCore(base: String, defaultText: String): String? {
        val trimmed = base.trim()
        return base.takeIf { trimmed.isNotEmpty() && trimmed != defaultText }
    }

    private fun callRandom(seq: Int, salt: Long): Random {
        val entropy = Random.Default.nextLong()
        return Random(entropy xor (seq.toLong() * salt))
    }

    private fun uniqueResult(
        random: Random,
        recent: RecentLru,
        create: () -> String
    ): String {
        var fallback = ""
        repeat(UNIQUE_ATTEMPTS) {
            val candidate = create()
            fallback = candidate
            if (!recent.contains(candidate)) {
                recent.remember(candidate)
                return candidate
            }
            // 같은 조합이 나오면 난수 상태를 한 번 더 움직여 다음 조합과의 상관을 낮춘다.
            random.nextLong()
        }
        recent.remember(fallback)
        return fallback
    }

    private fun format(parts: List<String>): String =
        parts.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ") { withSentenceEnding(it) }

    private fun withSentenceEnding(text: String): String {
        val last = text.lastOrNull()
        return if (last != null && last in ".!?。！？…~") text else "$text."
    }

    private fun optionalEmoji(random: Random): String =
        if (random.nextInt(100) < EMOJI_PERCENT) " ${pick(random, emojis)}" else ""

    private fun <T> pick(random: Random, values: List<T>): T =
        values[random.nextInt(values.size)]

    /** 최근에 반환한 완성 문구를 접근 순서대로 보관하는 작은 LRU. */
    private class RecentLru(private val capacity: Int) {
        private val items = object : LinkedHashMap<String, Unit>(capacity + 1, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Unit>?
            ): Boolean = size > capacity
        }

        fun contains(value: String): Boolean = items[value] != null

        fun remember(value: String) {
            items[value] = Unit
        }
    }
}
