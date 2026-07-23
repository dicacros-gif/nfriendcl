package com.nfriendcl.app

import kotlin.random.Random

/**
 * 주제 키워드를 입력하지 않았을 때 사용할 "개인 블로거" 지향 주제 모음.
 *
 * 광고성/업체 유입을 줄이려고 상업 신호가 강한 단어는 배제하고 일상·기록·취미
 * 중심의 개인 키워드만 담는다. 모든 검색어는 공백 없이 최대 5자로 제한하고,
 * base×suffix 조합을 매 덱마다 새로 섞어 같은 검색어가 반복되지 않게 한다.
 * (한 번 사용한 주제 제외는 호출부에서 영구 저장으로 처리한다.)
 */
object DiscoveryTopics {

    private const val MAX_TOPIC_LENGTH = 5

    /** 개인 블로거가 즐겨 쓰는 짧은 기본 주제(1~3자). */
    private val baseTopics = listOf(
        // 일상·기록
        "일상", "일기", "하루", "주말", "퇴근", "출근", "소통", "감성", "힐링", "소소",
        // 음식·카페
        "맛집", "집밥", "요리", "커피", "카페", "베이킹", "디저트", "브런치", "간식", "야식",
        "빵", "케이크", "혼밥", "혼술", "다과",
        // 운동·건강
        "운동", "홈트", "요가", "필라", "러닝", "등산", "산책", "헬스", "골프", "수영",
        "자전거", "다이어", "명상",
        // 문화·취미
        "독서", "책", "영화", "음악", "드라마", "공연", "전시", "미술", "사진", "그림",
        "글쓰기", "취미", "다꾸", "자수", "뜨개", "공예", "도예", "캘리", "레고",
        // 반려·식물
        "강아지", "고양이", "반려", "식물", "화분", "다육", "정원", "텃밭", "꽃",
        // 여행·계절
        "여행", "국내", "제주", "바다", "산", "캠핑", "드라이브", "봄", "여름", "가을",
        "겨울", "날씨",
        // 육아·공부
        "육아", "아기", "태교", "유아", "공부", "학습", "시험",
        // 패션·뷰티(개인 취향)
        "패션", "코디", "뷰티", "네일", "향수", "헤어",
        // 살림·자기계발
        "살림", "정리", "청소", "수납", "인테", "집꾸", "가계부", "절약", "미니멀",
        "습관", "갓생", "자기", "재테"
    )

    /** 기본 주제 뒤에 붙여 변주하는 개인형 접미어(빈 값이면 base 단독). */
    private val suffixes = listOf("", "일상", "기록", "일기", "후기", "소통", "그램")

    /**
     * base×suffix 를 조합해 5자 이내 검색어만 담은 무복원 랜덤 덱을 만든다.
     * [lastTopic]이 새 덱 첫 칸과 겹치면 다른 주제와 교환해 연속 중복을 막는다.
     */
    fun shuffledDeck(lastTopic: String?): List<String> {
        val combined = LinkedHashSet<String>()
        for (base in baseTopics) {
            for (suffix in suffixes) {
                if (suffix.isNotEmpty() && (base == suffix || base.endsWith(suffix))) continue
                val phrase = base + suffix
                if (phrase.length in 2..MAX_TOPIC_LENGTH) combined.add(phrase)
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
