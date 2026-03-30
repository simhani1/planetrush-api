# PlanetRush API 명세

코드 기준으로 정리한 API 명세다. 별도 Swagger 정의가 아니라 현재 컨트롤러, DTO, 예외 핸들러 구현을 기준으로 작성했다.

## 공통 사항

### BaseResponse 형식

대부분의 API는 아래 형식으로 응답한다.

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {}
}
```

성공 시 `code=2000`, `message=성공`, 실패 시 `data=null` 이다.

### 인증

`@RequireJwtToken` 이 붙은 API는 `Authorization` 헤더가 필요하다.

```http
Authorization: Bearer {accessToken}
```

인증 실패 시 공통으로 아래 응답이 발생할 수 있다.

| HTTP 상태 | code | message | 설명 |
|---|---:|---|---|
| 401 | 1000 | 인증되지 않은 사용자입니다. | 토큰 없음 또는 잘못된 토큰 |
| 401 | 1001 | jwt 토큰이 만료되었습니다. | 만료 토큰 |
| 401 | 1002 | 토큰 발급자가 일치하지 않습니다. | 지원하지 않는 토큰 |
| 401 | 1003 | 토큰이 없거나 인증 과정에서 오류가 발생헀습니다. | 인증 과정 일반 실패 |

### 카테고리 값

카테고리 문자열은 아래 enum 값을 사용한다.

- `EXERCISE`
- `BEAUTY`
- `LIFE`
- `STUDY`
- `ETC`

### 주의 사항

- [`S3Controller.java`](/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/aws/controller/S3Controller.java)는 `BaseResponse`가 아니라 평문 JSON 객체를 반환한다.
- 일부 입력 오류는 전용 예외 핸들러가 없어서 `IllegalArgumentException` 으로 처리되고, 현재 코드상 401 `1003` 으로 응답될 수 있다.
- `AlreadyWithdrawnException`, `NicknameOverflowException`, 중복 가입/탈퇴 요청 예외는 전용 핸들러가 없어 500으로 전파될 가능성이 있다.

## 1. 인증 API

### 1.1 일반 로그인

- Method: `POST`
- URI: `/api/v1/auth/login`
- Auth: 필요 없음

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | String | 예 | 로그인 이메일 |
| `nickname` | String | 예 | 사용자 닉네임 |

요청 예시:

```http
POST /api/v1/auth/login?email=test@example.com&nickname=행성러너
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "nickname": "행성러너",
    "accessToken": "Bearer eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "9a74d3e2-....-1710740000000"
  }
}
```

발생 가능한 예외:

- 명시적 전용 예외 없음

### 1.2 카카오 로그인

- Method: `POST`
- URI: `/api/v1/auth/login/kakao`
- Auth: 필요 없음

요청 예시:

```json
{
  "accessToken": "kakao-access-token"
}
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "nickname": "행복한고래",
    "accessToken": "Bearer eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "9a74d3e2-....-1710740000000"
  }
}
```

발생 가능한 예외:

- 명시적 전용 예외 없음

### 1.3 카카오 로그아웃

- Method: `POST`
- URI: `/api/v1/auth/logout/kakao`
- Auth: 필요

요청 예시:

```json
{
  "refreshToken": "9a74d3e2-....-1710740000000"
}
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- 인증 공통 예외

### 1.4 회원 탈퇴

- Method: `PATCH`
- URI: `/api/v1/auth/exit`
- Auth: 필요

요청 본문:

- 없음

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- 처리되지 않은 예외: `AlreadyWithdrawnException`
- 인증 공통 예외

### 1.5 토큰 재발급

- Method: `POST`
- URI: `/api/v1/auth/reissue`
- Auth: 필요 없음

요청 예시:

```json
{
  "refreshToken": "9a74d3e2-....-1710740000000"
}
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "accessToken": "Bearer eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "c4d42f1b-....-1710749999999"
  }
}
```

발생 가능한 예외:

- `401 / 1003 UNAUTHORIZED_EXCEPTION`

## 2. 회원 API

### 2.1 완료한 행성 컬렉션 조회

- Method: `GET`
- URI: `/api/v1/members/collections`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `lh-id` | Long | 아니오 | 마지막 조회 history id |
| `size` | int | 예 | 페이지 크기 |

요청 예시:

```http
GET /api/v1/members/collections?lh-id=100&size=10
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "planetCollection": [
      {
        "historyId": 101,
        "name": "아침 러닝 30일",
        "category": "EXERCISE",
        "content": "매일 5km 러닝",
        "imageUrl": "https://cdn.example.com/planet/101.png",
        "progress": 96.5
      }
    ],
    "hasNext": true
  }
}
```

발생 가능한 예외:

- 인증 공통 예외

### 2.2 마이페이지 통계 조회

- Method: `GET`
- URI: `/api/v1/members/mypage`
- Auth: 필요

요청 본문:

- 없음

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "completionCnt": 12,
    "challengeCnt": 20,
    "myTotalAvg": 76.2,
    "myTotalPer": 82.5,
    "totalAvg": 61.7,
    "myExerciseAvg": 88.1,
    "myExercisePer": 91.0,
    "exerciseAvg": 63.2,
    "myBeautyAvg": 70.0,
    "myBeautyPer": 65.0,
    "beautyAvg": 58.4,
    "myLifeAvg": 60.5,
    "myLifePer": 55.5,
    "lifeAvg": 49.4,
    "myStudyAvg": 85.3,
    "myStudyPer": 88.4,
    "studyAvg": 68.2,
    "myEtcAvg": 77.0,
    "myEtcPer": 74.1,
    "etcAvg": 54.8
  }
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 7003 PROGRESS_AVG_NOT_FOUND`
- `500 / 7001 FLASK_SERVER_NOT_CONNECTED`
- 인증 공통 예외

### 2.3 닉네임 수정

- Method: `PATCH`
- URI: `/api/v1/members/profile`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `nickname` | String | 예 | 새 닉네임 |

요청 예시:

```http
PATCH /api/v1/members/profile?nickname=새닉네임
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- 처리되지 않은 예외: `NicknameOverflowException`
- 코드상 401로 매핑될 수 있는 입력 오류: `IllegalArgumentException`
- 인증 공통 예외

## 3. 행성 API

### 3.1 기본 행성 이미지 목록 조회

- Method: `GET`
- URI: `/api/v1/planets/images`
- Auth: 필요 없음

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": [
    {
      "imgId": 1,
      "imgUrl": "https://cdn.example.com/default/planet1.png"
    }
  ]
}
```

발생 가능한 예외:

- 명시적 전용 예외 없음

### 3.2 행성 생성

- Method: `POST`
- URI: `/api/v1/planets`
- Auth: 필요

요청 예시:

```json
{
  "name": "기상 인증 챌린지",
  "content": "매일 오전 6시 전에 기상 인증",
  "category": "LIFE",
  "startDate": "2026-03-20",
  "endDate": "2026-04-18",
  "maxParticipants": 5,
  "authCond": "기상 직후 시계가 보이게 촬영",
  "planetImgUrl": "https://cdn.example.com/planet/custom.png",
  "standardVerificationImgUrl": "https://cdn.example.com/planet/standard.png"
}
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5004 INVALID_START_DATE`
- `400 / 6004 RESIDENT_OVERFLOW`
- 코드상 401로 매핑될 수 있는 입력 오류: `IllegalArgumentException` (`category` enum 변환 실패 등)
- 인증 공통 예외

### 3.3 행성 검색

- Method: `GET`
- URI: `/api/v1/planets`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `category` | String | 아니오 | 카테고리 |
| `keyword` | String | 아니오 | 검색 키워드 |
| `lp-id` | Long | 아니오 | 마지막 행성 id |
| `size` | int | 예 | 페이지 크기 |

요청 예시:

```http
GET /api/v1/planets?category=EXERCISE&keyword=러닝&lp-id=200&size=10
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "planets": [
      {
        "planetId": 210,
        "planetImg": "https://cdn.example.com/planet/210.png",
        "standardVerificationImg": null,
        "verificationCond": null,
        "category": "EXERCISE",
        "name": "새벽 러닝",
        "content": "아침 5km 러닝",
        "startDate": "2026-03-21",
        "endDate": "2026-04-20",
        "currentParticipants": 3,
        "maxParticipants": 10,
        "planetStatus": "READY",
        "joined": false
      }
    ],
    "hasNext": true
  }
}
```

발생 가능한 예외:

- 인증 공통 예외

### 3.4 시작 전 행성 상세 조회

- Method: `GET`
- URI: `/api/v1/planets/detail`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `planet-id` | Long | 예 | 행성 id |

요청 예시:

```http
GET /api/v1/planets/detail?planet-id=210
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "planetId": 210,
    "planetImg": "https://cdn.example.com/planet/210.png",
    "standardVerificationImg": "https://cdn.example.com/planet/standard.png",
    "verificationCond": "전신이 보이게 촬영",
    "category": "EXERCISE",
    "name": "새벽 러닝",
    "content": "아침 5km 러닝",
    "startDate": "2026-03-21",
    "endDate": "2026-04-20",
    "currentParticipants": 3,
    "maxParticipants": 10,
    "planetStatus": "READY",
    "isJoined": false
  }
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5000 PLANET_NOT_FOUND`
- 인증 공통 예외

### 3.5 진행 중 행성 상세 조회

- Method: `GET`
- URI: `/api/v1/planets/ongoing`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `planet-id` | Long | 예 | 행성 id |

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": {
    "planetId": 210,
    "planetImg": "https://cdn.example.com/planet/210.png",
    "standardVerificationImg": "https://cdn.example.com/planet/standard.png",
    "category": "EXERCISE",
    "name": "새벽 러닝",
    "content": "아침 5km 러닝",
    "startDate": "2026-03-21",
    "endDate": "2026-04-20",
    "totalVerificationCnt": 30,
    "residents": [
      {
        "memberId": 1,
        "nickname": "행성러너",
        "isQuerriedMember": true,
        "verificationCnt": 6,
        "verificationContinuityPoint": 6.3
      }
    ],
    "verifiedToday": false
  }
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5000 PLANET_NOT_FOUND`
- 인증 공통 예외

### 3.6 내 행성 목록 조회

- Method: `GET`
- URI: `/api/v1/planets/me/list`
- Auth: 필요

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": [
    {
      "planetId": 210,
      "planetImg": "https://cdn.example.com/planet/210.png",
      "category": "EXERCISE",
      "name": "새벽 러닝",
      "content": "아침 5km 러닝",
      "startDate": "2026-03-21",
      "endDate": "2026-04-20",
      "currentParticipants": 3,
      "maxParticipants": 10,
      "status": "IN_PROGRESS"
    }
  ]
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- 인증 공통 예외

### 3.7 메인 행성 목록 조회

- Method: `GET`
- URI: `/api/v1/planets/main/list`
- Auth: 필요

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": [
    {
      "planetId": 210,
      "planetImgUrl": "https://cdn.example.com/planet/210.png",
      "name": "새벽 러닝",
      "status": "IN_PROGRESS",
      "isLastDay": false
    }
  ]
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- 인증 공통 예외

### 3.8 행성 참여

- Method: `POST`
- URI: `/api/v1/planets/{planet-id}`
- Auth: 필요

요청 예시:

```http
POST /api/v1/planets/210
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5000 PLANET_NOT_FOUND`
- `400 / 5003 PLANET_IS_DESTROYED`
- `400 / 6001 ALREADY_EXIST_RESIDENT`
- `400 / 6003 REGISTER_RESIDENT_TIMEOUT`
- `400 / 6004 RESIDENT_OVERFLOW`
- 처리되지 않은 예외: `DuplicatedRegisterResidentRequestException`
- 인증 공통 예외

### 3.9 행성 탈퇴

- Method: `DELETE`
- URI: `/api/v1/planets/{planet-id}`
- Auth: 필요

요청 예시:

```http
DELETE /api/v1/planets/210
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5000 PLANET_NOT_FOUND`
- `400 / 5001 PARTICIPANTS_OVERFLOW`
- `400 / 6000 RESIDENT_NOT_FOUND`
- `400 / 6002 RESIDENT_EXIT_TIMEOUT`
- 처리되지 않은 예외: `DuplicatedDeleteResidentRequestException`
- 인증 공통 예외

## 4. 인증 사진 검증 API

### 4.1 오늘의 챌린지 인증

- Method: `POST`
- URI: `/api/v1/verify/planets/{planet-id}`
- Auth: 필요

요청 예시:

```json
{
  "verificationImgUrl": "https://cdn.example.com/verification/20260318.png"
}
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": null
}
```

발생 가능한 예외:

- `400 / 3000 MEMBER_NOT_FOUND`
- `400 / 5000 PLANET_NOT_FOUND`
- `400 / 7000 INVALID_IMAGE_URL_COUNT`
- `400 / 7002 IMAGE_SIMILARITY_CHECK_ERROR`
- `400 / 8000 ALREADY_VERIFIED`
- `500 / 7001 FLASK_SERVER_NOT_CONNECTED`
- 인증 공통 예외

## 5. S3 API

### 5.1 Presigned URL 발급

- Method: `POST`
- URI: `/api/v1/aws/s3/presigned-url`
- Auth: 필요

요청 예시:

```json
{
  "imageType": "verification",
  "fileName": "proof.png"
}
```

지원 `imageType` 값:

- `planet`
- `standard`
- `verification`

응답 예시:

```json
{
  "presignedUrl": "https://bucket.s3.ap-northeast-2.amazonaws.com/verification/1/20260318120000_uuid.png?...",
  "fileName": "proof.png"
}
```

발생 가능한 예외:

- `400 / 4001 FAIL_TO_UPLOAD_FILE`
- 코드상 401로 매핑될 수 있는 입력 오류: `IllegalArgumentException` (`imageType` 값 오류)
- 인증 공통 예외

## 6. 추천 API

### 6.1 인기 키워드 조회

- Method: `GET`
- URI: `/api/v1/recommend/keyword`
- Auth: 필요

쿼리 파라미터:

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `category` | String | 예 | 카테고리 enum 문자열 |

요청 예시:

```http
GET /api/v1/recommend/keyword?category=EXERCISE
```

응답 예시:

```json
{
  "code": "2000",
  "isSuccess": true,
  "message": "성공",
  "data": [
    {
      "keyword": "러닝",
      "category": "EXERCISE"
    },
    {
      "keyword": "홈트",
      "category": "EXERCISE"
    }
  ]
}
```

발생 가능한 예외:

- 코드상 401로 매핑될 수 있는 입력 오류: `IllegalArgumentException` (`category` 값 오류)
- 인증 공통 예외

## 7. 예외 코드 표

| code | HTTP 상태 | message |
|---:|---:|---|
| 1000 | 401 | 인증되지 않은 사용자입니다. |
| 1001 | 401 | jwt 토큰이 만료되었습니다. |
| 1002 | 401 | 토큰 발급자가 일치하지 않습니다. |
| 1003 | 401 | 토큰이 없거나 인증 과정에서 오류가 발생헀습니다. |
| 3000 | 400 | 회원이 존재하지 않습니다. |
| 4001 | 400 | AWS 서비스가 원활하지 않아 사진 업로드에 실패했습니다. |
| 5000 | 400 | 행성이 존재하지 않습니다. |
| 5001 | 400 | 최대 참여 인원 수를 초과했습니다. |
| 5003 | 400 | 이미 파괴된 행성입니다. |
| 5004 | 400 | 행성의 시작 날짜가 제한 범위를 벗어났습니다. |
| 6000 | 400 | 거주자가 존재하지 않습니다. |
| 6001 | 400 | 이미 행성에 거주 중입니다. |
| 6002 | 400 | 챌린지가 시작되어 행성을 떠날 수 없습니다. |
| 6003 | 400 | 챌린지가 시작되어 참여할 수 없습니다. |
| 6004 | 400 | 최대 참여 행성 수를 초과했습니다. |
| 7000 | 400 | 유사도 검사에 필요한 이미지 개수가 부족합니다. |
| 7001 | 500 | 플라스크 서버 연결에 실패했습니다. |
| 7002 | 400 | 이미지 유사도 측정 과정에서 에러가 발생했습니다. |
| 7003 | 400 | 평균 진행률 데이터가 존재하지 않습니다. |
| 8000 | 400 | 오늘 이미 인증을 완료한 사용자입니다. |
