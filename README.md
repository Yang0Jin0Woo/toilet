# Public Toilet Map (with 카카오 맵)

카카오 맵을 이용해 **서울시 공공 화장실 위치**를 시각화하고, 사용자가 **별점/리뷰**를 남길 수 있는 프로젝트입니다.  

## 스크린샷

<p align="center">
    <img src="img.png" alt="지도 메인 화면" width="85%">
</p>

<p align="center">
    <img src="review.png" alt="인포윈도우 + 리뷰 요약" width="85%">
</p>

<p align="center">
    <img src="reviewupdate.png" alt="리뷰 목록/작성 화면" width="85%">
</p>

---

## 문제 정의

- **정보 파편화**:
  - 공공 화장실의 데이터가 일관성 없이 제공되어 사용자가 빠르게 확인하기 어려움.

- **사용자 경험 부족**
  - 위치만 알 수 있으나, 화장실의 품질 정보를 알기 어려움.

- **평점 집계 성능 문제**
  - 리뷰가 누적될수록 평균 평점 계산이 느려져 응답 시간이 증가할 수 있음.

### 해결

- **실시간 위치 기반**
  - 현재 위치 중심 가장 가까운 공공 화장실 마커 표시.

- **각 화장실 리뷰**
  - 사용자 리뷰와 별점을 통해 평균 별점 표현.

- **간단하고 직관적인 UI/UX**
  - 빠르게 확인, 참여 가능.

- **평점 집계 최적화**
  - 그룹 집계 쿼리 + 캐시로 응답 시간을 개선하고, TTL로 데이터 일관성 보완.

---

## 핵심 기능

- **실시간 위치 기반 지도 렌더링**
- **타입별 마커 색상 구분**
- **인포윈도우 + 리뷰 요약**
- **리뷰 작성/목록/수정/삭제(+신고 누적), 금지어 방지, 평균 별점 및 리뷰 표시**
- **데이터 상태 배너 & 범례**

## API 요약

- `GET /map`: 카카오 지도 화면
- `GET /toilets?withRatings=true`: 화장실 목록(+평점/리뷰 수 포함)
- `GET /reviews?toiletId={id}`: 특정 화장실 리뷰 목록/작성 화면
- `POST /reviews`: 리뷰 등록
- `POST /reviews/update`: 리뷰 수정
- `POST /reviews/delete`: 리뷰 삭제

## 아키텍처 구조

<p>
  <img src="seoultoiletmap.png"
       alt="EC2 Spring Boot → RDS(MySQL), Kakao Maps JS SDK, JSON seed(read)">
</p>

## 프로젝트 구조

```
src
 ├─ main
 │   ├─ java/com/example/toilet
 │   │   ├─ ToiletApplication.java
 │   │   ├─ controller
 │   │   │   ├─ MapController.java
 │   │   │   ├─ ReviewController.java
 │   │   │   └─ ToiletController.java
 │   │   ├─ domain
 │   │   │   ├─ Review.java
 │   │   │   └─ Toilet.java
 │   │   ├─ repository
 │   │   │   ├─ ReviewRepository.java
 │   │   │   └─ ToiletRepository.java
 │   │   └─ service
 │   │       ├─ ReviewService.java
 │   │       └─ ToiletService.java
 │   └─ resources
 │       ├─ application.properties
 │       ├─ schema.sql
 │       ├─ seoultoilet.json
 │       └─ templates
 │           └─ map
 │               ├─ map.html
 │               └─ reviews.html
 └─ test
     └─ java/com/example/toilet
         ├─ CacheConsistencyMismatchTest.java
         ├─ CacheTtlPerfTest.java
         ├─ QueryCountProofTest.java
         ├─ QueryLogDiagnosticsTest.java
         ├─ RatingAggComparisonTest.java
         ├─ SlowQueryTestConfig.java
         └─ ToiletApplicationTests.java
```

## 화면 구성

### 1) 지도 화면 (`/map`)
- 카카오 지도 로드 후, **실시간 위치 갱신**.
- `/toilets` API로 받은 JSON을 순회 후 마커 생성, **인포윈도우 내 평균 평점/리뷰 수 표시**, **리뷰 페이지 이동 링크**.

### 2) 리뷰 화면 (`/reviews?toiletId={id}`)
- **평균 별점** 및 화장실 이름 표시.
- **리뷰 등록 폼**(별점, 리뷰 입력), **등록/취소** 버튼.
- **리뷰 목록 표**(별점, 리뷰, 등록일).

## 데이터 초기화

- 애플리케이션 시작 시 `seoultoilet.json` 파싱 후 DB 적재.
- `toilet` 테이블에 데이터가 있으면 건너 뜀.
- 실제 서비스에서는 최초 1회만 적재, 이후 DB 조회 수행.

## 성능
| 구분                         | DB 집계 시간 (aggMs)                   | 전체 응답 시간 (totalMs) | DB 접근 특성                                         |
|----------------------------| ---------------------------------- | ------------------ | ------------------------------------------------ |
| **그룹 X**                   | **695 ~ 773 ms**                   | **710 ~ 790 ms**   | 화장실별 집계 쿼리 반복 실행<br>→ **N+1 쿼리 발생**              |
| **그룹 O + 캐시 X**            | **9 ~ 13 ms**                      | **24 ~ 32 ms**     | 매 요청마다 GROUP BY 집계 수행<br>→ **조회 빈도 증가 시 DB 비효율** |
| **그룹 O + 캐시 O**<br>(캐시 히트) | **집계 쿼리 생략**<br>(캐시 미스 시 DB 비용 발생) | **12 ~ 17 ms**     | 최초 1회 집계 후 캐시 재사용<br>→ **반복 조회 시 DB 부하 제거**      |
| **TTL 만료 후 재집계**           | **9 ~ 13 ms**                      | **22 ~ 33 ms**     | TTL 만료로 집계 쿼리 재실행<br>→ **일시적으로 캐시 미적용 상태와 유사**   |

- **집계 방식**: 리뷰 평균/개수를 N+1 방식 대신 그룹 집계로 계산.
- **캐시 적용**: 집계 결과를 캐시에 저장해 반복 조회를 줄임.
- **TTL 사용**: 기본 5분 TTL로 만료 후 재집계하여 일관성 보완.
- **한계/향후 개선**
  - 캐시 스탬피드 발생 가능
  - 분산 캐시 일관성 이슈
  - 트래픽 증가 시 single-flight/락 또는 외부 캐시 적용 검토
    - single-flight: 동시에 같은 데이터 요청이 몰리면, 첫 요청만 실제 작업하고 나머지는 결과 공유
    - 락: 특정 키에 대해 한 번에 하나의 요청만 처리하고, 나머지는 대기

## 테스트

- `ToiletApplicationTests`: Spring 컨텍스트 로딩 스모크 테스트.
- `RatingAggComparisonTest`: per-toilet vs group vs cache warm 비교, `/toilets?withRatings=true` 응답 헤더 `X-Total-Ms`, `X-Agg-Ms` 로그.
- `CacheTtlPerfTest`: TTL(3초)에서 NO_CACHE/WARM/TTL_EXPIRE_SPIKE 구간 성능 및 캐시 히트/미스 통계 출력.
- `CacheConsistencyMismatchTest`: DB 직접 변경 시 캐시/DB 불일치 재현 후 `evict`로 복구 확인.
- `QueryLogDiagnosticsTest`: cache on/off에서 SQL 로그와 GROUP BY 감지 로그 확인.
- `QueryCountProofTest`: datasource-proxy로 쿼리 타입별 카운트(SELECT/INSERT/UPDATE/DELETE) 증빙.
- `SlowQueryTestConfig`: 느린 쿼리 경고 및 SQL 로그 공통 설정.
- `RenderLatencyStatsTest`: `seoultoilet.json` 기준 좌표를 사용해 sort vs heap 선택 알고리즘의 계산 시간 비교.
- 대부분 `SpringBootTest`로 실행되며 로컬 DB와 초기 데이터 로딩이 필요.
## 기술 스택

![Java](https://img.shields.io/badge/Java-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![H2 Database](https://img.shields.io/badge/H2-007ACC?style=for-the-badge&logo=h2&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Thymeleaf](https://img.shields.io/badge/Thymeleaf-005F0F?style=for-the-badge&logo=thymeleaf&logoColor=white)
![Bootstrap](https://img.shields.io/badge/Bootstrap-7952B3?style=for-the-badge&logo=bootstrap&logoColor=white)
![Kakao Maps](https://img.shields.io/badge/Kakao%20Maps-FFCD00?style=for-the-badge&logo=kakao&logoColor=black)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)

## 프로젝트 보기
- [공공 화장실 맵 프로젝트 시연 보기](https://youtu.be/srk0vQrEu9E)

## AWS EC2 & RDS
- http://3.36.128.192:8080/map (종료됨)

