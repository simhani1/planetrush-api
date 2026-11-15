package com.planetrush.planetrush.verification.event;

/**
 * 워커 스레드의 유사도 검사 완료를 알리는 Spring 내부 이벤트.
 *
 * @param requestId       {@code VerificationRequest.id}
 * @param verified        인증 여부. 오류 시 null
 * @param similarityScore 유사도 점수. 오류 시 null
 * @param error           오류 사유. 정상 시 null
 */
public record SaveVerificationResultEvent(
	String requestId,
	Boolean verified,
	Double similarityScore,
	String error
) {

	public static SaveVerificationResultEvent success(String requestId, boolean verified, double similarityScore) {
		return new SaveVerificationResultEvent(requestId, verified, similarityScore, null);
	}

	public static SaveVerificationResultEvent error(String requestId, String error) {
		return new SaveVerificationResultEvent(requestId, null, null, error);
	}
}
