package com.nfriendcl.app

import kotlin.random.Random

/**
 * 주제 키워드를 입력하지 않았을 때 사용할 "개인 블로거" 지향 주제 모음.
 *
 * 광고성/업체 유입을 줄이려고 상업 신호가 강한 단어(예: 분양, 견적, 창업)는 배제하고
 * 일상·기록·취미 중심의 개인 키워드만 담는다. 매 덱마다 base×modifier 조합을 새로
 * 섞어 같은 검색어가 반복되지 않게 하고, 목표 인원을 채울 때까지 계속 다른 주제로
 * 넘어갈 수 있도록 충분히 넓은 후보를 만든다.
 */
object DiscoveryTopics {

    /** 개인 블로거가 즐겨 쓰는 일상/취미 중심 기본 주제. */
    private val baseTopics = listOf(
        "일상", "소소한일상", "오늘일기", "하루기록", "감성일기", "일상기록",
        "집순이", "직장인일상", "주부일상", "퇴근후일상", "육아일기", "아기일상",
        "홈카페", "집밥", "오늘의요리", "베이킹기록", "다이어트일기", "홈트",
        "러닝기록", "등산", "산책", "캠핑일기", "국내여행", "여행기록", "혼자여행",
        "반려견", "반려묘", "강아지일상", "고양이집사", "식물집사", "홈가드닝",
        "독서기록", "책스타그램", "영화감상", "음악추천", "드라마후기",
        "취미생활", "손그림", "다꾸", "가계부", "미니멀라이프", "습관만들기",
        "공부기록", "자기계발", "글쓰기", "에세이", "사진일기", "감성사진",
        "셀프인테리어", "정리수납", "육아소통", "동네산책", "주말일상"
    )

    /**
     * 기본 주제 뒤에 붙여 자연스럽게 변주하는 개인형 수식어.
     * 빈 문자열이 있어 base 단독 검색도 섞인다.
     */
    private val modifiers = listOf(
        "", "", "일상", "기록", "이야기", "소통", "일기", "후기", "그램", "블로그"
    )

    /**
     * base×modifier 를 조합해 무복원 랜덤 덱을 만든다.
     * [lastTopic]이 새 덱 첫 칸과 겹치면 다른 주제와 교환해 연속 중복을 막는다.
     */
    fun shuffledDeck(lastTopic: String?): List<String> {
        val combined = LinkedHashSet<String>()
        for (base in baseTopics) {
            for (mod in modifiers) {
                val phrase = if (mod.isEmpty()) base else "$base $mod"
                combined.add(phrase)
            }
        }
        val deck = combined.toMutableList().apply { shuffle(Random.Default) }
        val previous = lastTopic?.trim().orEmpty()
        if (previous.isNotEmpty() && deck.isNotEmpty() &&
            deck.first().equals(previous, ignoreCase = true)
        ) {
            val replacement = deck.indexOfFirst { !it.equals(previous, ignoreCase = true) }
            if (replacement > 0) {
                val first = deck[0]
                deck[0] = deck[replacement]
                deck[replacement] = first
            }
        }
        return deck
    }
}
