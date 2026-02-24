# Public Toilet Map (with 카카오 맵)

카카오 맵을 이용해 **서울시 공공 화장실 위치**를 시각화하고, 사용자가 **별점/리뷰**를 남길 수 있는 프로젝트입니다.  

## 스크린샷

### 1) 지도 메인 화면
<p align="center">
  <img src="/img/img.png" alt="지도 메인 화면" width="85%">
</p>

### 2) 로그인 전 리뷰 페이지
<p align="center">
  <img src="/img/beforelogin.png" alt="로그인 이전 리뷰 페이지" width="85%">
</p>

### 3) 회원가입 화면
<p align="center">
  <img src="/img/signup.png" alt="회원가입" width="85%">
</p>

### 4) 로그인 후 리뷰 페이지
<p align="center">
  <img src="/img/afterloginreviewlise.png" alt="로그인 이후 리뷰 페이지" width="85%">
</p>

### 5) 리뷰 목록
<p align="center">
  <img src="/img/reviews.png" alt="리뷰 목록" width="85%">
</p>

---

## 문제 정의

- 공공 화장실 정보가 분산돼 빠른 조회가 어렵고, 위치 외 품질 정보(평점/리뷰)가 부족함.

-  프로젝트 진행 중 리뷰 누적에 따라 평균 평점 집계가 느려져 응답 지연이 발생할 수 있음

### 해결

- 카카오 지도 기반으로 실시간 위치 렌더링으로 주변 공공 화장실을 시각화

- 리뷰/별점 기능을 통해 화장실 품질 정보를 시각화 및 사용성 증가

- 평균 평점/리뷰 수 조회의 N+1 문제를 개선하고 리스트 캐시(TTL)로 응답 지연 및 DB 부하를 줄임(read 기준)


## 핵심 기능

- 실시간 위치 기반 지도 렌더링, 이동/줌 시 마커 재생성
 
- 줌 레벨별 Top-N 마커 제한(최대 히프 적용), 타입별 마커 색상 구분
 
- 인포윈도우에서 평균 평점/리뷰 수 표시 및 리뷰 페이지 이동

- 세션 기반 로그인/회원가입/로그아웃/회원탈퇴

- 리뷰 등록/수정/삭제, 신고 누적 차단
  - 리뷰 작성은 로그인 사용자만 가능
  - 리뷰 수정/삭제는 본인 작성 리뷰만 가능
- 데이터 상태 배너
  - 지도 오른쪽 상단: 로딩/성공/실패 및 화장실 수(줌 반영)
  - 지도 왼쪽 상단: 마커 색상(남/여/장애인/기타) 안내


## API 요약

- `GET /map`: 카카오 지도 화면
 
- `GET /toilets?withRatings=true`: 화장실 목록(+평점/리뷰 수 포함)
 
- `GET /reviews?toiletId={id}`: 특정 화장실 리뷰 목록/작성 화면
 
- `GET /signup`: 회원가입 화면

- `POST /signup`: 회원가입

- `POST /login`: 로그인

- `POST /logout`: 로그아웃

- `POST /account/delete`: 회원탈퇴(비밀번호 확인)

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
 │   │   │   ├─ AuthController.java
 │   │   │   ├─ ReviewController.java
 │   │   │   └─ ToiletController.java
 │   │   ├─ dto
 │   │   │   ├─ ToiletSnapshot.java
 │   │   │   └─ ToiletView.java
 │   │   ├─ domain
 │   │   │   ├─ AppUser.java
 │   │   │   ├─ Review.java
 │   │   │   └─ Toilet.java
 │   │   ├─ repository
 │   │   │   ├─ AppUserRepository.java
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
 │           ├─ signup.html
 │           └─ map
 │               ├─ map.html
 │               ├─ map_benchmark.html
 │               └─ reviews.html
 └─ test
     └─ java/com/example/toilet
         ├─ DtoProjectionComparisonTest.java
         ├─ GroupAggComparisonTest.java
         ├─ GroupCacheComparisonTest.java
         ├─ ReviewPageViewConcurrencyTest.java
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

- 우측 상단 로그인 링크 클릭 시 작은 로그인 모달 표시, 하단 회원가입 링크로 `signup.html` 이동.

- 로그인 상태에서는 로그아웃/회원탈퇴 버튼 표시.

### 3) 회원가입 화면 (`/signup`)

- 아이디/이메일/비밀번호/비밀번호 확인 입력 후 가입.

- 가입 완료 시 자동 로그인되고 기존 리뷰 화면으로 복귀.


## 데이터 초기화

- 애플리케이션 시작 시 `seoultoilet.json`을 파싱해 `toilet` 테이블에 적재.
 
- `toilet` 테이블에 데이터가 있으면 초기 적재를 건너뜀.

- 운영 환경에서는 최초 1회 적재 후 DB 데이터를 사용.


## 성능

- N+1 문제 개선(목록 조회)
  - 동일 요구사항(화장실 목록 + 평균 평점/리뷰 수) 기준으로 `N+1_PROBLEM`, `FETCH_JOIN`, `BATCH_FETCH`, `DTO_PROJECTION`을 비교
  - 측정 조건: `RUNS=100`, `sampleToilets=4624`, `batchFetchSize=100`, 캐시 비활성화

| 전략 | 목록 쿼리 수 | 리뷰 집계 쿼리 수 | 총 DB 쿼리 수 | 행 수 | 전체 시간 (ms, min~P95(avg)) |
|:---:|:---:|:---:|:---:|:---:|:---:|
| N+1_PROBLEM | 0 | 4,624 | 4,624 | 4,624 | 892~969 ms (925 ms) |
| FETCH_JOIN | 1 | 1 | 2 | 4,624 | 28~37 ms (32 ms) |
| BATCH_FETCH | 1 | 1 | 2 | 4,624 | 27~36 ms (31 ms) |
| DTO_PROJECTION | 1 | 0 | 1 | 4,624 | 19~28 ms (22 ms) |


- 리스트 캐시
  - 리스트 캐시 TTL 60분, `list.cache.ttl-ms`로 조정 가능
  - 테스트 환경: DTO 프로젝션 경로에서 리스트 캐시 ON/OFF 비교(`RUNS=100`, `ttlMs=30000/3000`, 장기 지연 10%(3.5~4.5s), 단기 지연 최대 200ms)
  - 리뷰 집계 지연과 동시 요청 시 합·개수 불일치로 지도/리뷰 평점이 어긋나는 문제가 있었으나, 리뷰 쓰기 트랜잭션에서 toilet.rating_sum/rating_count를 원자 업데이트하고 리뷰
    CRUD 시 리스트 캐시를 즉시 무효화 적용했고, 그 결과 리뷰 직후 최신 평점이 일관되게 노출됨
  - 추가 개선: 커밋 전 캐시 공백에서 구데이터가 재캐시될 수 있는 구간을 줄이기 위해 캐시 무효화와 SSE 발행을 `afterCommit`으로 이관하고, `afterCompletion`에서 트랜잭션 리소스(`pendingToiletIds` 등)를 정리해 정합성을 강화

| 구분 | 목록 쿼리 수 | 리뷰 집계 쿼리 수 | 총 DB 쿼리 수 | 집계 시간 (ms, min~P95(avg)) | 전체 시간 (ms, min~P95(avg)) |
|:---:|:---:|:---:|:---:|:---:|:---:|
| DTO_CACHE_OFF | 1 | 0 | 1 | 0.00~0.00 ms (0.02 ms) | 34.81~58.33 ms (41.78 ms) |
| DTO_CACHE_ON_30SEC | 0 | 0 | 0 | 0.00~0.00 ms (0.01 ms) | 0.07~0.27 ms (0.55 ms) |
| DTO_CACHE_ON_3SEC | 0 | 0 | 0 | 0.00~0.00 ms (0.00 ms) | 0.05~38.97 ms (4.10 ms) |


- 동시성 제어
  - 조회 수 증가 로직 개선(문제 -> 해결 -> 결과)
    - 문제: `review_page_view`를 `select 후 +1 계산 -> update` 방식으로 처리해 동시 요청 시 유실 업데이트가 발생하고, 100명 동시 조회에서도 최종 조회 수가 100보다 작아질 수 있었음
    - 해결: `@Modifying` + native query로 `insert ... on duplicate key update view_count = view_count + 1` 원자 업데이트를 적용해 DB 단에서 증가 연산을 직렬화
    - 결과: 조회 수 동시성 테스트(`ReviewPageViewConcurrencyTest`)에서 100명 동시 조회 시 최종 조회 수 100을 안정적으로 만족
  - 조회수 업데이트 전략 성능/정합성 비교(`ReviewPageViewUpdatePerfComparisonTest`)
    - 조건: `runs=100`, `concurrentUsers=1000`, 동일 `toiletId`에 동시 시작

| 전략 | 총 수행시간 min~P95(평균) | 최종 조회수 min~P95(평균) | 유실 업데이트율 min~P95(평균) |
|:---:|:---:|:---:|:---:|
| 동시성 제어 미적용 | 225.81 ms ~ 664.84 ms (504.75 ms) | 4 ~ 100 (82) | 90.00% ~ 98.70% (91.80%) |
| 원자 업데이트 | 810.37 ms ~ 950.04 ms (910.30 ms) | 1000 ~ 1000 (1000) | 0.00% ~ 0.00% (0.00%) |
| 비관적 락 | 1017.13 ms ~ 1243.17 ms (1093.44 ms) | 1000 ~ 1000 (1000) | 0.00% ~ 0.00% (0.00%) |

  - 조회수 정책 보강(문제 -> 해결 -> 결과)
    - 문제: 동일 사용자의 새로고침/반복 진입, 쿠키 미사용 자동화 요청으로 조회수가 과대 집계되어 지표 신뢰도가 떨어질 수 있었음
    - 해결: 동일 사용자-동일 화장실 재조회는 쿠키 기반 1분 윈도우에서 조회수 증가 제외, 추가로 `IP+UA` 기준 1분 30회 초과 요청은 조회수 증가 제외
    - 결과: 단시간 반복 요청과 비정상 트래픽으로 인한 조회수 왜곡을 완화하고 운영 지표 해석 가능성을 개선
  - 조회수 정책 보강2(문제 -> 해결 -> 결과)
    - 문제: `skipViewCount` 외부 파라미터 악용, 화장실별 쿠키 다건 생성, `IP+UA` 전역 제한으로 정상 탐색까지 막히는 과차단 이슈가 있었음
    - 해결: `skipViewCount`를 외부 입력으로 받지 않고 서버 내부 1회성 플래그로만 증가 제외 처리, `toiletId`별 쿠키를 단일 히스토리 쿠키로 통합(LRU 유사 + 만료 pruning, 최대 40개 상한), 쿠키 값 길이 과대 시 현재 항목만 남기도록 fail-safe 적용, `isViewRateLimited()`를 `ip|ua|toiletId` 기준으로 세분화
    - 결과: 파라미터 조작 가능성을 제거하고 쿠키 폭증/과차단을 완화해 조회수 지표의 안정성과 신뢰도를 개선
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

- 세션 기반 로그인 적용 후 500 오류 개선
  - 문제: 로그인/회원가입 도입 후 리뷰 페이지 진입과 회원가입에서 500 오류가 발생
    - `app_user`/`review` 스키마와 애플리케이션 엔티티 간 컬럼 불일치(`username`, `email`, `user_id`)로 SQLGrammarException 발생
    - 회원가입 시 `app_user.email NOT NULL` 조건이 있는데 저장값 누락으로 insert 실패
    - 폼 검증 실패가 `ConstraintViolationException`으로 전파되어 500으로 노출되는 케이스 존재
  - 해결:
    - 시작 시점 스키마 보정 로직 추가: 누락 테이블/컬럼/인덱스/FK 자동 점검 및 보완
    - 회원가입 폼/엔티티/저장 로직에 `email` 필드를 일치시켜 NOT NULL 제약 충족
    - 회원가입 검증 흐름을 `BindingResult` 기반으로 정리해 유효성 오류를 400/폼 메시지로 처리
    - 리뷰 로딩 구간에 예외 방어를 추가해 장애 시에도 페이지 렌더링을 유지
  - 결과:
    - 마커 클릭 후 리뷰 페이지 진입 시 500 재현이 해소되고 HTTP 200으로 정상 응답
    - 회원가입 요청이 DB 제약을 충족하며 정상 완료(자동 로그인/복귀 동작 포함)
    - 인증 기능 추가 이후에도 기존 지도/리뷰 핵심 플로우를 유지하면서 안정성을 강화

- 세션 기반 인증 보안 강화(CSRF/세션 고정 공격 방어)
  - 문제: 세션 기반 인증을 사용하면서 모든 POST 요청에 대한 위조 요청 방어(CSRF)와 로그인 시 세션 재사용 방어가 명시적으로 적용되지 않아 보안 리스크 존재
  - 해결:
    - `spring-boot-starter-security`를 도입하되 기존 동작 호환을 위해 엔드포인트 접근 정책은 `permitAll`로 유지
    - 커스텀 `SecurityFilterChain`을 추가해 기본 폼 로그인/HTTP Basic/기본 로그아웃 필터를 비활성화하고, CSRF 보호는 활성화
    - 회원가입/로그인/로그아웃/회원탈퇴/리뷰 등록·수정·삭제·신고 등 모든 POST 폼에 CSRF 토큰 hidden 필드 추가
    - 로그인 성공 시 `request.changeSessionId()`를 호출해 세션 ID를 재발급
  - 결과:
    - 기존 URL/화면/비즈니스 플로우(지도 조회, 리뷰 CRUD, 세션 로그인/회원가입/탈퇴, SSE)는 유지
    - 정상 사용자 요청은 동일하게 동작하고, CSRF 토큰이 없는 비정상 POST 요청은 서버에서 차단(403)
    - 인증 세션 탈취/고정 공격 가능성을 낮춰 보안 안정성을 개선



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
  - 동일 목록 조회 요구사항에서 N+1 개선 전략 비교(`N+1_PROBLEM` vs `FETCH_JOIN` vs `BATCH_FETCH` vs `DTO_PROJECTION`), 100회 실행, `sampleToilets=4624`, `batchFetchSize=100`, 캐시 X
  - 전략 실행 순서를 run마다 랜덤화하고, 전략 간 결과(sum/count) 동등성을 검증해 성능/정확성을 함께 확인
- `GroupCacheComparisonTest`
  - DTO 프로젝션 경로에서 리스트 캐시 비교(`DTO_CACHE_OFF` vs `DTO_CACHE_ON_30SEC` vs `DTO_CACHE_ON_3SEC`), 100회 실행
  - 지연 시뮬레이션(랜덤 0~200ms, 10% 확률 3.5~4.5s)과 TTL(30초/3초) 조건에서 SQL 수와 응답시간을 측정
  - 캐시 ON/OFF 모두 리뷰 집계 쿼리 미발생(`ratingAggCount=0`)을 검증
- `ReviewPageViewConcurrencyTest`
  - 조회수 테이블(`review_page_view`)을 0으로 초기화한 뒤, 100개 스레드를 동시에 시작해 조회수 증가를 수행하고 최종 값이 기대값(100)과 일치하는지 검증
  - 테스트 로그에 `expected/actual/match(일치|불일치)`를 출력해 실패 시 원인 파악을 빠르게 지원
- `ReviewSynchronizedConcurrencyTest`
  - 동시 업데이트에서 `synchronized` 적용 전/후 정합성 비교, 100회 실행
- `DtoProjectionComparisonTest`
  - 리스트/평점 집계에서 엔티티 vs DTO 프로젝션 회귀/성능 비교, 100회 실행
  - 결과 정합성(행 수/집계값), SQL 수, 전체 시간/메모리/페이로드를 함께 측정
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
