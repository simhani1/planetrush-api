# 초고밀도 경쟁 구간 대안 설계 문서

## 개요

5000명 같은 초고밀도 동시 요청 구간에서는 비관적 락과 낙관적 락만으로 처리 품질을 보장하기 어렵다. 특히 아래 문제가 동시에 나타날 수 있다.

- DB row 경쟁 집중
- 애플리케이션 스레드 점유 증가
- 예외 폭증
- 응답 지연 증가
- 연결 timeout 또는 큐 적체

이 문서는 락 전략 외에 검토할 수 있는 구조적 대안 3가지를 정리한다.

- 큐잉
- 선착순 토큰화
- 예약 테이블 분리

## 1. 큐잉 방식

### 개념

사용자의 행성 참여 요청을 즉시 DB에 반영하지 않고, 먼저 메시지 큐에 적재한 뒤 소비자(worker)가 순차 또는 제한된 병렬도로 처리하는 방식이다.

즉 요청 흐름을 아래처럼 바꾼다.

1. API 서버는 참여 요청을 검증하고 큐에 넣는다.
2. 사용자에게는 "접수됨" 응답을 빠르게 반환한다.
3. 백그라운드 소비자가 큐에서 하나씩 꺼내 실제 참여 처리한다.

### 언제 적합한가

- 순간 트래픽이 매우 크고 짧게 몰리는 경우
- 실시간 즉시 확정이 아니라 "잠시 후 결과 확인" UX가 허용되는 경우
- 동일 자원에 대한 충돌이 아주 빈번한 경우

### 장점

- API 응답 속도를 빠르게 유지하기 쉽다.
- DB 경쟁을 소비자 수만큼 제한할 수 있다.
- 재시도, dead-letter queue, 모니터링 설계가 용이하다.

### 단점

- 즉시 확정 응답이 어렵다.
- 큐 적체 시 지연이 증가한다.
- 중복 메시지, 순서 보장, idempotency를 별도로 설계해야 한다.

### 예시 코드

API에서 큐에 적재:

```java
@PostMapping("/planets/{planet-id}/join")
public ResponseEntity<BaseResponse<?>> enqueueJoin(@PathVariable("planet-id") Long planetId) {
	Long memberId = MemberContext.getMemberId();
	joinQueuePublisher.publish(new PlanetJoinMessage(memberId, planetId));
	return ResponseEntity.accepted().body(BaseResponse.ofSuccess());
}
```

메시지 정의:

```java
public record PlanetJoinMessage(Long memberId, Long planetId) {
}
```

소비자에서 실제 처리:

```java
@Component
@RequiredArgsConstructor
public class PlanetJoinConsumer {

	private final PlanetService planetService;

	@KafkaListener(topics = "planet-join")
	public void consume(PlanetJoinMessage message) {
		planetService.registerResident(
			PlanetSubscriptionDto.builder()
				.memberId(message.memberId())
				.planetId(message.planetId())
				.build()
		);
	}
}
```

### PlanetRush 적용 포인트

- 현재 `registerResident()` 자체는 유지하고, 컨트롤러 앞단만 큐 접수형으로 바꿀 수 있다.
- 이미 메시지 발행 구조가 일부 있으므로 큐 도입 난이도는 상대적으로 낮을 수 있다.

## 2. 선착순 토큰화 방식

### 개념

실제 DB 참여 처리 전에 "참여 시도 권한 토큰"을 먼저 발급하거나 선점하게 해서, 동시 요청을 API 입구에서 줄이는 방식이다.

핵심은 모두가 바로 DB row를 두드리지 않게 하는 것이다.

예를 들어:

1. 사용자는 참여 요청 전에 토큰 획득 API를 호출한다.
2. 서버는 Redis 같은 빠른 저장소에서 선착순으로 토큰을 배분한다.
3. 토큰을 받은 사용자만 실제 참여 API를 호출한다.

### 언제 적합한가

- 선착순 이벤트 성격이 강한 경우
- 정원 제한이 매우 중요하고, API 입구에서 빠르게 컷오프하고 싶은 경우
- DB보다 Redis 같은 인메모리 자원으로 먼저 경쟁을 흡수하고 싶은 경우

### 장점

- DB에 도달하는 요청 수를 강하게 줄일 수 있다.
- 선착순 제어가 명확하다.
- 인기 행성 오픈 시 트래픽 폭주 완화에 효과적이다.

### 단점

- 토큰 만료, 재사용 방지, 위조 방지 설계가 필요하다.
- Redis 등 별도 저장소 의존성이 커진다.
- 토큰 획득과 실제 참여 확정 사이 불일치 상황을 다뤄야 한다.

### 예시 코드

Redis 기반 토큰 획득:

```java
@Service
@RequiredArgsConstructor
public class PlanetJoinTokenService {

	private final StringRedisTemplate redisTemplate;

	public boolean acquire(Long memberId, Long planetId, int maxParticipants) {
		String key = "planet:join:token:" + planetId;
		Long rank = redisTemplate.opsForValue().increment(key);
		if (rank == null) {
			return false;
		}
		if (rank > maxParticipants) {
			return false;
		}
		String tokenKey = "planet:join:permit:" + planetId + ":" + memberId;
		redisTemplate.opsForValue().set(tokenKey, "granted", Duration.ofMinutes(5));
		return true;
	}

	public boolean hasPermit(Long memberId, Long planetId) {
		String tokenKey = "planet:join:permit:" + planetId + ":" + memberId;
		return Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey));
	}
}
```

토큰 발급 API:

```java
@PostMapping("/planets/{planet-id}/join-token")
public ResponseEntity<BaseResponse<?>> issueJoinToken(@PathVariable("planet-id") Long planetId) {
	Long memberId = MemberContext.getMemberId();
	boolean granted = planetJoinTokenService.acquire(memberId, planetId, 100);
	if (!granted) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.ofFail(...));
	}
	return ResponseEntity.ok(BaseResponse.ofSuccess());
}
```

실제 참여 API:

```java
@PostMapping("/planets/{planet-id}")
public ResponseEntity<BaseResponse<?>> joinPlanet(@PathVariable("planet-id") Long planetId) {
	Long memberId = MemberContext.getMemberId();
	if (!planetJoinTokenService.hasPermit(memberId, planetId)) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.ofFail(...));
	}

	planetService.registerResident(
		PlanetSubscriptionDto.builder()
			.memberId(memberId)
			.planetId(planetId)
			.build()
	);

	return ResponseEntity.ok(BaseResponse.ofSuccess());
}
```

### PlanetRush 적용 포인트

- Redis를 이미 사용 중이라면 기술적으로 가장 붙이기 쉬운 대안 중 하나다.
- 선착순 행성 모집, 한정 인원 모집 같은 기능과 잘 맞는다.

## 3. 예약 테이블 분리 방식

### 개념

행성 엔티티의 `currentParticipants`를 직접 두드리는 대신, 참여 의사를 별도 `planet_join_request` 또는 `planet_reservation` 테이블에 먼저 기록하고, 확정 작업을 분리하는 방식이다.

즉 "행성 row 1개"에 경쟁을 집중시키지 않고, 요청 자체를 append 형태로 분산 저장하는 접근이다.

흐름 예시:

1. 사용자는 참여 요청을 보낸다.
2. 서버는 별도 예약 테이블에 요청을 insert 한다.
3. 배치/워커/트랜잭션 작업이 예약을 읽어 최종 확정한다.

### 언제 적합한가

- 실시간성보다 정확한 선별과 후처리가 중요한 경우
- 참여 요청 기록 자체를 모두 남기고 싶은 경우
- 감사 로그, 대기열, 승인 상태 관리가 필요한 경우

### 장점

- 충돌을 planet row 하나에 집중시키지 않는다.
- 요청 이력 관리가 쉬워진다.
- 상태 전이(`REQUESTED`, `CONFIRMED`, `REJECTED`)를 명확히 둘 수 있다.

### 단점

- 데이터 모델이 복잡해진다.
- 확정 작업이 추가되어 즉시성이 떨어질 수 있다.
- 중복 요청 방지용 유니크 제약과 상태 관리가 필요하다.

### 예시 코드

예약 엔티티:

```java
@Entity
@Table(
	name = "planet_join_request",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = {"planet_id", "member_id"})
	}
)
public class PlanetJoinRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "planet_id", nullable = false)
	private Long planetId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private JoinRequestStatus status;

	public static PlanetJoinRequest requested(Long planetId, Long memberId) {
		PlanetJoinRequest request = new PlanetJoinRequest();
		request.planetId = planetId;
		request.memberId = memberId;
		request.status = JoinRequestStatus.REQUESTED;
		return request;
	}

	public void confirm() {
		this.status = JoinRequestStatus.CONFIRMED;
	}

	public void reject() {
		this.status = JoinRequestStatus.REJECTED;
	}
}
```

예약 요청 저장:

```java
@Transactional
public void requestJoin(Long memberId, Long planetId) {
	planetJoinRequestRepository.save(PlanetJoinRequest.requested(planetId, memberId));
}
```

확정 처리:

```java
@Transactional
public void confirmJoinRequests(Long planetId) {
	List<PlanetJoinRequest> requests = planetJoinRequestRepository.findTop100ByPlanetIdAndStatusOrderByIdAsc(
		planetId,
		JoinRequestStatus.REQUESTED
	);

	Planet planet = planetRepository.findByIdForUpdate(planetId)
		.orElseThrow();

	for (PlanetJoinRequest request : requests) {
		if (planet.getCurrentParticipants() >= planet.getMaxParticipants()) {
			request.reject();
			continue;
		}

		planet.addParticipant();
		residentRepository.save(Resident.isNotCreator(
			memberRepository.getReferenceById(request.getMemberId()),
			planet
		));
		request.confirm();
	}
}
```

### PlanetRush 적용 포인트

- 선착순 모집과 대기자 관리가 필요해지면 가장 확장성 있는 구조다.
- 대신 현재 단순 참여 모델보다 설계 복잡도가 확실히 높아진다.

## 어떤 대안이 가장 현실적인가

PlanetRush 현재 구조와 Redis 사용 여부를 고려하면, 현실적인 우선순위는 보통 아래 순서다.

1. 선착순 토큰화
2. 큐잉
3. 예약 테이블 분리

이유:

- 선착순 토큰화는 현재 참여 API 앞단에 붙이기 쉽다.
- 큐잉은 처리 안정성을 높이지만 UX가 "즉시 확정"에서 멀어질 수 있다.
- 예약 테이블 분리는 가장 유연하지만, 설계 변경 폭이 가장 크다.

## 추천

5000명 같은 초고밀도 경쟁 구간을 실제로 다뤄야 한다면, 단순 락 전략 튜닝보다 아래 조합을 먼저 검토하는 것이 좋다.

1. Redis 기반 선착순 토큰화로 API 입구 트래픽 컷오프
2. 필요 시 큐잉으로 확정 처리 안정화
3. 장기적으로는 예약 테이블 분리로 요청 상태 모델 확장

즉 락은 마지막 방어선으로 두고, 그 앞단에서 경쟁 자체를 줄이는 방향이 더 현실적이다.
