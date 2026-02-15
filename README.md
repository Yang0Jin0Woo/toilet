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

- 공공 화장실 정보가 분산돼 빠른 조회가 어렵고, 위치 외 품질 정보(평점/리뷰)가 부족함.

-  프로젝트 진행 중 리뷰 누적에 따라 평균 평점 집계가 느려져 응답 지연이 발생할 수 있음

### 해결

- 카카오 지도 기반으로 실시간 위치 렌더링으로 주변 공공 화장실을 시각화

- 리뷰/별점 기능을 통해 화장실 품질 정보를 시각화 및 사용성 증가

- 평점 집계를 그룹 쿼리로 최적화하고 리스트 캐시(TTL)로 응답 지연 및 DB 부하 줄임(read 기준)


## 핵심 기능

- 실시간 위치 기반 지도 렌더링, 이동/줌 시 마커 재생성
 
- 줌 레벨별 Top-N 마커 제한(최대 히프 적용), 타입별 마커 색상 구분
 
- 인포윈도우에서 평균 평점/리뷰 수 표시 및 리뷰 페이지 이동

- 리뷰 등록/수정/삭제, 신고 누적 차단
- 데이터 상태 배너
  - 지도 오른쪽 상단: 로딩/성공/실패 및 화장실 수(줌 반영)
  - 지도 왼쪽 상단: 마커 색상(남/여/장애인/기타) 안내


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
 │   │   ├─ dto
 │   │   │   ├─ ToiletSnapshot.java
 │   │   │   └─ ToiletView.java
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
 │       ├─ static
 │       │   └─ seoultoilet.json
 │       └─ templates
 │           └─ map
 │               ├─ map.html
 │               ├─ map_benchmark.html
 │               └─ reviews.html
 └─ test
     └─ java/com/example/toilet
         ├─ DtoProjectionComparisonTest.java
         ├─ GroupAggComparisonTest.java
         ├─ GroupCacheComparisonTest.java
         ├─ SlowQueryTestConfig.java
         └─ ToiletApplicationTests.java
```

- `dto`
  - `ToiletSnapshot`: 리스트 캐시용 스냅샷(엔티티 미사용)
  - `ToiletView`: API 응답용 DTO(평점/리뷰 수 포함)


## 화면 구성

### 1) 지도 화면 (`/map`)

- 카카오 지도 로드 후 현 위치 표시, 이동/줌 시 마커 갱신.

- `/toilets` API 응답을 기반으로 마커 생성, 타입별 색상으로 구분.
 
- 인포윈도우에 평균 평점/리뷰 수를 표시하고 리뷰 페이지로 이동 링크 제공.

### 2) 리뷰 화면 (`/reviews?toiletId={id}`)

- 선택한 화장실 이름과 평균 평점/리뷰 수 표시.
 
- 리뷰 작성 폼과 등록/취소 버튼 제공.

- 리뷰 목록 표 표시.


## 데이터 초기화

- 애플리케이션 시작 시 `seoultoilet.json`을 파싱해 `toilet` 테이블에 적재.
 
- `toilet` 테이블에 데이터가 있으면 초기 적재를 건너뜀.

- 운영 환경에서는 최초 1회 적재 후 DB 데이터를 사용.


## 성능

- 집계 방식
  - 리뷰 평균/개수를 N+1 방식 대신 그룹 집계로 계산
    - 쿼리 방식으로 `개별 화장실별 집계` vs `group by` vs `fetch join`을 비교하며 캐시/TTL을 끈 상태에서 SQL 카운트와 agg/total 시간을 `RUNS=100`, `sampleToilets=4624`로 측정

|   집계 방식    | 리스트 조회 횟수 | 평점 집계 횟수 | 집계 연산 시간 | 전체 응답 시간 |
|:----------:|:---:|:---:|:---:|:---:|
| 개별 화장실별 집계 | 0 | 4,624 | 896 ~ 1,191 ms (평균 969 ms) | 896 ~ 1,191 ms (평균 969 ms) |
|  GROUP BY  | 0 | 1 | 9 ~ 12 ms (평균 10 ms) | 9 ~ 12 ms (평균 10 ms) |
| FETCH JOIN | 0 | 1 | 9 ~ 14 ms (평균 10 ms) | 9 ~ 14 ms (평균 10 ms) |


- 리스트 캐시
  - 리스트 캐시 TTL 60분, `list.cache.ttl-ms`로 조정 가능
  - 테스트 환경: 리스트 캐시 ON/OFF 비교(RUNS=100, 랜덤 지연 포함)
  - 리뷰 집계 지연과 동시 요청 시 합·개수 불일치로 지도/리뷰 평점이 어긋나는 문제가 있었으나, 리뷰 쓰기 트랜잭션에서 toilet.rating_sum/rating_count를 원자 업데이트하고 리뷰
    CRUD 시 리스트 캐시를 즉시 무효화 적용했고, 그 결과 리뷰 직후 최신 평점이 일관되게 노출됨
  - 추가 개선: 커밋 전 캐시 공백에서 구데이터가 재캐시될 수 있는 구간을 줄이기 위해 캐시 무효화와 SSE 발행을 `afterCommit`으로 이관하고, `afterCompletion`에서 트랜잭션 리소스(`pendingToiletIds` 등)를 정리해 정합성을 강화

|   구분   | 리스트 조회 횟수 | 집계 연산 횟수 | DB 호출 횟수 |            집계 연산 시간             | 전체 응답 시간  |
|:------:|:---------:|:--------:|:--------:|:-------------------------------:|:-------------------------------:|
| 캐시 미적용 |     1     |    1     |    2     |  18.00 ~ 32.00 ms ( 21.05 ms)   | 47.69 ~ 70.15 ms (평균 54.91 ms) |
| 캐시 적용  |     0     |    1     |    1     | 17.00 ~ 22.00 ms (평균 19.94 ms) | 17.91 ~ 24.73 ms (평균 22.11 ms) |


- 동시성 제어
  - 리뷰 `create/update/delete/report`에 `synchronized` 적용(단일 인스턴스 락)
    - 저장/수정/삭제/신고가 읽기-수정-저장으로 이어져 경쟁 시 정합성 문제가 생길 수 있어 단일 인스턴스에서 직렬화
    - 멀티 인스턴스 환경에서는 DB 락(낙관적/비관적) 또는 Redis 분산 락으로 보완 필요
  - 테스트 환경: 단일 인스턴스 기준으로 수정 경로만 동시 요청을 직렬화하여 측정(runs 100번, 스레드 8, 딜레이 50 ms 적용)
  - 낙관적 락(Optimistic Lock)
    - `review`에 `@Version` 컬럼을 두어 갱신 시 충돌을 감지하고 실패를 반환
    - 충돌 발생 시 리뷰 화면으로 리다이렉트하고 플래시 메시지로 안내
    - DB 스키마에 `version BIGINT NOT NULL DEFAULT 0` 컬럼 필요

| 구분 | 동시 업데이트 충돌 수 | 일관성 검증 실패 횟수 | 최종 값 불일치 횟수 |
|:---:|:---:|:---:|:---:|
| 적용 전 | 100 | 100 | 100 |
| 적용 후 | 0 | 0 | 0 |


- 인터페이스 기반 DTO 프로젝션
    - 리스트 조회와 평점 집계에서 엔티티 전체를 반환하는 대신 인터페이스 기반 DTO 프로젝션을 적용해 필요한 컬럼만 조회/전송.

| TYPE(100회) | 전체 처리 시간 (ms, min~p95(avg)) | 페이로드 크기 (min~p95(avg)) | 메모리 사용량 (min~p95(avg)) | 결과 레코드 수 | 요청 당 SQL 수 | 요청 당 SQL 시간 (ms, min~p95(avg)) |
|:-------------:|:-------------------------------:|:----------------------------------:|:----------------------------------:|:--------:|:----------:|:-----------------------------------:|
| 리스트 엔티티 | 13~17(14) | 1.96MB~1.96MB(1.96MB) | 12.58MB~13.11MB(12.64MB) | 4624 | 1 | 5~8(6) |
| 리스트 프로젝션 | 35~46(41) | 1.26MB~1.26MB(1.26MB) | 10.64MB~14.65MB(13.95MB) | 4624 | 1 | 5~7(5) |
| 평점 엔티티 | 10~14(12) | 2B~2B(2B) | 6.25MB~6.29MB(6.37MB) | 0 | 1 | 3~6(4) |
| 평점 프로젝션 | 8~13(10) | 2B~2B(2B) | 6.17MB~6.29MB(6.31MB) | 0 | 1 | 2~4(3) |


- 최대 히프 적용
  - 클라이언트 렌더링에서 가까운 K개만 유지하기 위해 최대 히프 사용
    - 렌더링 직전에는 거리 기준으로 한 번 일반 정렬하여 마커 표시
  - 지도 렌더링은 전체 정렬 대신 최대 히프 사용

|         구분          |             ALL SORT              |           N개 SORT           |           N개 HEAP           |
|:-------------------:|:---------------------------------:|:---------------------------:|:---------------------------:|
|  전체 처리 시간 (Total)   | 334.30 ~ 390.80 ms (평균 362.13 ms) | 5.30 ~ 8.70 ms (평균 6.29 ms) | 4.60 ~ 7.20 ms (평균 5.10 ms) |
| 오버헤드 시간 (Overhead)  | 329.40 ~ 385.70 ms (평균 357.01 ms) | 3.50 ~ 7.90 ms (평균 5.64 ms) | 3.10 ~ 6.70 ms (평균 4.50 ms) |
| 거리 계산 시간 (Distance) |    4.60 ~ 5.40 ms (평균 5.12 ms)    | 0.40 ~ 1.00 ms (평균 0.65 ms) | 0.40 ~ 0.90 ms (평균 0.60 ms) |

- 실시간 갱신 (SSE)
  - 리뷰/평점 변경 시 지도 인포윈도우 및 리뷰 화면 상단을 즉시 갱신
  - 이벤트는 트랜잭션 커밋 이후 발행해 정합성 확보(중간 상태 노출 방지)
  - 성능 관점(정성)
    - 연결 수에 비례해 유지되는 SSE 스트림이 증가하지만, 롱폴링 대비 요청 수는 감소
    - 이벤트 빈도는 리뷰 쓰기 트래픽에 비례하며 읽기 트래픽 증가는 없음
    - 페이로드는 `toiletId/avgRating/reviewCount`만 전송하여 네트워크 비용 최소화
  - SSE 선택 이유
    - 서버→클라이언트 단방향 알림에 적합하고 구현이 단순
    - HTTP 기반이라 인프라/프록시 호환성이 높고 운영 비용이 낮음
    - WebSocket 대비 상태/프로토콜 관리 복잡도가 낮음

  - SSE 적용 후 성능 이슈(문제 → 해결 → 결과)
    - 문제: 리뷰 CRUD 시 간헐적으로 20~30초 Stalled 발생(브라우저 커넥션 풀 고갈)
    - 해결: 트랜잭션 afterCommit 이후 비동기 발행, bounded queue + DiscardOldestPolicy, toiletId 단위 coalescing으로 backpressure 처리
    - 결과: Stalled 재발 방지 및 리뷰 CRUD 응답 안정화(평균 수십 ms 수준)
    - 문제: 페이지 전환/bfcache에서 SSE·Geolocation 잔존으로 리소스 누적
    - 해결: 페이지당 SSE 1개만 유지, `pagehide/visibilitychange`에서 close/clearWatch 수행
    - 결과: 백그라운드 잔존 작업 제거로 지연 재발 가능성 감소



## 한계/향후 개선

- 캐시는 인메모리 기반이라 서버 확장 시 일관성 문제가 발생할 수 있음
  - Redis 등 분산 캐시 도입 및 single-flight/락 적용으로 스탬피드 완화 필요

- 초기 데이터는 `seoultoilet.json`에 의존하며 자동 갱신이 없음
  - 외부 데이터 소스 연동 및 주기적 동기화 작업 필요

- 캐시 TTL로 최신성/성능을 절충하고 있어 일부 구간에서 스테일 데이터가 노출될 수 있음
  - 이벤트 기반 무효화 또는 백그라운드 리프레시로 보완 고려


---

## 테스트

- `ToiletApplicationTests`
  - Spring 컨텍스트 로딩 스모크 테스트
- `GroupAggComparisonTest`
  - 리뷰 평균/개수 집계 방식 3-way 비교(`per_toilet` vs `group by` vs `fetch join`), 100회 실행, `sampleToilets=4624`, 캐시 X
- `GroupCacheComparisonTest`
  - 그룹 집계 + 리스트 캐시 적용 전/후 비교, 100회 실행, TTL= 10초, 지연 시뮬레이션(랜덤 0~200ms, 10% 확률 3.5~4.5s)
- `ReviewSynchronizedConcurrencyTest`
  - 동시 업데이트에서 `synchronized` 적용 전/후 정합성 비교, 100회 실행
- `DtoProjectionComparisonTest`
  - 리스트/평점 집계에서 엔티티 vs DTO 프로젝션 성능 비교, 100회 실행
- `SlowQueryTestConfig`
  - DataSource 프록시로 SQL 횟수/시간을 카운트하는 테스트 설정


## 기술 스택

- Java, Spring Boot, Spring Data JPA
- MySQL, H2
- Thymeleaf, Bootstrap, Kakao Maps
- Gradle

## 프로젝트 보기
- [공공 화장실 맵 프로젝트 시연 보기](https://youtu.be/srk0vQrEu9E)

## AWS EC2 & RDS
- http://3.36.128.192:8080/map (종료됨)
