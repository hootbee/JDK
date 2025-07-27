// backend/src/main/java/com/example/oda/service/PromptServiceImpl.java
package com.example.oda.service;

import com.example.oda.entity.PublicData;
import com.example.oda.repository.PublicDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Service
public class PromptServiceImpl implements PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptServiceImpl.class);

    private final PublicDataRepository publicDataRepository;
    private final AiModelService aiModelService;

    // 지역명 목록 (지역 키워드 식별용)
    private static final String[] REGION_KEYWORDS = {
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
    };

    public PromptServiceImpl(PublicDataRepository publicDataRepository, AiModelService aiModelService) {
        this.publicDataRepository = publicDataRepository;
        this.aiModelService = aiModelService;
    }

    @Override
    public Mono<List<String>> processPrompt(String prompt) {
        log.info("=== 프롬프트 처리 시작 ===");
        log.info("입력 프롬프트: '{}'", prompt);

        // 중복 요청 체크 (간단한 캐시 메커니즘)
        String requestHash = Integer.toString(prompt.hashCode());

        // ⭐ 개선된 상세 조회 판단
        boolean isDetail = isDetailRequest(prompt);
        log.info("상세 조회 요청 판단: {}", isDetail);

        if (isDetail) {
            String fileName = extractFileNameFromPrompt(prompt);
            log.info("상세 조회 대상 파일명: '{}'", fileName);

            // 빈 파일명 체크
            if (fileName == null || fileName.trim().isEmpty()) {
                return Mono.just(List.of("❌ 파일명을 찾을 수 없습니다. 정확한 파일명을 입력해주세요."));
            }

            return getDataDetails(fileName)
                    .map(details -> List.of(details))
                    .doOnNext(result -> log.info("상세 조회 결과 반환: {} 문자", result.get(0).length()));
        }

        log.info("일반 검색 모드로 진행");

        // 기존 검색 로직
        return aiModelService.getQueryPlan(prompt)
                .flatMap(queryPlan -> {
                    JsonNode data = queryPlan.get("data");
                    String majorCategory = data.get("majorCategory").asText();
                    List<String> keywords = new ArrayList<>();
                    data.get("keywords").forEach(node -> keywords.add(node.asText()));

                    // ⭐ 프롬프트에서 개수 추출 (개선)
                    int limit = extractCountFromPrompt(prompt);
                    if (data.has("limit")) {
                        limit = Math.min(limit, data.get("limit").asInt());
                    }

                    log.info("원본 프롬프트: {}", prompt);
                    log.info("추출된 키워드: {}", keywords);
                    log.info("AI 분류 결과: {}", majorCategory);
                    log.info("결과 개수 제한: {}", limit);

                    List<PublicData> allResults = new ArrayList<>();

                    for (String keyword : keywords) {
                        Set<PublicData> keywordResults = new HashSet<>();

                        // ⭐ 지역 키워드 우선 처리
                        if (isRegionKeyword(keyword)) {
                            log.info("지역 키워드 '{}' 감지 - 우선 검색 적용", keyword);

                            // 지역 관련 필드 우선 검색
                            keywordResults.addAll(publicDataRepository.findByProviderAgencyContainingIgnoreCase(keyword));
                            keywordResults.addAll(publicDataRepository.findByFileDataNameContainingIgnoreCase(keyword));

                            // 지역 데이터가 충분하면 다른 필드 검색 최소화
                            if (keywordResults.size() >= 10) {
                                log.info("지역 키워드 '{}' 충분한 결과 확보: {}개", keyword, keywordResults.size());
                            } else {
                                // 지역 데이터 부족 시 다른 필드도 검색
                                keywordResults.addAll(publicDataRepository.findByKeywordsContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByTitleContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByDescriptionContainingIgnoreCase(keyword));
                            }
                        } else {
                            // ⭐ 일반 키워드 검색 (기존 방식)
                            try {
                                keywordResults.addAll(publicDataRepository.findByKeywordsContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByTitleContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByProviderAgencyContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByFileDataNameContainingIgnoreCase(keyword));
                                keywordResults.addAll(publicDataRepository.findByDescriptionContainingIgnoreCase(keyword));
                            } catch (Exception e) {
                                log.error("키워드 '{}' 검색 중 오류 발생: {}", keyword, e.getMessage());
                                continue;
                            }
                        }

                        // 대분류 필터링 (null 체크 강화)
                        if (majorCategory != null && !"일반공공행정".equals(majorCategory)) {
                            keywordResults = keywordResults.stream()
                                    .filter(publicData -> {
                                        try {
                                            return publicData != null &&
                                                    publicData.getClassificationSystem() != null &&
                                                    publicData.getClassificationSystem().toUpperCase().contains(majorCategory.toUpperCase());
                                        } catch (Exception e) {
                                            log.warn("분류 필터링 중 오류: {}", e.getMessage());
                                            return false;
                                        }
                                    })
                                    .collect(Collectors.toSet());
                        }

                        allResults.addAll(keywordResults);
                        log.info("키워드 '{}' 검색 결과: {}개", keyword, keywordResults.size());
                    }

                    // ⭐ 안전한 중복 제거
                    List<PublicData> uniqueResults;
                    try {
                        uniqueResults = allResults.stream()
                                .filter(publicData -> publicData != null && publicData.getFileDataName() != null)
                                .collect(Collectors.toMap(
                                        PublicData::getFileDataName,
                                        Function.identity(),
                                        (existing, replacement) -> existing,
                                        LinkedHashMap::new))
                                .values()
                                .stream()
                                .collect(Collectors.toList());
                    } catch (Exception e) {
                        log.warn("중복 제거 중 오류 발생, 기본 distinct 사용: {}", e.getMessage());
                        uniqueResults = allResults.stream()
                                .filter(publicData -> publicData != null && publicData.getFileDataName() != null)
                                .distinct()
                                .collect(Collectors.toList());
                    }

                    log.info("중복 제거 전: {}개 → 중복 제거 후: {}개", allResults.size(), uniqueResults.size());

                    // 관련성 점수 기반 정렬
                    List<PublicData> sortedResults = uniqueResults.stream()
                            .sorted((a, b) -> {
                                try {
                                    return calculateRelevanceScore(b, keywords, prompt) -
                                            calculateRelevanceScore(a, keywords, prompt);
                                } catch (Exception e) {
                                    log.warn("점수 계산 중 오류: {}", e.getMessage());
                                    return 0;
                                }
                            })
                            .collect(Collectors.toList());

                    log.info("전체 검색 결과 수: {}", sortedResults.size());

                    // ⭐ 데이터 부족 지역 대응
                    if (sortedResults.isEmpty()) {
                        String regionKeyword = extractRegionFromKeywords(keywords);
                        if (regionKeyword != null) {
                            return Mono.just(List.of(
                                    "해당 지역(" + regionKeyword + ")의 데이터가 부족합니다.",
                                    "다른 지역의 유사한 데이터를 참고하거나",
                                    "상위 카테고리(" + majorCategory + ")로 검색해보세요."
                            ));
                        } else {
                            return Mono.just(List.of("해당 조건에 맞는 데이터를 찾을 수 없습니다."));
                        }
                    }

                    // 상위 결과 로깅 (디버깅용)
                    if (log.isInfoEnabled()) {
                        sortedResults.stream()
                                .limit(5)
                                .forEach(item -> {
                                    int score = calculateRelevanceScore(item, keywords, prompt);
                                    log.info("상위 결과: {} (점수: {})", item.getFileDataName(), score);
                                });
                    }

                    List<String> results = sortedResults.stream()
                            .map(PublicData::getFileDataName)
                            .filter(name -> name != null && !name.trim().isEmpty())
                            .limit(limit)
                            .collect(Collectors.toList());

                    // ⭐ 결과에 상세 조회 안내 추가 (조건부)
                    if (!results.isEmpty() && results.size() >= 3) {
                        results.add("💡 특정 데이터에 대한 자세한 정보가 필요하시면");
                        results.add("'[파일명] 상세정보' 또는 '[파일명] 자세히'라고 말씀하세요.");
                    }

                    return Mono.just(results);
                })
                .onErrorReturn(List.of("데이터를 조회하는 중 오류가 발생했습니다."));
    }

    /**
     * ⭐ 프롬프트에서 개수 추출
     */
    private int extractCountFromPrompt(String prompt) {
        Pattern countPattern = Pattern.compile("(\\d+)개");
        Matcher matcher = countPattern.matcher(prompt);

        if (matcher.find()) {
            int count = Integer.parseInt(matcher.group(1));
            log.info("프롬프트에서 추출된 개수: {}", count);
            return Math.min(count, 30); // 최대 30개 제한
        }

        return 12; // 기본값
    }

    /**
     * ⭐ 데이터 상세 정보 조회
     */
    @Override
    public Mono<String> getDataDetails(String fileDataName) {
        return Mono.fromCallable(() -> {
            log.info("상세 정보 조회 요청: '{}'", fileDataName);

            // 1단계: 정확한 파일명 매칭
            Optional<PublicData> exactMatch = publicDataRepository.findByFileDataName(fileDataName);

            if (exactMatch.isPresent()) {
                log.info("정확한 매칭 성공: '{}'", exactMatch.get().getFileDataName());
                return formatDataDetails(exactMatch.get());
            }

            // 2단계: 부분 매칭 (더 엄격하게)
            List<PublicData> partialMatches = publicDataRepository.findByFileDataNameContaining(fileDataName);

            if (!partialMatches.isEmpty()) {
                // 가장 유사한 파일명 찾기
                PublicData bestMatch = partialMatches.stream()
                        .min((a, b) -> calculateSimilarity(fileDataName, b.getFileDataName()) -
                                calculateSimilarity(fileDataName, a.getFileDataName()))
                        .orElse(partialMatches.get(0));

                log.info("부분 매칭 결과: 요청='{}', 찾은결과='{}'", fileDataName, bestMatch.getFileDataName());
                return formatDataDetails(bestMatch);
            }

            log.warn("매칭 결과 없음: '{}'", fileDataName);
            return "❌ 해당 파일명을 찾을 수 없습니다: " + fileDataName;
        });
    }

    /**
     * 문자열 유사도 계산 (편집 거리)
     */
    private int calculateSimilarity(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                            Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                            dp[i-1][j-1] + (s1.charAt(i-1) == s2.charAt(j-1) ? 0 : 1)
                    );
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }


    /**
     * ⭐ 상세 조회 요청인지 판단 (개선된 버전)
     */
    private boolean isDetailRequest(String prompt) {
        String lowerPrompt = prompt.toLowerCase().trim();

        // 명확한 상세 조회 패턴만 허용
        return (lowerPrompt.contains("상세정보") ||
                lowerPrompt.contains("자세히") ||
                lowerPrompt.contains("더 알고") ||
                (lowerPrompt.contains("상세") && !lowerPrompt.contains("데이터"))) && // "상세 데이터" 제외
                // ⭐ 일반 검색 키워드와 구분
                !lowerPrompt.matches(".*\\d+개.*") && // "5개만" 같은 개수 지정 제외
                !lowerPrompt.contains("제공") &&     // "제공해줘" 제외
                !lowerPrompt.contains("보여") &&     // "보여줘" 제외
                !lowerPrompt.contains("검색") &&     // "검색" 제외
                !lowerPrompt.contains("찾아");       // "찾아줘" 제외
    }

    /**
     * ⭐ 프롬프트에서 파일명 추출 (개선된 버전)
     */
    /**
     * 개선된 파일명 추출 (정확한 매칭)
     */
    private String extractFileNameFromPrompt(String prompt) {
        log.info("파일명 추출 시작: '{}'", prompt);

        // 1단계: 완전한 파일명 패턴 매칭 (가장 정확)
        Pattern fullFilePattern = Pattern.compile("([가-힣a-zA-Z0-9]+광역시\\s[가-구]+_[가-힣a-zA-Z0-9\\s]+_\\d{8})");
        Matcher fullMatcher = fullFilePattern.matcher(prompt);

        if (fullMatcher.find()) {
            String fileName = fullMatcher.group(1).trim();
            log.info("완전한 파일명 패턴으로 추출: '{}'", fileName);
            return fileName;
        }

        // 2단계: 부분 패턴 매칭
        Pattern partialPattern = Pattern.compile("([가-힣a-zA-Z0-9_\\s]+_\\d{8})");
        Matcher partialMatcher = partialPattern.matcher(prompt);

        if (partialMatcher.find()) {
            String fileName = partialMatcher.group(1).trim();
            log.info("부분 패턴으로 추출: '{}'", fileName);
            return fileName;
        }

        // 3단계: 기존 방식 (최후 수단)
        String fileName = prompt
                .replaceAll("(?i)(상세정보|자세히|더 알고|상세|에 대해|에 대한|의|을|를)", "")
                .trim();

        log.info("기존 방식으로 추출: '{}'", fileName);
        return fileName;
    }


    /**
     * ⭐ 데이터 정보를 보기 좋게 포맷팅
     */
    private String formatDataDetails(PublicData data) {
        StringBuilder details = new StringBuilder();

        details.append("📋 데이터 상세 정보\n");
        details.append("═".repeat(50)).append("\n\n");

        details.append("📄 파일명: ").append(data.getFileDataName() != null ? data.getFileDataName() : "정보 없음").append("\n\n");

        details.append("🏷️ 제목: ").append(data.getTitle() != null ? data.getTitle() : "정보 없음").append("\n\n");

        details.append("📂 분류체계: ").append(data.getClassificationSystem() != null ? data.getClassificationSystem() : "정보 없음").append("\n\n");

        details.append("🏢 제공기관: ").append(data.getProviderAgency() != null ? data.getProviderAgency() : "정보 없음").append("\n\n");

        details.append("📅 수정일: ").append(data.getModifiedDate() != null ? data.getModifiedDate().toString() : "정보 없음").append("\n\n");

        details.append("📎 확장자: ").append(data.getFileExtension() != null ? data.getFileExtension() : "정보 없음").append("\n\n");

        details.append("🔑 키워드: ").append(data.getKeywords() != null ? data.getKeywords() : "정보 없음").append("\n\n");

        if (data.getDescription() != null && !data.getDescription().trim().isEmpty()) {
            details.append("📝 상세 설명:\n");
            details.append("-".repeat(30)).append("\n");
            details.append(data.getDescription()).append("\n");
        } else {
            details.append("📝 상세 설명: 정보 없음\n");
        }

        return details.toString();
    }

    /**
     * 개선된 관련성 점수 계산 (설명 필드 포함)
     */
    private int calculateRelevanceScore(PublicData data, List<String> keywords, String originalPrompt) {
        int score = 0;
        String dataName = data.getFileDataName() != null ? data.getFileDataName().toLowerCase() : "";
        String dataKeywords = data.getKeywords() != null ? data.getKeywords().toLowerCase() : "";
        String dataTitle = data.getTitle() != null ? data.getTitle().toLowerCase() : "";
        String providerAgency = data.getProviderAgency() != null ? data.getProviderAgency().toLowerCase() : "";
        String description = data.getDescription() != null ? data.getDescription().toLowerCase() : "";

        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();

            // ⭐ 지역명 매칭에 압도적 점수
            if (providerAgency.contains(lowerKeyword)) {
                score += 200;
            }

            // 파일명에서 지역명 직접 매칭 (파일명 시작 부분)
            if (dataName.startsWith(lowerKeyword)) {
                score += 150;
            }

            // ⭐ 키워드 필드 정확 매칭에 높은 점수
            if (isKeywordExactMatch(dataKeywords, lowerKeyword)) {
                score += 100;
            } else if (dataKeywords.contains(lowerKeyword)) {
                score += 60;
            }

            // 파일명 일반 매칭
            if (dataName.contains(lowerKeyword)) {
                score += 40;
            }

            // 제목 매칭
            if (dataTitle.contains(lowerKeyword)) {
                score += 25;
            }

            // ⭐ 설명 필드 매칭
            if (description.contains(lowerKeyword)) {
                score += 30;
            }

            // ⭐ 설명 필드에서 복합 키워드 매칭
            if (keywords.size() >= 2) {
                String combinedKeywords = String.join(" ", keywords).toLowerCase();
                if (description.contains(combinedKeywords)) {
                    score += 50;
                }
            }
        }

        // ⭐ 첫 번째 키워드(주로 지역명)에 특별 가중치
        if (!keywords.isEmpty()) {
            String primaryKeyword = keywords.get(0).toLowerCase();

            if (isRegionKeyword(primaryKeyword)) {
                if (providerAgency.contains(primaryKeyword)) {
                    score += 100;
                }
                if (dataName.startsWith(primaryKeyword)) {
                    score += 80;
                }
                if (dataName.contains(primaryKeyword)) {
                    score += 50;
                }
                if (description.contains(primaryKeyword)) {
                    score += 40;
                }
            } else {
                if (providerAgency.contains(primaryKeyword)) {
                    score += 30;
                }
                if (dataName.contains(primaryKeyword)) {
                    score += 20;
                }
                if (description.contains(primaryKeyword)) {
                    score += 25;
                }
            }
        }

        // ⭐ 설명 필드 특화 점수 추가
        score += calculateDescriptionScore(description, keywords);

        // 최신 데이터 보너스
        if (data.getModifiedDate() != null) {
            try {
                if (data.getModifiedDate().isAfter(java.time.LocalDateTime.now().minusYears(1))) {
                    score += 20;
                }
            } catch (Exception e) {
                // 날짜 처리 오류 시 무시
            }
        }

        // 분류체계 일치 보너스
        if (data.getClassificationSystem() != null) {
            String classification = data.getClassificationSystem().toLowerCase();
            for (String keyword : keywords) {
                if (classification.contains(keyword.toLowerCase())) {
                    score += 20;
                }
            }
        }

        return Math.max(0, score);
    }

    /**
     * 설명 필드에서 상세 키워드 매칭
     */
    private int calculateDescriptionScore(String description, List<String> keywords) {
        if (description == null || description.isEmpty()) {
            return 0;
        }

        int score = 0;
        String lowerDescription = description.toLowerCase();

        // 개별 키워드 매칭
        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            if (lowerDescription.contains(lowerKeyword)) {
                score += 10;
            }
        }

        // 전문 용어 매칭
        String[] specialTerms = {
                "도시개발", "토지구획", "재개발", "재정비", "환지", "감보율", "시행인가",
                "대기오염", "수질오염", "폐기물", "배출시설", "환경영향", "오염물질",
                "교통사고", "교통위반", "교통체계", "대중교통", "교통량", "신호체계",
                "교육과정", "학습", "연구", "교육시설", "교육프로그램",
                "문화재", "관광지", "문화시설", "예술", "공연", "축제"
        };

        for (String term : specialTerms) {
            if (lowerDescription.contains(term)) {
                score += 25;
            }
        }

        // 키워드 밀도 계산
        long keywordCount = keywords.stream()
                .mapToLong(keyword -> {
                    String lowerKeyword = keyword.toLowerCase();
                    return (lowerDescription.length() - lowerDescription.replace(lowerKeyword, "").length())
                            / Math.max(lowerKeyword.length(), 1);
                })
                .sum();

        if (keywordCount > 2) {
            score += 20;
        }

        return score;
    }

    /**
     * 지역 키워드 식별
     */
    private boolean isRegionKeyword(String keyword) {
        return Arrays.asList(REGION_KEYWORDS).contains(keyword);
    }

    /**
     * 키워드 목록에서 지역명 추출
     */
    private String extractRegionFromKeywords(List<String> keywords) {
        return keywords.stream()
                .filter(this::isRegionKeyword)
                .findFirst()
                .orElse(null);
    }

    /**
     * 키워드 정확 매칭 헬퍼 메서드
     */
    private boolean isKeywordExactMatch(String dataKeywords, String searchKeyword) {
        if (dataKeywords == null || dataKeywords.isEmpty()) {
            return false;
        }

        String[] keywords = dataKeywords.split(",");
        for (String keyword : keywords) {
            String trimmedKeyword = keyword.trim().toLowerCase();
            if (trimmedKeyword.equals(searchKeyword) ||
                    trimmedKeyword.contains(searchKeyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 키워드별 검색 결과 통계 로깅 (디버깅용)
     */
    private void logSearchStatistics(List<String> keywords, List<PublicData> results) {
        if (!log.isDebugEnabled()) return;

        log.debug("=== 검색 통계 ===");
        log.debug("총 키워드 수: {}", keywords.size());
        log.debug("총 검색 결과: {}개", results.size());

        for (String keyword : keywords) {
            long matchCount = results.stream()
                    .filter(item -> {
                        String lowerKeyword = keyword.toLowerCase();
                        String dataName = item.getFileDataName() != null ? item.getFileDataName().toLowerCase() : "";
                        String dataKeywords = item.getKeywords() != null ? item.getKeywords().toLowerCase() : "";
                        return dataName.contains(lowerKeyword) || dataKeywords.contains(lowerKeyword);
                    })
                    .count();
            log.debug("키워드 '{}': {}개 매칭", keyword, matchCount);
        }

        results.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getClassificationSystem() != null ?
                                item.getClassificationSystem().split(" - ")[0] : "기타",
                        Collectors.counting()))
                .forEach((category, count) ->
                        log.debug("분류 '{}': {}개", category, count));
    }

    /**
     * 검색 결과 품질 검증
     */
    private boolean isQualityResult(PublicData data, List<String> keywords) {
        if (data.getFileDataName() == null || data.getFileDataName().trim().isEmpty()) {
            return false;
        }

        String dataText = (data.getFileDataName() + " " +
                (data.getKeywords() != null ? data.getKeywords() : "") + " " +
                (data.getTitle() != null ? data.getTitle() : "") + " " +
                (data.getDescription() != null ? data.getDescription() : "")).toLowerCase();

        return keywords.stream()
                .anyMatch(keyword -> dataText.contains(keyword.toLowerCase()));
    }
}
