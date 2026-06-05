package com.planetrush.planetrush.verification.domain;

/**
 * 인증 요청 라이프사이클 상태.
 *
 * <p>Spec 005 — data-model.md §VerificationRequest 라이프사이클(state machine).
 *
 * <ul>
 *   <li>{@link #PENDING} — 요청 접수 직후 진입. 컨슈머 callback 도착 시 종착 상태로 전이.</li>
 *   <li>{@link #SUCCESS} — 컨슈머 추론 결과 {@code verified=true}. {@code VerificationRecord} 1건 저장 트리거.</li>
 *   <li>{@link #FAIL} — 컨슈머 추론 결과 {@code verified=false}. {@code VerificationRecord} 1건 저장 트리거.</li>
 *   <li>{@link #ERROR} — 컨슈머 처리 불가 사유 보고({@code image_load_failed} 등).
 *       Clarify Q2 — 본 종착은 {@code VerificationRecord}를 저장하지 않아 사용자의
 *       오늘의 재시도 권한을 보존한다.</li>
 * </ul>
 *
 * <p>종착 상태(SUCCESS/FAIL/ERROR) 진입 후 PENDING 으로 되돌리는 전이는 없다 —
 * {@code UPDATE ... WHERE status='PENDING'} optimistic update 절이 SQL 레벨에서 강제 (R-002).
 */
public enum VerificationRequestStatus {
	PENDING,
	SUCCESS,
	FAIL,
	ERROR
}
