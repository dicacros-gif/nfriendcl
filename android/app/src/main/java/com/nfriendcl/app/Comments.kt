package com.nfriendcl.app

import kotlin.random.Random

/**
 * 댓글/서로이웃 신청 문구를 "조금씩 변형"해 생성한다.
 *
 * 사용자가 준 예시
 *   "좋은 글 잘 읽었어요. 고생 많으셨어요. 제 블로그에도 놀러오셔서 인사이트 받아가세요"
 * 를 [인사 · 격려 · 초대(CTA)] 3-파트 구조로 일반화한다.
 * base(사용자 편집 가능)의 각 문장을 앵커로 삼고, 매 호출(seq)마다 일부를 동의 표현으로
 * 바꾸거나 이모지를 회전시켜 같은 문구가 반복 등록되지 않게 한다(스팸/도배 방지).
 */
object Comments {

    const val DEFAULT_BASE =
        "좋은 글 잘 읽었어요. 고생 많으셨어요. 제 블로그에도 놀러오셔서 인사이트 받아가세요"

    const val DEFAULT_NEIGHBOR_MSG =
        "안녕하세요! 글 잘 보고 이웃 신청합니다. 자주 소통해요"

    private val openers = listOf(
        "좋은 글 잘 읽었어요",
        "포스팅 잘 보고 갑니다",
        "유익한 글 감사합니다",
        "정성스러운 글 잘 봤어요",
        "오늘도 좋은 글 잘 읽고 가요",
        "글 정말 잘 읽었습니다",
        "공감하며 잘 읽었어요",
        "좋은 정보 잘 보고 가요"
    )
    private val middles = listOf(
        "고생 많으셨어요",
        "항상 응원합니다",
        "오늘도 좋은 하루 되세요",
        "늘 좋은 글 감사해요",
        "행복한 하루 보내세요",
        "다음 글도 기대할게요",
        "건강 잘 챙기세요"
    )
    private val ctas = listOf(
        "제 블로그에도 놀러오셔서 인사이트 받아가세요",
        "시간 되시면 제 블로그도 들러주세요",
        "자주 소통하며 지내요",
        "서로이웃하며 자주 뵈어요",
        "제 블로그에도 한번 놀러오세요",
        "앞으로도 자주 소통해요"
    )
    private val emojis = listOf("", " 😊", " 👍", " 🙏", " ✨", " 🌿", " 😄")

    private val neighborMsgs = listOf(
        "안녕하세요! 글 잘 보고 이웃 신청합니다. 자주 소통해요",
        "포스팅 잘 보고 갑니다. 서로이웃 신청드려요, 자주 뵈어요",
        "좋은 글 보고 이웃 추가합니다. 앞으로 자주 소통해요",
        "반갑습니다! 관심 주제가 비슷해 신청합니다. 서로이웃해요",
        "글 잘 읽었습니다. 서로 이웃하며 좋은 정보 나눠요"
    )

    /** 댓글 변형 생성 — base 문장을 앵커로 두고 seq 마다 다른 조합을 만든다. */
    fun comment(base: String, seq: Int): String {
        val src = base.ifBlank { DEFAULT_BASE }
        val parts = src.split('.', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val r = Random(seq * 1000003L + 7)
        val o = pick(r, openers, parts.getOrNull(0))
        val m = pick(r, middles, parts.getOrNull(1))
        val c = pick(r, ctas, parts.getOrNull(2))
        val body = listOf(o, m, c).filter { it.isNotBlank() }.joinToString(". ")
        return body + "." + emojis[r.nextInt(emojis.size)]
    }

    /** 미리보기용 샘플 n개 */
    fun samples(base: String, n: Int): List<String> =
        (0 until n).map { comment(base, it * 31 + 5) }

    /** 서로이웃/이웃 신청 메시지 — 사용자가 직접 쓴 메시지면 이모지만 회전 */
    fun neighborMessage(base: String, seq: Int): String {
        val r = Random(seq * 7919L + 3)
        val text = if (base.isNotBlank() && base.trim() != DEFAULT_NEIGHBOR_MSG) {
            base.trim()
        } else {
            neighborMsgs[r.nextInt(neighborMsgs.size)]
        }
        return text + emojis[r.nextInt(emojis.size)]
    }

    /**
     * 앵커가 있으면 40% 확률로 그대로 쓰고, 아니면 (앵커 포함) 풀에서 변형 선택.
     * 앵커가 없으면(사용자 base 문장이 부족) 풀에서 골라 친근한 문장을 보충한다.
     */
    private fun pick(r: Random, pool: List<String>, anchor: String?): String {
        val a = anchor?.takeIf { it.isNotBlank() }
        if (a != null && r.nextInt(100) < 40) return a
        val merged = if (a != null) listOf(a) + pool else pool
        return merged[r.nextInt(merged.size)]
    }
}
