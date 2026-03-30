import http from 'k6/http';
import {check, sleep} from 'k6';
import exec from 'k6/execution';
import {Counter, Rate, Trend} from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PLANET_ID = __ENV.PLANET_ID;
const USER_COUNT = Number(__ENV.USER_COUNT || 200);
const LOGIN_INTERVAL_SECONDS = Number(__ENV.LOGIN_INTERVAL_SECONDS || 0.2);

if (!PLANET_ID) {
	throw new Error('PLANET_ID 환경변수가 필요합니다.');
}

export const options = {
	setupTimeout: '30m',
	scenarios: {
		join_planet: {
			executor: 'shared-iterations',
			vus: USER_COUNT,
			iterations: USER_COUNT,
			maxDuration: '30m',
		},
	},
};

const loginDuration = new Trend('login_duration');
const joinDuration = new Trend('join_duration');
const loginSuccessRate = new Rate('login_success_rate');
const join200Rate = new Rate('join_200_rate');
const join400Rate = new Rate('join_400_rate');
const joinOtherRate = new Rate('join_other_rate');
const loginFailureCount = new Counter('login_failure_count');
const joinFailureCount = new Counter('join_failure_count');

function buildLoginIdentity(index) {
	return {
		email: `load-user-${index + 1}@planetrush.test`,
		nickname: `loaduser${index + 1}`,
	};
}

function login(identity) {
	const url = `${BASE_URL}/api/v1/auth/login?email=${encodeURIComponent(identity.email)}&nickname=${encodeURIComponent(identity.nickname)}`;
	const response = http.post(url, null, {
		tags: { name: 'auth_login' },
	});

	loginDuration.add(response.timings.duration);

	const ok = check(response, {
		'login status is 200': (res) => res.status === 200,
		'login response has access token': (res) => {
			const json = res.json();
			return Boolean(json?.data?.accessToken);
		},
	});

	loginSuccessRate.add(ok);

	if (!ok) {
		loginFailureCount.add(1);
		throw new Error(
			`로그인 실패: email=${identity.email}, status=${response.status}, body=${response.body}`
		);
	}

	return response.json().data.accessToken;
}

export function setup() {
	const tokens = [];

	for (let i = 0; i < USER_COUNT; i++) {
		const identity = buildLoginIdentity(i);
		const token = login(identity);
		tokens.push(token);

		if (i < USER_COUNT - 1) {
			sleep(LOGIN_INTERVAL_SECONDS);
		}
	}

	return { tokens };
}

export default function (data) {
	const tokenIndex = exec.scenario.iterationInTest;
	const accessToken = data.tokens[tokenIndex];

	const response = http.post(`${BASE_URL}/api/v1/planets/${PLANET_ID}`, null, {
		headers: {
			Authorization: accessToken,
		},
		tags: { name: 'planet_join' },
	});

	joinDuration.add(response.timings.duration);

	const is200 = response.status === 200;
	const is400 = response.status === 400;
	const isOther = !is200 && !is400;

	join200Rate.add(is200);
	join400Rate.add(is400);
	joinOtherRate.add(isOther);

	check(response, {
		'join request completed': (res) => res.status !== 0,
	});

	if (isOther) {
		joinFailureCount.add(1);
	}
}

