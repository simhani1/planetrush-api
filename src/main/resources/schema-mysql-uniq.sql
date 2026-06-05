-- Spec 005 — 운영 MySQL 수동 적용 DDL (참조 전용; Spring 부트시 자동 실행 안 함).
--
-- application.yml 의 `spring.sql.init.mode=never` 기본을 유지하고, 본 스크립트는 운영자가
-- 수동으로 적용한다. quickstart §6-2 절차 참조.
--
-- 사전 점검 (R-003) — 같은 사용자·챌린지·날짜로 record 가 2건 이상 있으면 적용 실패. 운영
-- 데이터 정리 후 본 스크립트 실행:
--
--   SELECT member_id, planet_id, DATE(upload_date) d, COUNT(*) c
--     FROM verification_record
--    GROUP BY member_id, planet_id, d
--   HAVING c > 1;
--
-- 결과 0 row 면 안전.

-- (1) Generated column 추가
ALTER TABLE verification_record
    ADD COLUMN upload_date_only DATE
        GENERATED ALWAYS AS (DATE(upload_date)) STORED;

-- (2) 사용자·챌린지·날짜 단위 unique 안전망 (R-003)
ALTER TABLE verification_record
    ADD CONSTRAINT uniq_verification_record_member_planet_date
    UNIQUE (member_id, planet_id, upload_date_only);
