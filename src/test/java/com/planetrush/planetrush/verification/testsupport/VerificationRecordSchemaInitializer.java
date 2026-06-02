package com.planetrush.planetrush.verification.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 통합 테스트 부팅 시 {@code verification_record} 의 generated column 과 unique constraint 를
 * 멱등하게 적용하는 초기화기.
 *
 * <p>Spec 005 — phase2-verify.md 의 회귀 근본 원인 해소. 기존 {@code schema.sql} 방식은
 * stored procedure 본문의 {@code ;} 가 Spring {@code ScriptUtils.executeSqlScript} 의 단순 split
 * 과 충돌해 모든 {@code @SpringBootTest} ApplicationContext 부팅을 실패시켰다(Spec 001/002 회귀).
 * 본 클래스는 JDBC API 로 INFORMATION_SCHEMA 를 조회한 뒤 동적으로 ALTER 를 발행해 위 문제를 우회한다.
 *
 * <h3>적용 방식</h3>
 * <ul>
 *   <li>{@link IntegrationTest} 베이스 클래스 위에 {@code @Import(VerificationRecordSchemaInitializer.class)}
 *       부착 — 자식 통합 테스트 전부에 자동 전파.</li>
 *   <li>{@link ApplicationRunner} 가 Spring 부팅 완료 직후 실행되므로 hibernate {@code ddl-auto=update}
 *       가 테이블을 만든 이후에 동작.</li>
 *   <li>Testcontainers {@code withReuse(true)} 안전 — INFORMATION_SCHEMA 가드로 멱등.</li>
 * </ul>
 *
 * <h3>헌법 정합</h3>
 * <ul>
 *   <li>헌법 II — 본 컴포넌트는 테스트 인프라이며 production 어댑터 규칙 비대상.</li>
 *   <li>헌법 V — 로그는 ALTER 적용 사실만 기록, 시크릿 키워드 미사용.</li>
 * </ul>
 */
@TestConfiguration
public class VerificationRecordSchemaInitializer {

	private static final Logger log = LoggerFactory.getLogger(VerificationRecordSchemaInitializer.class);

	private static final String TABLE = "verification_record";
	private static final String COLUMN = "upload_date_only";
	private static final String UNIQUE_NAME = "uniq_verification_record_member_planet_date";

	@Bean
	ApplicationRunner initVerificationRecordUniqueIndex(DataSource dataSource) {
		return args -> {
			try (Connection conn = dataSource.getConnection()) {
				if (!tableExists(conn, TABLE)) {
					// hibernate 가 아직 테이블을 만들지 못한 경우 — 통합 테스트가 verification 도메인을
					// 다루지 않는 시나리오일 수 있으므로 silently skip. (보수 결정 — 다음 부팅에 재시도.)
					log.debug("verification_record 테이블이 아직 존재하지 않음 — schema init skip");
					return;
				}
				if (!columnExists(conn, TABLE, COLUMN)) {
					execute(conn,
						"ALTER TABLE " + TABLE
							+ " ADD COLUMN " + COLUMN + " DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED");
					log.info("[schema-init] added generated column {}.{}", TABLE, COLUMN);
				}
				if (!uniqueConstraintExists(conn, TABLE, UNIQUE_NAME)) {
					execute(conn,
						"ALTER TABLE " + TABLE
							+ " ADD CONSTRAINT " + UNIQUE_NAME
							+ " UNIQUE (member_id, planet_id, " + COLUMN + ")");
					log.info("[schema-init] added unique constraint {} on {}", UNIQUE_NAME, TABLE);
				}
			}
		};
	}

	private static boolean tableExists(Connection conn, String table) throws SQLException {
		String sql = "SELECT 1 FROM INFORMATION_SCHEMA.TABLES"
			+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, table);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
		String sql = "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS"
			+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, table);
			ps.setString(2, column);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static boolean uniqueConstraintExists(Connection conn, String table, String name) throws SQLException {
		String sql = "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
			+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, table);
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private static void execute(Connection conn, String ddl) throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute(ddl);
		}
	}
}
