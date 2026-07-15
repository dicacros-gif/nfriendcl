package com.nfriendcl.app

import kotlin.random.Random

/** 빈 키워드 검색에서 사용할 안전한 일반 주제 모음. */
object DiscoveryTopics {

    private val safeTopics = listOf(
        "일상",
        "맛집",
        "여행",
        "요리",
        "카페",
        "반려동물",
        "운동",
        "독서",
        "영화",
        "음악",
        "사진",
        "인테리어",
        "IT",
        "모바일",
        "자동차",
        "캠핑",
        "식물",
        "패션",
        "뷰티",
        "건강",
        "육아",
        "공부",
        "자기계발",
        "취미",
        "문화생활",
        "전시",
        "홈카페",
        "베이킹",
        "산책",
        "등산",
        "자전거",
        "공예",
        "그림",
        "정리수납",
        "국내여행",
        "생활정보"
    )

    /**
     * 모든 주제를 한 번씩 담은 무복원 랜덤 덱을 만든다.
     * [lastTopic]이 이전 덱의 첫 주제였다면 새 덱 첫 칸과 겹치지 않게 교환한다.
     */
    fun shuffledDeck(lastTopic: String?): List<String> {
        val deck = safeTopics.shuffled(Random.Default).toMutableList()
        val previous = lastTopic?.trim().orEmpty()
        if (previous.isNotEmpty() && deck.first().equals(previous, ignoreCase = true)) {
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
