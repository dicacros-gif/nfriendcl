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
        "좋은 정보 잘 보고 가요",
        "포스팅 정독하고 갑니다",
        "오늘도 유익한 포스팅 감사해요",
        "정말 공감되는 내용이네요",
        "귀한 정보 정독하며 많이 배워요",
        "글을 참 진정성 있게 잘 쓰시네요",
        "올려주신 포스팅 덕분에 힐링하고 가요",
        "정말 도움되는 유용한 내용이네요",
        "멋진 사진과 글 잘 감상하고 갑니다"
    )
    private val middles = listOf(
        "고생 많으셨어요",
        "항상 응원합니다",
        "오늘도 좋은 하루 되세요",
        "늘 좋은 글 감사해요",
        "행복한 하루 보내세요",
        "다음 글도 기대할게요",
        "건강 잘 챙기세요",
        "보람찬 하루 보내시길 바랄게요",
        "항상 유익한 글 올려주셔서 감사해요",
        "포스팅 하나하나에 정성이 느껴지네요",
        "매번 좋은 정보 나눠주셔서 큰 힘이 돼요",
        "글에서 따뜻한 마음이 느껴져서 좋아요",
        "오늘도 덕분에 즐거운 시간 보냈습니다",
        "바쁜 일상 속에서도 늘 빛나는 포스팅이네요",
        "꾸준히 소통하며 지내고 싶은 분이시네요"
    )
    private val ctas = listOf(
        "제 블로그에도 놀러오셔서 인사이트 받아가세요",
        "시간 되시면 제 블로그도 들러주세요",
        "자주 소통하며 지내요",
        "서로이웃하며 자주 뵈어요",
        "제 블로그에도 한번 놀러오세요",
        "앞으로도 자주 소통해요",
        "앞으로 더 자주 뵙고 싶습니다",
        "우리 진심으로 소통하는 이웃이 되어요",
        "언제든 제 공간에도 편하게 들러주세요",
        "좋은 인연 이어갔으면 좋겠습니다",
        "오늘도 소중한 인연에 감사하며 갑니다",
        "자주 들러서 좋은 글 많이 읽을게요",
        "서로 응원하며 힘이 되는 이웃이 되어요"
    )
    private val emojis = listOf("", " 😊", " 👍", " 🙏", " ✨", " 🌿", " 😄", " ❤️", " ✨", " 🍀", " 🌸")

    private val neighborMsgs = listOf(
        "안녕하세요! 블로그의 따뜻한 감성과 정성 가득한 글들에 매료되어 서로이웃 신청드립니다. 앞으로 자주 소통하며 좋은 인연 이어가고 싶어요.",
        "반갑습니다! 관심 주제가 너무 비슷해서 포스팅 하나하나 정독하게 되네요. 깊이 있는 인사이트 많이 배우고 싶어 서로이웃 신청합니다.",
        "포스팅 하나하나에 담긴 진심이 느껴져서 참 좋았습니다. 저도 비슷한 주제로 블로그 운영 중인데, 서로 응원하며 힘이 되는 든든한 이웃이 되고 싶어요.",
        "우연히 들렀는데 글 솜씨가 너무 좋으셔서 깜짝 놀랐습니다! 유익한 정보와 따뜻한 일상을 저도 함께 나누고 싶어 정중히 서로이웃 신청드려요.",
        "올려주신 포스팅들이 저에게 큰 영감을 주네요. 앞으로도 올라올 글들이 너무 기대됩니다. 서로 응원하며 자주 소통하는 이웃으로 지내고 싶습니다.",
        "사진과 글에서 느껴지는 밝은 에너지가 너무 좋아서 이웃 신청하게 되었습니다. 우리 앞으로 진심으로 소통하며 좋은 정보 많이 나눠요!",
        "안녕하세요! 글이 너무 제 취향이라 시간 가는 줄 모르고 구경했네요. 앞으로 더 가까이서 소통하며 지내고 싶은 마음에 서로이웃 신청 남깁니다.",
        "진정성 있는 포스팅 잘 읽었습니다. 저도 정성껏 소통하는 걸 좋아해서, 좋은 이웃이 될 수 있을 것 같아요. 우리 서로이웃해요!",
        "오늘도 멋진 글 잘 보고 갑니다. 비슷한 관심사를 가진 분을 만나서 너무 반갑네요. 서로 힘이 되어주는 좋은 이웃이 되길 희망합니다.",
        "정성스러운 포스팅 덕분에 좋은 인사이트 얻어갑니다. 앞으로도 자주 들러서 배우고 소통하고 싶어요. 기쁜 마음으로 서로이웃 신청드립니다!"
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
