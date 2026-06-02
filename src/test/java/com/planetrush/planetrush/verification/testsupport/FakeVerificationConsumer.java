package com.planetrush.planetrush.verification.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 통합 테스트용 가짜 컨슈머.
 *
 * <p>Spec 005 — R-006. Redis Stream `verify:requests` 를 {@code XREADGROUP} 으로 폴링하여
 * 메시지를 받으면 Spring 의 callback 엔드포인트({@code POST /api/v1/internal/verification-results})
 * 를 호출한다. 실제 컨슈머(Python + PyTorch)를 띄우지 않고 chaos 시나리오를 결정론적으로 재현한다.
 *
 * <h3>책임 분리 (헌법 II)</h3>
 * <p>본 컴포넌트는 테스트 인프라이며 production 도메인 코드가 아니다. Redis Stream {@code XREADGROUP}
 * 직접 호출은 외부 컨슈머의 책임을 시뮬레이션하는 것으로, production 코드의 Redis 직접 호출 금지
 * 규칙(헌법 IV) 과 충돌하지 않는다.
 *
 * <h3>사용 패턴</h3>
 * <pre>{@code
 * @Import(FakeVerificationConsumer.class)   // @TestComponent 는 자동 스캔되지 않음
 * class VerificationAsyncFlowIntegrationTest extends IntegrationTest {
 *     @Autowired FakeVerificationConsumer fakeConsumer;
 *
 *     @Test
 *     void scenario() {
 *         fakeConsumer.setCallbackBaseUrl("http://localhost:" + port);
 *         fakeConsumer.setNextResult(CallbackResult.success(85));
 *         fakeConsumer.start();
 *         // ... 인증 요청 발행 + Awaitility 대기 ...
 *         fakeConsumer.stop();
 *     }
 * }
 * }</pre>
 *
 * <h3>chaos 시나리오</h3>
 * <ul>
 *   <li>{@link #stop()} — 폴링 루프 중단. 스트림 메시지가 누적된다(컨슈머 다운 모의, SC-003).</li>
 *   <li>{@link #start()} 재호출 — 누적분이 순차 소비된다(컨슈머 복구 모의).</li>
 *   <li>{@link #setNextResult(CallbackResult)} / {@link #enqueueResults(List)} — 메시지별 결과 주입.
 *       큐가 비면 기본값({@link CallbackResult#success(int)} similarityScore=85)을 사용한다.</li>
 * </ul>
 *
 * <h3>스레드 안전성</h3>
 * <p>{@link #start()}/{@link #stop()} 는 동일 인스턴스에 다수 호출 가능하지만, 동시 호출은 금지한다 —
 * 단일 통합 테스트 라이프사이클 안에서만 사용.
 */
@TestComponent
public class FakeVerificationConsumer {

	private static final Logger log = LoggerFactory.getLogger(FakeVerificationConsumer.class);

	private static final String CONSUMER_GROUP = "verifiers";
	private static final String CONSUMER_NAME = "fake-consumer-1";
	private static final Duration READ_BLOCK = Duration.ofMillis(100);
	private static final int READ_COUNT = 10;

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final RestTemplate restTemplate = new RestTemplate();

	public FakeVerificationConsumer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	@Value("${verification.redis.stream-key:verify:requests}")
	private String streamKey;

	/** 폴링 루프 활성 플래그. */
	private final AtomicBoolean running = new AtomicBoolean(false);

	/** 처리된 메시지 누계 — {@link #runUntilProcessed(int, Duration)} 기준. */
	private final AtomicInteger processedCount = new AtomicInteger(0);

	/** 메시지별 callback 응답 큐. 비면 default 결과 사용. */
	private final Deque<CallbackResult> resultQueue = new ArrayDeque<>();

	/** 테스트가 주입하는 Spring 부팅 base URL (예: {@code http://localhost:<port>}). */
	private volatile String callbackBaseUrl;

	/** 폴링 스레드 핸들. */
	private volatile Thread workerThread;

	// ---------------------------------------------------------------------
	// 테스트 제어 API
	// ---------------------------------------------------------------------

	/**
	 * Spring 부팅 URL 을 주입한다 — 테스트의 {@code @LocalServerPort} 로부터 받는다.
	 */
	public void setCallbackBaseUrl(String callbackBaseUrl) {
		this.callbackBaseUrl = callbackBaseUrl;
	}

	/** 다음 메시지에 사용할 callback 결과 1개를 주입한다(LIFO 아닌 FIFO). */
	public synchronized void setNextResult(CallbackResult result) {
		resultQueue.offerLast(result);
	}

	/** 여러 결과를 순서대로 enqueue 한다. */
	public synchronized void enqueueResults(List<CallbackResult> results) {
		resultQueue.addAll(results);
	}

	/** 폴링 루프를 시작한다(이미 동작 중이면 no-op). */
	public synchronized void start() {
		if (running.get()) {
			return;
		}
		ensureGroupExists();
		running.set(true);
		workerThread = new Thread(this::pollLoop, "fake-verification-consumer");
		workerThread.setDaemon(true);
		workerThread.start();
	}

	/** 폴링 루프를 중단한다(컨슈머 다운 모의). */
	public synchronized void stop() {
		running.set(false);
		Thread t = workerThread;
		if (t != null) {
			try {
				t.join(Duration.ofSeconds(3).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			workerThread = null;
		}
	}

	/** 단일 메시지만 동기 처리한다(블로킹). 메시지가 없으면 false. */
	public boolean processOne(Duration timeout) {
		ensureGroupExists();
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (readAndProcess()) {
				return true;
			}
			sleepQuiet(50);
		}
		return false;
	}

	/** {@code n} 개 메시지가 처리될 때까지 대기(start 가 호출된 상태에서). */
	public boolean runUntilProcessed(int n, Duration timeout) {
		int target = processedCount.get() + n;
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			if (processedCount.get() >= target) {
				return true;
			}
			sleepQuiet(50);
		}
		return processedCount.get() >= target;
	}

	/** 컨슈머 그룹과 stream 자체를 깨끗이 초기화한다. */
	public void clearGroup() {
		try {
			redisTemplate.delete(streamKey);
		} catch (RuntimeException e) {
			log.debug("stream delete skipped: {}", e.getMessage());
		}
	}

	/** 처리된 메시지 누계. */
	public int processedCount() {
		return processedCount.get();
	}

	// ---------------------------------------------------------------------
	// 내부 폴링 로직
	// ---------------------------------------------------------------------

	private void pollLoop() {
		while (running.get()) {
			try {
				readAndProcess();
			} catch (RuntimeException e) {
				log.warn("fake-consumer poll iteration failed", e);
				sleepQuiet(100);
			}
		}
	}

	private boolean readAndProcess() {
		StreamReadOptions options = StreamReadOptions.empty()
			.block(READ_BLOCK)
			.count(READ_COUNT);
		List<MapRecord<String, Object, Object>> records;
		try {
			records = redisTemplate.opsForStream().read(
				Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
				options,
				StreamOffset.create(streamKey, ReadOffset.lastConsumed())
			);
		} catch (RedisSystemException e) {
			// group not exist 등 — 다시 보장 후 0건 처리
			ensureGroupExists();
			return false;
		}
		if (records == null || records.isEmpty()) {
			return false;
		}
		for (MapRecord<String, Object, Object> record : records) {
			handleRecord(record);
		}
		return true;
	}

	private void handleRecord(MapRecord<String, Object, Object> record) {
		Map<Object, Object> raw = record.getValue();
		String requestId = stringValue(raw.get("requestId"));
		if (requestId == null) {
			// 신/구 키 양쪽 호환: 신규 contract 는 requestId, Spec 002 의 기존 키는 eventId.
			requestId = stringValue(raw.get("eventId"));
		}
		if (requestId == null) {
			log.warn("fake-consumer received record without requestId; skipping. id={}", record.getId());
			ack(record);
			return;
		}
		CallbackResult result;
		synchronized (this) {
			result = resultQueue.pollFirst();
		}
		if (result == null) {
			result = CallbackResult.success(85);
		}
		postCallback(requestId, result);
		ack(record);
		processedCount.incrementAndGet();
	}

	private void postCallback(String requestId, CallbackResult result) {
		String base = callbackBaseUrl;
		if (base == null || base.isBlank()) {
			throw new IllegalStateException(
				"FakeVerificationConsumer.callbackBaseUrl 가 비어 있다 — 테스트에서 setCallbackBaseUrl() 호출 필요"
			);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		if (result.error == null) {
			body.put("similarityScore", result.similarityScore);
			body.put("verified", result.verified);
		} else {
			body.put("error", result.error);
			body.put("message", result.message);
		}
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			String json = objectMapper.writeValueAsString(body);
			String url = base + "/api/v1/internal/verification-results";
			restTemplate.postForEntity(url, new HttpEntity<>(json, headers), String.class);
		} catch (Exception e) {
			// 헌법 V — payload 평문 미출현. requestId 만 로그.
			log.warn("fake-consumer callback failed requestId={}", requestId, e);
		}
	}

	private void ack(MapRecord<String, Object, Object> record) {
		try {
			redisTemplate.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, record.getId());
		} catch (RuntimeException e) {
			log.debug("ack skipped requestId={}: {}", record.getId(), e.getMessage());
		}
	}

	private void ensureGroupExists() {
		try {
			redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), CONSUMER_GROUP);
		} catch (RedisSystemException e) {
			// already exists — 무시
			log.debug("consumer group ensure skipped: {}", e.getMessage());
		} catch (RuntimeException e) {
			log.debug("consumer group ensure skipped (unexpected): {}", e.getMessage());
		}
	}

	private static String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private static void sleepQuiet(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------------
	// 결과 주입 DTO
	// ---------------------------------------------------------------------

	/** 메시지별 callback 응답 주입용. {@link #success}/{@link #fail}/{@link #error} 정적 팩토리 사용. */
	public static final class CallbackResult {
		final Integer similarityScore;
		final Boolean verified;
		final String error;
		final String message;

		private CallbackResult(Integer similarityScore, Boolean verified, String error, String message) {
			this.similarityScore = similarityScore;
			this.verified = verified;
			this.error = error;
			this.message = message;
		}

		public static CallbackResult success(int similarityScore) {
			return new CallbackResult(similarityScore, true, null, null);
		}

		public static CallbackResult fail(int similarityScore) {
			return new CallbackResult(similarityScore, false, null, null);
		}

		public static CallbackResult error(String code, String message) {
			return new CallbackResult(null, null, code, message);
		}
	}
}
