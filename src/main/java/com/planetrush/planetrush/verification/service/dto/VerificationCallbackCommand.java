package com.planetrush.planetrush.verification.service.dto;

/**
 * 컨슈머 callback 인입 도메인 DTO.
 *
 * <p>Spec 005 — T019. {@code InternalVerificationResultController} 가 받은
 * {@code VerificationCallbackReq} (HTTP 표면) 를 본 record 로 매핑해
 * {@code VerificationResultService} 에 전달한다 (헌법 II — 어댑터/도메인 경계).
 *
 * <p>페이로드 분기 규칙(contracts/rest-api.md §3 / FR-004):
 * <ul>
 *   <li>{@code error} 가 비-null → 오류 결과 (status = ERROR). {@code similarityScore}/{@code verified} 무시.</li>
 *   <li>{@code error} 가 null + {@code verified}/{@code similarityScore} 둘 다 non-null → 정상 결과 (status = SUCCESS/FAIL).</li>
 *   <li>두 패턴 모두 어긋남 → 서비스 레이어가 {@link IllegalArgumentException} 으로 거절 (컨트롤러가 400 으로 변환).</li>
 * </ul>
 *
 * @param requestId       UUID — {@code VerificationRequest.id} 와 동일.
 * @param similarityScore 0~100. 정상 결과 시 non-null, 오류 결과 시 null.
 * @param verified        true/false. 정상 결과 시 non-null, 오류 결과 시 null.
 * @param error           컨슈머 오류 사유 (예: {@code image_load_failed}). 오류 결과 시 non-null.
 * @param message         오류 상세 메시지 (선택).
 */
public record VerificationCallbackCommand(
	String requestId,
	Integer similarityScore,
	Boolean verified,
	String error,
	String message
) {

	/**
	 * 오류 페이로드 여부. {@code error} 비-null 이면 ERROR 종착.
	 */
	public boolean isErrorPayload() {
		return error != null;
	}

	/**
	 * 정상 페이로드 여부. {@code verified} 와 {@code similarityScore} 둘 다 non-null.
	 */
	public boolean isNormalPayload() {
		return verified != null && similarityScore != null;
	}
}
